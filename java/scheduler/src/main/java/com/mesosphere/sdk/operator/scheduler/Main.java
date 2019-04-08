
package com.mesosphere.sdk.operator.scheduler;

import com.google.common.collect.Lists;
import com.mesosphere.sdk.config.validate.ConfigValidationError;
import com.mesosphere.sdk.config.validate.ConfigValidator;
import com.mesosphere.sdk.framework.EnvStore;
import com.mesosphere.sdk.operator.scheduler.api.HiveMQProxyHandler;
import com.mesosphere.sdk.operator.scheduler.api.NodeDiscovery;
import com.mesosphere.sdk.operator.scheduler.scheduler.CustomizedDefaultScheduler;
import com.mesosphere.sdk.operator.scheduler.scheduler.CustomizedSchedulerBuilder;
import com.mesosphere.sdk.operator.scheduler.scheduler.CustomizedSchedulerRunner;
import com.mesosphere.sdk.scheduler.SchedulerConfig;
import com.mesosphere.sdk.specification.*;
import com.mesosphere.sdk.specification.yaml.RawServiceSpec;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.storage.Persister;
import io.undertow.Undertow;
import io.undertow.server.handlers.builder.RewriteHandlerBuilder;
import io.undertow.server.handlers.proxy.LoadBalancingProxyClient;
import io.undertow.server.handlers.proxy.ProxyHandler;
import io.undertow.servlet.compat.rewrite.RewriteConfig;
import io.undertow.servlet.compat.rewrite.RewriteConfigFactory;
import io.undertow.servlet.compat.rewrite.RewriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Main entry point for the Scheduler.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    public static final String VIRTUAL_NETWORK_ERROR = "Must not change virtual network configuration on already deployed cluster";

    private static LoadBalancingProxyClient dashboardProxyClient;


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

        /*
        FIXME: need to correctly configure the reverse proxy first. currently does not rewrite as expected.

        final Map<String, String> env = new HashMap<>(System.getenv());

        final int apiServerPort = SchedulerConfig.fromEnv().getApiServerPort();
        // Wrap around the API server here:
        // get the API port from the original scheduler config, bind to it and proxy to another port where the actual scheduler API is running.
        dashboardProxyClient = new LoadBalancingProxyClient();
        final ProxyHandler dashboardProxyHandler = ProxyHandler.builder().setProxyClient(dashboardProxyClient).build();
        byte[] data = "RewriteRule / /hivemq".getBytes(StandardCharsets.UTF_8);
        RewriteConfig config = RewriteConfigFactory.build(new ByteArrayInputStream(data));
        final RewriteHandler rewriteHandler = new RewriteHandler(config, dashboardProxyHandler);
        final LoadBalancingProxyClient apiProxyClient = new LoadBalancingProxyClient();
        final String actualPortString = env.get("PORT_ACTUAL");
        env.put("PORT_API", actualPortString);
        apiProxyClient.addHost(URI.create("http://localhost:" + actualPortString));

        final ProxyHandler apiProxyHandler = ProxyHandler.builder().setProxyClient(apiProxyClient).build();*/

        final Map<String, String> env = System.getenv();
        SchedulerConfig schedulerConfig = SchedulerConfig.fromEnvStore(EnvStore.fromMap(env));
        final DefaultServiceSpec.Generator generator = DefaultServiceSpec.newGenerator(rawServiceSpec, schedulerConfig, env, yamlSpecFile.getParentFile());
        final DefaultServiceSpec defaultServiceSpec = generator.build();
        final CustomizedSchedulerBuilder schedulerBuilder = CustomizedDefaultScheduler.newBuilder(defaultServiceSpec, schedulerConfig);
        final Persister persister = schedulerBuilder.getPersister();
        final UpgradeCustomizer upgradeCustomizer = new UpgradeCustomizer(defaultServiceSpec);
        schedulerBuilder.setPlanCustomizer(upgradeCustomizer);
        schedulerBuilder.setPlansFrom(rawServiceSpec);

        setConfigValidators(schedulerBuilder);
        final Integer podCount = defaultServiceSpec.getPods().get(0).getCount();
        //startProxy(apiServerPort, dashboardProxyHandler, rewriteHandler, apiProxyHandler, persister);
        final NodeDiscovery nodeDiscovery = new NodeDiscovery(podCount);

        schedulerBuilder.setCustomResources(Lists.newArrayList(nodeDiscovery));
        return schedulerBuilder;
    }

    private static void startProxy(int apiServerPort, ProxyHandler dashboardProxyHandler, RewriteHandler rewriteHandler, ProxyHandler apiProxyHandler, Persister persister) {
        final StateStore stateStore = new StateStore(persister);
        final Undertow undertow = Undertow.builder()
                .addHttpListener(apiServerPort, "0.0.0.0")
                .setHandler(new HiveMQProxyHandler(dashboardProxyHandler, apiProxyHandler, dashboardProxyClient, stateStore, rewriteHandler))
                .build();
        undertow.start();
    }

    private static void setConfigValidators(CustomizedSchedulerBuilder schedulerBuilder) {
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