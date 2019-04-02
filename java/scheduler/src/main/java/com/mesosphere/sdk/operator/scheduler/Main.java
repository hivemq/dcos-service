
package com.mesosphere.sdk.operator.scheduler;

import com.mesosphere.sdk.framework.EnvStore;
import com.mesosphere.sdk.operator.scheduler.scheduler.CustomizedDefaultScheduler;
import com.mesosphere.sdk.operator.scheduler.scheduler.CustomizedSchedulerBuilder;
import com.mesosphere.sdk.operator.scheduler.scheduler.CustomizedSchedulerRunner;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.specification.DefaultServiceSpec;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.specification.yaml.RawServiceSpec;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.storage.Persister;
import io.undertow.Undertow;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.util.*;

/**
 * Main entry point for the Scheduler.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);


    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one file argument, got: " + Arrays.toString(args));
        }

        // Read config from provided file, and assume any config templates are in the same directory as the file:
        File yamlSpecFile = new File(args[0]);
        final CustomizedSchedulerBuilder schedulerBuilder = createSchedulerBuilder(yamlSpecFile);

        CustomizedSchedulerRunner
                .fromSchedulerBuilder(schedulerBuilder)
                .run();
    }

    private static CustomizedSchedulerBuilder createSchedulerBuilder(File yamlSpecFile) throws Exception {
        RawServiceSpec rawServiceSpec = RawServiceSpec.newBuilder(yamlSpecFile).build();
        File configDir = yamlSpecFile.getParentFile();

        final int apiServerPort = SchedulerConfig.fromEnv().getApiServerPort();
        // Wrap around the API server here:
        // get the API port from the original scheduler config, bind to it and proxy to another port where the actual scheduler API is running.
        final LoadBalancingProxyClient dashboardProxyClient = new LoadBalancingProxyClient();
        final ProxyHandler dashboardProxyHandler = ProxyHandler.builder().setProxyClient(dashboardProxyClient).build();
        final LoadBalancingProxyClient apiProxyClient = new LoadBalancingProxyClient();
        // FIXME: figure out the pod instances control center ports and add them to the dashboard LB
        final Map<String, String> env = new HashMap<>(System.getenv());
        final String actualPortString = env.get("PORT_ACTUAL");
        env.put("PORT_API", actualPortString);
        apiProxyClient.addHost(URI.create("http://localhost:" + actualPortString));

        final ProxyHandler apiProxyHandler = ProxyHandler.builder().setProxyClient(apiProxyClient).build();
        final Undertow undertow = Undertow.builder()
                .addHttpListener(apiServerPort, "0.0.0.0")
                .setHandler(exchange -> {
                    final String relativePath = exchange.getRelativePath();
                    log.info("Relative path: {}", relativePath);
                    if (relativePath.contains("dashboard")) {
                        dashboardProxyHandler.handleRequest(exchange);
                    } else {
                        apiProxyHandler.handleRequest(exchange);
                    }
                })
                .build();
        undertow.start();

        SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(EnvStore.fromMap(env));
        final DefaultServiceSpec.Generator generator = DefaultServiceSpec.newGenerator(rawServiceSpec, schedulerConfig, env, yamlSpecFile.getParentFile());
        final DefaultServiceSpec defaultServiceSpec = generator.build();
        final CustomizedSchedulerBuilder schedulerBuilder = CustomizedDefaultScheduler.newBuilder(defaultServiceSpec, schedulerConfig);
        final Persister persister = schedulerBuilder.getPersister();
        //final String rollingUpgradePath = "hivemq" + PersisterUtils.PATH_DELIM + "rolling_upgrade";
        //final byte[] bytes = persister.get(rollingUpgradePath);
        final UpgradeCustomizer upgradeCustomizer = new UpgradeCustomizer(persister, defaultServiceSpec);
        schedulerBuilder.setPlanCustomizer(upgradeCustomizer);
        schedulerBuilder.setPlansFrom(rawServiceSpec);
        return schedulerBuilder;
    }
}