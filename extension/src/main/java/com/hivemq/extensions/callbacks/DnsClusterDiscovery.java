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
import com.hivemq.extension.sdk.api.services.cluster.ClusterDiscoveryCallback;
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterDiscoveryInput;
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterDiscoveryOutput;
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterNodeAddress;
import com.hivemq.extensions.configuration.DnsDiscoveryConfigExtended;
import io.netty.buffer.ByteBuf;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.Future;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;


/**
 * Cluster discovery using DNS resolution of round-robin A records.
 * Uses non-blocking netty API for DNS resolution, reads discovery parameters as environment variables.
 *
 * @author Daniel Krüger
 * @author Simon Baier
 */
public class DnsClusterDiscovery implements ClusterDiscoveryCallback {

    @NotNull
    private static final Logger log = LoggerFactory.getLogger(DnsClusterDiscovery.class);
    @NotNull
    private final DnsDiscoveryConfigExtended discoveryConfiguration;
    @NotNull
    private final NioEventLoopGroup eventLoopGroup;
    @NotNull
    private final InetAddressValidator addressValidator;
    @Nullable
    private ClusterNodeAddress ownAddress;


    public DnsClusterDiscovery(final @NotNull DnsDiscoveryConfigExtended discoveryConfiguration) {
        this.eventLoopGroup = new NioEventLoopGroup();
        this.addressValidator = InetAddressValidator.getInstance();
        this.discoveryConfiguration = discoveryConfiguration;
    }

    @Override
    public void init(final @NotNull ClusterDiscoveryInput clusterDiscoveryInput, final @NotNull ClusterDiscoveryOutput clusterDiscoveryOutput) {
        ownAddress = clusterDiscoveryInput.getOwnAddress();
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
        try {
            final List<ClusterNodeAddress> clusterNodeAddresses = loadOtherNodes();
            if (clusterNodeAddresses != null) {
                clusterDiscoveryOutput.provideCurrentNodes(clusterNodeAddresses);
            }
        } catch (TimeoutException | InterruptedException e) {
            log.error("Timeout while getting other node addresses");
        }
    }

    private List<ClusterNodeAddress> loadOtherNodes() throws TimeoutException, InterruptedException {

        log.debug("Discovering other nodes");

        // Get node count. exposed as env, rendered from config
        final String nodeCount = System.getenv("HIVEMQ_TOTAL_NODES");
        int index = -1;
        if (nodeCount != null && !nodeCount.isEmpty()) {
            index = Integer.parseInt(nodeCount);
        }
        final String frameworkName = System.getenv("FRAMEWORK_NAME");

        if (index < 1) {
            return null;
        }

        final int discoveryTimeout = discoveryConfiguration.resolutionTimeout();

        // initialize netty DNS resolver
        try (DnsNameResolver resolver = new DnsNameResolverBuilder(eventLoopGroup.next()).channelType(NioDatagramChannel.class).build()) {
            List<ClusterNodeAddress> addresses = new ArrayList<>();
            for (int i = 0; i < index; ++i) {
                // construct the dns name as it will be published by Mesos-DNS
                final String discoveryAddress = "_cluster._" + frameworkName + "-" + i + "._tcp." + frameworkName + ".mesos.";
                addresses.addAll(resolveAddress(discoveryAddress, discoveryTimeout, resolver));
            }
            return addresses;
        } catch (ExecutionException ex) {
            log.error("Failed to resolve DNS record for address '{}'.", frameworkName, ex);
        }
        return null;
    }

    private List<ClusterNodeAddress> resolveAddress(String discoveryAddress, int discoveryTimeout, DnsNameResolver resolver) throws InterruptedException, ExecutionException, TimeoutException {
        final Future<List<DnsRecord>> records = resolver.resolveAll(new DefaultDnsQuestion(discoveryAddress, DnsRecordType.SRV));
        final List<DnsRecord> recordList = records.get(discoveryTimeout, TimeUnit.SECONDS);
        // FIXME refactor this, return futures instead and wait/collect on all of them in parallel
        return recordList.stream()
                .map(this::decodeServiceRecord)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
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
