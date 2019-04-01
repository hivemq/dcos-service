package com.hivemq;

import com.beust.jcommander.JCommander;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import com.hivemq.command.Shutdown;
import io.netty.buffer.ByteBuf;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.*;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static final String SHUTDOWN_COMMAND = "shutdown";

    public static void main(final String[] args) {
        final Shutdown shutdown = new Shutdown();
        final JCommander jCommander = JCommander.newBuilder()
                .addCommand(SHUTDOWN_COMMAND, shutdown)
                .build();
        jCommander.parse(args);

        final String parsedCommand = jCommander.getParsedCommand();

        if (SHUTDOWN_COMMAND.equals(parsedCommand)) {
            handleShutdown(shutdown);
        }
    }

    private static void handleShutdown(Shutdown shutdown) {
        final NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();
        final String hostUri = shutdown.getHostUri();
        try (DnsNameResolver resolver = new DnsNameResolverBuilder(eventLoopGroup.next()).channelType(NioDatagramChannel.class).build()) {

            final List<DnsRecord> addresses = resolver.resolveAll(new DefaultDnsQuestion(hostUri, DnsRecordType.SRV)).get(10, TimeUnit.SECONDS);
            if (addresses.size() == 1) {
                final String address = decodeServiceRecord(addresses.get(0));
                final String url = String.format("http://%s/health/shutdown", address);
                final HttpResponse<String> stringHttpResponse = Unirest.    get(url).asString();
                log.info("Got HTTP response with code {}, URL queried: {}", stringHttpResponse.getStatus(), url);
                log.debug("Response: {}", stringHttpResponse);
                if(stringHttpResponse.getStatus() > 199 && stringHttpResponse.getStatus() < 400) {
                     log.info("Shutdown successful");
                }
            } else {
                log.error("Unexpected address count for URI {}, records: {}", hostUri, addresses);
            }
        } catch (InterruptedException | ExecutionException | TimeoutException ex) {
            log.error("Failed to resolve SRV record for URI {}, exception: {}", hostUri, ex);
        } catch (UnirestException ex) {
            log.error("Failed to query API: {}", ex);
        } finally {
            eventLoopGroup.shutdownGracefully();
        }
    }

    private static String decodeServiceRecord(final DnsRecord dnsRecord) {
        if (dnsRecord instanceof DnsRawRecord) {
            DefaultDnsRawRecord rec = (DefaultDnsRawRecord) dnsRecord;
            final ByteBuf buf = rec.content();
            // Skip weight and priority, we don't use them for discovery
            buf.skipBytes(4);
            final int port = buf.readUnsignedShort();
            final String target = DefaultDnsRecordDecoder.decodeName(buf);
            rec.release();
            return target + ":" + port;
        }
        return null;
    }
}
