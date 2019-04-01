
package com.mesosphere.sdk.operator.scheduler;

import com.mesosphere.sdk.config.ConfigurationFactory;
import com.mesosphere.sdk.curator.CuratorUtils;
import com.mesosphere.sdk.framework.EnvStore;
import com.mesosphere.sdk.http.EndpointUtils;
import com.mesosphere.sdk.http.types.EndpointProducer;
import com.mesosphere.sdk.operator.scheduler.scheduler.CustomizedSchedulerBuilder;
import com.mesosphere.sdk.operator.scheduler.scheduler.CustomizedSchedulerRunner;
import com.mesosphere.sdk.scheduler.DefaultScheduler;
import com.mesosphere.sdk.scheduler.SchedulerBuilder;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.scheduler.SchedulerRunner;
import com.mesosphere.sdk.specification.DefaultServiceSpec;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.specification.yaml.RawServiceSpec;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.storage.Persister;
import com.mesosphere.sdk.storage.PersisterUtils;
import io.undertow.Undertow;
import io.undertow.client.UndertowClient;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ProxyPeerAddressHandler;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.server.handlers.proxy.ProxyHandlerBuilder;
import io.undertow.server.handlers.proxy.SimpleProxyClientProvider;
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

        final Map<String, String> env = System.getenv();

        SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(EnvStore.fromMap(env));
        final DefaultServiceSpec.Generator generator = DefaultServiceSpec.newGenerator(rawServiceSpec, schedulerConfig, env, yamlSpecFile.getParentFile());
        final DefaultServiceSpec defaultServiceSpec = generator.build();
        final CustomizedSchedulerBuilder schedulerBuilder = new CustomizedSchedulerBuilder(defaultServiceSpec, schedulerConfig);
        final Persister persister = schedulerBuilder.getPersister();
        //final String rollingUpgradePath = "hivemq" + PersisterUtils.PATH_DELIM + "rolling_upgrade";
        //final byte[] bytes = persister.get(rollingUpgradePath);

        ConfigStore<ServiceSpec> configStore = new ConfigStore<>(
                DefaultServiceSpec.getConfigurationFactory(defaultServiceSpec, new ArrayList<>()),
                persister,
                Optional.empty());
        final UpgradeCustomizer upgradeCustomizer = new UpgradeCustomizer(persister, configStore, defaultServiceSpec);
        schedulerBuilder.setPlansFrom(rawServiceSpec);
        schedulerBuilder.setPlanCustomizer(upgradeCustomizer);
        return schedulerBuilder;
    }
}