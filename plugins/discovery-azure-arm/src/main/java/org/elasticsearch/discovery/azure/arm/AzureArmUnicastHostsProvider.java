/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.discovery.azure.arm;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.cloud.azure.arm.AzureManagementService;
import org.elasticsearch.cloud.azure.arm.AzureManagementService.Discovery;
import org.elasticsearch.cloud.azure.arm.AzureVirtualMachine;
import org.elasticsearch.cloud.azure.arm.AzureVirtualMachine.PowerState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.zen.UnicastHostsProvider;
import org.elasticsearch.transport.TransportService;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.elasticsearch.cloud.azure.arm.AzureManagementService.HostType.PRIVATE_IP;
import static org.elasticsearch.cloud.azure.arm.AzureManagementService.HostType.PUBLIC_IP;

public class AzureArmUnicastHostsProvider implements UnicastHostsProvider {

    private static final Logger logger = Loggers.getLogger(AzureArmUnicastHostsProvider.class);

    private final AzureManagementService azureManagementService;
    private TransportService transportService;

    private long lastRefresh;
    private List<DiscoveryNode> cachedDiscoNodes;
    private final Settings settings;


    public AzureArmUnicastHostsProvider(Settings settings, AzureManagementService azureManagementService,
                                        TransportService transportService) {
        this.settings = settings;
        this.azureManagementService = azureManagementService;
        this.transportService = transportService;

        if (Discovery.REGION_SETTING.exists(settings) == false) {
            logger.debug("starting discover without region set. This should be avoided if some nodes can be started in multiple regions.");
        }
    }

    /**
     * We build the list of Nodes from Azure Management API
     * Information can be cached using `discovery.azure-arm.refresh_interval` property if needed.
     * Setting `discovery.azure-arm.refresh_interval` to `-1` will cause infinite caching.
     * Setting `discovery.azure-arm.refresh_interval` to `0` will disable caching (default).
     */
    @Override
    public List<DiscoveryNode> buildDynamicNodes() {
        // Evaluating refresh settings here will allow live update of this setting
        TimeValue refreshInterval = Discovery.REFRESH_SETTING.get(settings);
        if (refreshInterval.millis() != 0) {
            if (cachedDiscoNodes != null &&
                    (refreshInterval.millis() < 0 || (System.currentTimeMillis() - lastRefresh) < refreshInterval.millis())) {
                logger.trace("using cache to retrieve node list");
                return cachedDiscoNodes;
            }
            lastRefresh = System.currentTimeMillis();
        }
        logger.debug("start building nodes list using Azure API");

        cachedDiscoNodes = new ArrayList<>();

        List<AzureVirtualMachine> vms = azureManagementService.getVirtualMachines(Discovery.HOST_GROUP_NAME_SETTING.get(settings));

        for (AzureVirtualMachine vm : vms) {
            // We check current power state
            // No needs to include VMs which are stopped or going to be stopped
            if (vm.getPowerState() != PowerState.STARTING && vm.getPowerState() != PowerState.RUNNING) {
                logger.debug("[{}] status is [{}]. skipping...", vm.getName(), vm.getPowerState());
                continue;
            }

            // If provided, we check the region name.
            if (Discovery.REGION_SETTING.exists(settings)) {
                String region = Discovery.REGION_SETTING.get(settings);
                if (region.equals(vm.getRegion()) == false) {
                    logger.debug("current region [{}] does not match [{}]. skipping...", vm.getRegion(), region);
                    continue;
                }
            }

            // If provided, we check the group name. It can be a wildcard
            if (Discovery.HOST_GROUP_NAME_SETTING.exists(settings)) {
                String groupName = Discovery.HOST_GROUP_NAME_SETTING.get(settings);
                boolean match;
                if (Regex.isSimpleMatchPattern(groupName)) {
                    match = Regex.simpleMatch(groupName, vm.getGroupName());
                } else {
                    match = groupName.equals(vm.getGroupName());
                }

                if (match == false) {
                    logger.debug("current group name [{}] does not match [{}]. skipping...", vm.getGroupName(), groupName);
                    continue;
                }
            }

            // If provided, we check the host name. It can be a wildcard
            if (Discovery.HOST_NAME_SETTING.exists(settings)) {
                String hostName = Discovery.HOST_NAME_SETTING.get(settings);
                boolean match;
                if (Regex.isSimpleMatchPattern(hostName)) {
                    match = Regex.simpleMatch(hostName, vm.getName());
                } else {
                    match = hostName.equals(vm.getName());
                }

                if (match == false) {
                    logger.debug("current name [{}] does not match [{}]. skipping...", vm.getName(), hostName);
                    continue;
                }
            }

            // In other case, it should be the right deployment so we can add it to the list of instances
            String networkAddress = null;
            InetAddress ip = null;
            // Let's detect if we want to use public or private IP
            AzureManagementService.HostType hostType = Discovery.HOST_TYPE_SETTING.get(settings);
            if (hostType.equals(PRIVATE_IP)) {
                if (vm.getPrivateIp() == null) {
                    logger.trace("no private ip available. ignoring [{}]...", vm.getName());
                } else {
                    ip = InetAddresses.forString(vm.getPrivateIp());
                }
            } else if (hostType.equals(PUBLIC_IP)) {
                if (vm.getPublicIp() == null) {
                    logger.trace("no public ip available. ignoring [{}]...", vm.getName());
                } else {
                    ip = InetAddresses.forString(vm.getPublicIp());
                }
            }

            if (ip == null) {
                // We have a bad parameter here or not enough information from azure
                logger.warn("no network address found. ignoring [{}]...", vm.getName());
                continue;
            }

            networkAddress = InetAddresses.toUriString(ip);
            logger.debug("found networkAddress for [{}]: [{}]", vm.getName(), networkAddress);

            try {
                // we only limit to 1 port per address, makes no sense to ping 100 ports
                TransportAddress[] addresses = transportService.addressesFromString(networkAddress, 1);
                for (TransportAddress address : addresses) {
                    logger.trace("adding {}, transport_address {}", networkAddress, address);
                    cachedDiscoNodes.add(new DiscoveryNode("#cloud-" + vm.getName(), address, emptyMap(),
                        emptySet(), Version.CURRENT.minimumCompatibilityVersion()));
                }
            } catch (Exception e) {
                logger.warn("can not convert [{}] to transport address. skipping. [{}]", networkAddress, e.getMessage());
            }
        }

        logger.debug("{} node(s) added", cachedDiscoNodes.size());

        return cachedDiscoNodes;
    }
}
