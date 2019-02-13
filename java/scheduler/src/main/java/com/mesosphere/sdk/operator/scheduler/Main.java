
package com.mesosphere.sdk.operator.scheduler;

import com.mesosphere.sdk.config.ConfigurationFactory;
import com.mesosphere.sdk.curator.CuratorUtils;
import com.mesosphere.sdk.http.EndpointUtils;
import com.mesosphere.sdk.http.types.EndpointProducer;
import com.mesosphere.sdk.scheduler.DefaultScheduler;
import com.mesosphere.sdk.scheduler.SchedulerBuilder;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.scheduler.SchedulerRunner;
import com.mesosphere.sdk.specification.DefaultServiceSpec;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.specification.yaml.RawServiceSpec;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.storage.Persister;

import java.io.File;
import java.util.*;

/**
 * Main entry point for the Scheduler.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one file argument, got: " + Arrays.toString(args));
        }

        // Read config from provided file, and assume any config templates are in the same directory as the file:
        File yamlSpecFile = new File(args[0]);
        final SchedulerBuilder schedulerBuilder = createSchedulerBuilder(yamlSpecFile);

        SchedulerRunner
                .fromSchedulerBuilder(schedulerBuilder)
                .run();
    }

    private static SchedulerBuilder createSchedulerBuilder(File yamlSpecFile) throws Exception {
        RawServiceSpec rawServiceSpec = RawServiceSpec.newBuilder(yamlSpecFile).build();
        File configDir = yamlSpecFile.getParentFile();

        SchedulerConfig schedulerConfig = SchedulerConfig.fromEnv();
        final Map<String, String> env = System.getenv();
        final DefaultServiceSpec.Generator generator = DefaultServiceSpec.newGenerator(rawServiceSpec, schedulerConfig, env, yamlSpecFile.getParentFile());
        final DefaultServiceSpec defaultServiceSpec = generator.build();
        final SchedulerBuilder schedulerBuilder = DefaultScheduler.newBuilder(defaultServiceSpec, schedulerConfig);
        final Persister persister = schedulerBuilder.getPersister();

        ConfigStore<ServiceSpec> configStore = new ConfigStore<>(
                DefaultServiceSpec.getConfigurationFactory(defaultServiceSpec, new ArrayList<>()),
                persister,
                Optional.empty());
        final UpgradeCustomizer upgradeCustomizer = new UpgradeCustomizer(persister, configStore);
        schedulerBuilder.setPlansFrom(rawServiceSpec);
        schedulerBuilder.setPlanCustomizer(upgradeCustomizer);
        return schedulerBuilder;
    }
}
