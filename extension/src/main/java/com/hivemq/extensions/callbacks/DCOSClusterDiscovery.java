/*
 * Copyright 2018 dc-square GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hivemq.extensions.callbacks;

import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.annotations.Nullable;
import com.hivemq.extension.sdk.api.services.ManagedExtensionExecutorService;
import com.hivemq.extension.sdk.api.services.Services;
import com.hivemq.extension.sdk.api.services.cluster.ClusterDiscoveryCallback;
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterDiscoveryInput;
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterDiscoveryOutput;
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterNodeAddress;
import com.hivemq.extensions.configuration.DnsDiscoveryConfigExtended;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import jersey.repackaged.com.google.common.collect.Lists;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;


/**
 * Cluster Discovery for DCOS. Queries the scheduler for the node count and then resolves DNS SRV records derived from environment variables.
 *
 * @author Simon Baier
 */
public class DCOSClusterDiscovery implements ClusterDiscoveryCallback {

    @NotNull
    private static final Logger log = LoggerFactory.getLogger(DCOSClusterDiscovery.class);

    /* DCOS default service TLD, used for DNS discovery */
    public static final String DEFAULT_SERVICE_TLD = "autoip.dcos.thisdcos.directory";
    @NotNull
    private final DnsDiscoveryConfigExtended discoveryConfiguration;
    @NotNull
    private final NioEventLoopGroup eventLoopGroup;
    @NotNull
    private final InetAddressValidator addressValidator;
    @Nullable
    private ClusterNodeAddress ownAddress;

    private String queryHost;
    private String frameworkName;
    private ManagedExtensionExecutorService managedExtensionExecutorService;
    private String serviceTld;


    public DCOSClusterDiscovery(final @NotNull DnsDiscoveryConfigExtended discoveryConfiguration) {
        this.eventLoopGroup = new NioEventLoopGroup();
        this.addressValidator = InetAddressValidator.getInstance();
        this.discoveryConfiguration = discoveryConfiguration;
    }

    @Override
    public void init(final @NotNull ClusterDiscoveryInput clusterDiscoveryInput, final @NotNull ClusterDiscoveryOutput clusterDiscoveryOutput) {
        managedExtensionExecutorService = Services.extensionExecutorService();
        ownAddress = clusterDiscoveryInput.getOwnAddress();
        final String serviceTldString = System.getenv("SERVICE_TLD");
        if (serviceTldString != null) {
            serviceTld = serviceTldString;
        } else {
            serviceTld = DEFAULT_SERVICE_TLD;
        }
        // Get node count from scheduler API
        queryHost = System.getenv("SCHEDULER_API_HOSTNAME") + ":" + System.getenv("SCHEDULER_API_PORT");
        frameworkName = System.getenv("FRAMEWORK_NAME");
        loadClusterNodeAddresses(clusterDiscoveryOutput);
    }

    @Override
    public void reload(final @NotNull ClusterDiscoveryInput clusterDiscoveryInput, final @NotNull ClusterDiscoveryOutput clusterDiscoveryOutput) {
        loadClusterNodeAddresses(clusterDiscoveryOutput);
    }

    @Override
    public void destroy(final @NotNull ClusterDiscoveryInput clusterDiscoveryInput) {
        eventLoopGroup.shutdownGracefully();
    }

