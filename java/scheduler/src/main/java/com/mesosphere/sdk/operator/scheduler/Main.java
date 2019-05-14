
package com.mesosphere.sdk.operator.scheduler;

import com.google.common.collect.Lists;
import com.mesosphere.sdk.config.validate.ConfigValidationError;
import com.mesosphere.sdk.config.validate.ConfigValidator;
import com.mesosphere.sdk.operator.scheduler.api.NodeDiscovery;
import com.mesosphere.sdk.scheduler.DefaultScheduler;
import com.mesosphere.sdk.scheduler.SchedulerBuilder;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.scheduler.SchedulerRunner;
import com.mesosphere.sdk.specification.*;
import com.mesosphere.sdk.specification.yaml.RawServiceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Main entry point for the Scheduler.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    public static final String VIRTUAL_NETWORK_ERROR = "Must not change virtual network configuration on already deployed cluster";


    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one file argument, got: " + Arrays.toString(args));
        }

        // Read config from provided file, and assume any config templates are in the same directory as the file:
        File yamlSpecFile = new File(args[0]);
        final SchedulerBuilder schedulerBuilder = createSchedulerBuilderAlt(yamlSpecFile);

        SchedulerRunner
                .fromSchedulerBuilder(schedulerBuilder)
                .run();
    }



    private static SchedulerBuilder createSchedulerBuilderAlt(File yamlSpecFile) throws Exception {
        RawServiceSpec rawServiceSpec = RawServiceSpec.newBuilder(yamlSpecFile).build();
        File configDir = yamlSpecFile.getParentFile();

        SchedulerConfig schedulerConfig = SchedulerConfig.fromEnv();

        final Map<String, String> env = System.getenv();
        final DefaultServiceSpec.Generator generator = DefaultServiceSpec.newGenerator(rawServiceSpec, schedulerConfig, env, configDir);
        final DefaultServiceSpec defaultServiceSpec = generator.build();
        final SchedulerBuilder schedulerBuilder = DefaultScheduler.newBuilder(defaultServiceSpec, schedulerConfig);
        setConfigValidators(schedulerBuilder);
        schedulerBuilder.setPlansFrom(rawServiceSpec);

        final Integer podCount = defaultServiceSpec.getPods().get(0).getCount();
        final NodeDiscovery nodeDiscovery = new NodeDiscovery(podCount);
        schedulerBuilder.setCustomResources(Lists.newArrayList(nodeDiscovery));

        return schedulerBuilder;
    }

    private static void setConfigValidators(SchedulerBuilder schedulerBuilder) {
        final ConfigValidator<ServiceSpec> tlsConfigValidator = (oldConfig, newConfig) -> {
            List<ConfigValidationError> errors = Lists.newArrayList();
            if (oldConfig.isPresent()) {
                final ServiceSpec oldSpec = oldConfig.get();

                final String tlsEnabledOld = getEnvFromSpec(oldSpec, "HIVEMQ_CLUSTER_TLS_ENABLED");
                final String tlsEnabledNew = getEnvFromSpec(newConfig, "HIVEMQ_CLUSTER_TLS_ENABLED");
                if ("false".equals(tlsEnabledOld) && "true".equals(tlsEnabledNew)) {
                    errors.add(ConfigValidationError.transitionError("HIVEMQ_CLUSTER_TLS_ENABLED", "false", "true", "Must not enable cluster TLS on already deployed cluster", true));
                } else if ("true".equals(tlsEnabledOld) && "false".equals(tlsEnabledNew)) {
                    errors.add(ConfigValidationError.transitionError("HIVEMQ_CLUSTER_TLS_ENABLED", "true", "false", "Must not disable cluster TLS on already deployed cluster", true));
                }
            }
            return errors;
        };

        final ConfigValidator<ServiceSpec> virtualNetworkValidator = (oldConfig, newConfig) -> {
            List<ConfigValidationError> errors = Lists.newArrayList();
            if (oldConfig.isPresent()) {
                final ServiceSpec oldSpec = oldConfig.get();
                final Collection<NetworkSpec> oldNetworks = oldSpec.getPods().get(0).getNetworks();
                final int oldNetworkSize = oldNetworks.size();
                final Collection<NetworkSpec> newNetworks = newConfig.getPods().get(0).getNetworks();
                final int newNetworkSize = newNetworks.size();
                if(oldNetworkSize != newNetworkSize) {
                    errors.add(ConfigValidationError.transitionError("VIRTUAL_NETWORK_NAME", String.valueOf(oldNetworkSize), String.valueOf(newNetworkSize), VIRTUAL_NETWORK_ERROR, true));
                }
                if(!oldNetworks.equals(newNetworks)) {
                    errors.add(ConfigValidationError.transitionError("VIRTUAL_NETWORK_NAME", oldNetworks.toString(), newNetworks.toString(), VIRTUAL_NETWORK_ERROR, true));
                }
            }
            return errors;
        };
        schedulerBuilder.setCustomConfigValidators(Lists.newArrayList(tlsConfigValidator, virtualNetworkValidator));
    }

    private static String getEnvFromSpec(final ServiceSpec serviceSpec, final String envKey) {
        final PodSpec hivemqPod = serviceSpec.getPods().get(0);
        final List<TaskSpec> tasks = hivemqPod.getTasks();
        final TaskSpec taskSpecNode = tasks.get(0);
        final Optional<CommandSpec> commandNode = taskSpecNode.getCommand();
        return commandNode.map(commandSpec -> commandSpec.getEnvironment().get(envKey)).orElse(null);
    }
}