package com.mesosphere.sdk.operator.scheduler.api;

import com.mesosphere.sdk.http.EndpointUtils;
import com.mesosphere.sdk.offer.Constants;
import com.mesosphere.sdk.offer.TaskException;
import com.mesosphere.sdk.offer.taskdata.TaskLabelReader;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.state.StateStoreUtils;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.servlet.compat.rewrite.RewriteHandler;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class HiveMQProxyHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(HiveMQProxyHandler.class);

    private final ProxyHandler dashboardProxyHandler;
    private final ProxyHandler apiProxyHandler;
    private final LoadBalancingProxyClient dashboardProxyClient;
    private final StateStore stateStore;
    private final RewriteHandler rewriteHandler;

    private URI previousHost;

    public HiveMQProxyHandler(ProxyHandler dashboardProxyHandler, ProxyHandler apiProxyHandler,
                              LoadBalancingProxyClient dashboardProxyClient, StateStore stateStore, RewriteHandler rewriteHandler) {
        this.dashboardProxyHandler = dashboardProxyHandler;
        this.apiProxyHandler = apiProxyHandler;
        this.dashboardProxyClient = dashboardProxyClient;
        this.stateStore = stateStore;
        this.rewriteHandler = rewriteHandler;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        final String relativePath = exchange.getRelativePath();
        log.info("Relative path: {}", relativePath);
        if (relativePath.startsWith("/hivemq") || relativePath.startsWith("/VAADIN")) {
            if (previousHost != null) {
                dashboardProxyClient.removeHost(previousHost);
            }
            URI uri = getDashboardURI();

            if (uri != null) {
                dashboardProxyClient.addHost(uri);
            }

            rewriteHandler.handleRequest(exchange);
        } else {
            apiProxyHandler.handleRequest(exchange);
        }
    }

    private URI getDashboardURI() throws TaskException {
        final Collection<String> strings = stateStore.fetchTaskNames();
        log.info("Tasks found in state store: {}", strings);
        final Optional<Protos.TaskInfo> node = stateStore.fetchTask("hivemq-0-node");
        if (node.isPresent()) {
            final Protos.TaskInfo taskInfo = node.get();
            final Protos.DiscoveryInfo discoveryInfo = taskInfo.getDiscovery();

            // Autoip hostname:
            String autoIpTaskName = discoveryInfo.hasName() ?
                    discoveryInfo.getName() :
                    taskInfo.getName();
            // Hostname of agent at offer time:
            String nativeHost = new TaskLabelReader(taskInfo).getHostname();
            // get IP address(es) from container status on the latest TaskStatus, if the latest TaskStatus has an IP
            // otherwise use the lastest TaskStatus' IP stored in the stateStore
            List<String> ipAddresses = reconcileIpAddresses(stateStore, taskInfo.getName());
            for (Protos.Port port : discoveryInfo.getPorts().getPortsList()) {
                final String portName = port.getName();
                log.info("Iterating over ports, name: {}, port: {}", portName, port);
                if ("control-center".equals(portName)) {
                    if (port.getVisibility() != Constants.DISPLAYED_PORT_VISIBILITY) {
                        log.debug(
                                "Port {} in task {} has {} visibility. {} is needed to be listed in endpoints.",
                                port.getName(), taskInfo.getName(), port.getVisibility(),
                                Constants.DISPLAYED_PORT_VISIBILITY);
                        continue;
                    }
                    final String hostIpString;
                    switch (ipAddresses.size()) {
                        case 0:
                            hostIpString = nativeHost;
                            break;
                        case 1:
                            hostIpString = ipAddresses.get(0);
                            break;
                        default:
                            hostIpString = ipAddresses.toString();
                            break;
                    }
                    final URI uri = URI.create("http://" + EndpointUtils.toEndpoint(hostIpString, port.getNumber()));
                    log.info("Returning URI {}", uri);
                    return uri;

                }
            }
        }
        return null;
    }

    private static List<String> reconcileIpAddresses(StateStore stateStore, String taskName) {
        // get the IP addresses from the latest TaskStatus (currentTaskStatus), if that TaskStatus doesn't have an
        // IP address (it's a TASK_KILLED, LOST, etc.) than use the last IP address recorded in the stateStore
        // (this is better than nothing).
        Protos.TaskStatus currentTaskStatus = stateStore.fetchStatus(taskName).orElse(null);
        Protos.TaskStatus savedTaskStatus = StateStoreUtils.getTaskStatusFromProperty(stateStore, taskName)
                .orElse(null);
        List<String> currentIpAddresses = getIpAddresses(currentTaskStatus);
        return currentIpAddresses.isEmpty() ?
                getIpAddresses(savedTaskStatus) : currentIpAddresses;
    }

    private static List<String> getIpAddresses(Protos.TaskStatus taskStatus) {
        if (taskStatus != null && taskStatus.hasContainerStatus() &&
                taskStatus.getContainerStatus().getNetworkInfosCount() > 0) {
            return taskStatus
                    .getContainerStatus()
                    .getNetworkInfosList()
                    .stream()
                    .flatMap(networkInfo -> networkInfo.getIpAddressesList().stream())
                    .map(Protos.NetworkInfo.IPAddress::getIpAddress)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