    private void loadClusterNodeAddresses(final @NotNull ClusterDiscoveryOutput clusterDiscoveryOutput) {
       /* managedExtensionExecutorService.submit(new Callable<List<ClusterNodeAddress>>() {
            @Override
            public List<ClusterNodeAddress> call() throws Exception {
                final List<ClusterNodeAddress> clusterNodeAddresses = loadOtherNodes();
                if (clusterNodeAddresses != null) {
                    log.trace("Found addresses: {}", clusterNodeAddresses);
                    return clusterNodeAddresses;
                }
                return null;
            }
        })
                .whenComplete(new BiConsumer<List<ClusterNodeAddress>, Throwable>() {
                    @Override
                    public void accept(List<ClusterNodeAddress> clusterNodeAddresses, Throwable throwable) {
                        if (throwable == null && clusterNodeAddresses != null) {
                            clusterDiscoveryOutput.provideCurrentNodes(clusterNodeAddresses);
                        } else {
                            log.error("Error occurred when discovering nodes:", throwable);
                        }
                    }
                })*/

        try {
            final List<ClusterNodeAddress> nodeAddresses = loadOtherNodes();
            log.trace("Found addresses: {}", nodeAddresses);
            clusterDiscoveryOutput.provideCurrentNodes(nodeAddresses);
        } catch (TimeoutException | InterruptedException e) {
            log.error("Error occurred when discovering nodes:", e);
        }
    }

    private List<ClusterNodeAddress> loadOtherNodes() throws TimeoutException, InterruptedException {
        log.debug("Discovering other nodes");

        String nodeCount = null;
        try {
            nodeCount = Unirest.get(String.format("http://%s/discovery/nodeCount", queryHost)).asString().getBody();
        } catch (UnirestException e) {
            log.debug("Node count request failed. Is the scheduler restarting?");
            log.trace("Query exception for node count:", e);
        }

        // Fallback: Get node count. Exposed as env, rendered from config. Send requests to lower pods.
        if (nodeCount == null) {
            nodeCount = System.getenv("POD_INSTANCE_INDEX");
        }

        int index = -1;
        if (nodeCount != null && !nodeCount.isEmpty()) {
            index = Integer.parseInt(nodeCount);
        }

        if (index < 0) {
            log.warn("No instance index given, not discovering other nodes");
            return null;
        }

        final int discoveryTimeout = discoveryConfiguration.resolutionTimeout();

        // initialize netty DNS resolver
        try (DnsNameResolver resolver = new DnsNameResolverBuilder(eventLoopGroup.next()).channelType(NioDatagramChannel.class).build()) {
            List<ClusterNodeAddress> addresses = new ArrayList<>();
            for (int i = 0; i < index; ++i) {
                // construct the dns name as it will be published by Mesos-DNS
                final String discoveryAddress = "_cluster._" + frameworkName + "-" + i + "._tcp." + frameworkName + ".mesos.";
                addresses.addAll(resolveAddress(discoveryAddress, discoveryTimeout, resolver, i, frameworkName));
            }
            return addresses;
        }
    }

    private List<ClusterNodeAddress> resolveAddress(final String discoveryAddress, final int discoveryTimeout,
                                                    final DnsNameResolver resolver, final int index,
                                                    final String frameworkName) throws InterruptedException, TimeoutException {
        final Future<List<DnsRecord>> records = resolver.resolveAll(new DefaultDnsQuestion(discoveryAddress, DnsRecordType.SRV));
        try {
            final List<DnsRecord> recordList = records.get(discoveryTimeout, TimeUnit.SECONDS);
            // TODO refactor this, return futures instead and wait/collect on all of them in parallel
            return recordList.stream()
                    .map(this::decodeServiceRecord)
                    .filter(Objects::nonNull)
                    .map(cna -> new ClusterNodeAddress(frameworkName + "-" + index + "." + frameworkName + "." + serviceTld, cna.getPort()))
                    .collect(Collectors.toList());
        } catch (ExecutionException ex) {
            log.trace("Ignoring single unresolved record");
        }
        return Lists.newArrayList();
    }

    private @Nullable ClusterNodeAddress decodeServiceRecord(final DnsRecord dnsRecord) {
        if (dnsRecord instanceof DnsRawRecord) {
            DefaultDnsRawRecord rec = (DefaultDnsRawRecord) dnsRecord;
            final ByteBuf buf = rec.content();
            // Skip weight and priority, we don't use them for discovery
            buf.skipBytes(4);
            final int port = buf.readUnsignedShort();
            final String target = DefaultDnsRecordDecoder.decodeName(buf);
            rec.release();
            return new ClusterNodeAddress(target, port);
        }
        return null;
    }

}
