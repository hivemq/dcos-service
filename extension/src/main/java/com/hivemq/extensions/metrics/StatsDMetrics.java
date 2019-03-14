package com.hivemq.extensions.metrics;

import com.codahale.metrics.MetricRegistry;
import com.hivemq.extensions.callbacks.DnsClusterDiscovery;
import com.readytalk.metrics.StatsDReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Report StatsD metrics to DCOS. Server information is provided by env vars in the container.
 *
 * @author Simon Baier
 */
public class StatsDMetrics {
    private static final Logger log = LoggerFactory.getLogger(DnsClusterDiscovery.class);

    private final MetricRegistry metricRegistry;
    private StatsDReporter reporter;

    public StatsDMetrics(final MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    public void startReporter() {
        final String host = System.getenv("STATSD_UDP_HOST");
        final String portString = System.getenv("STATSD_UDP_PORT");

        if (portString != null && !portString.isEmpty() && host != null && !host.isEmpty()) {
            final int port = Integer.parseInt(portString);
            final String intervalString = System.getenv("HIVEMQ_METRICS_INTERVAL");
            final int interval = Integer.parseInt(intervalString);
            reporter = StatsDReporter.forRegistry(metricRegistry)
                    .build(host, port);
            log.debug("Starting StatsD with interval {}, host '{}', port '{}'", interval, host, port);
            reporter.start(interval, TimeUnit.SECONDS);
        } else {
            log.error("Could not start StatsD metrics reporter. Server information is missing. Host: {}, Port: {}", host, portString);
        }
    }

    public void stopReporter() {
        if (reporter != null) {
            reporter.stop();
        }
    }
}
