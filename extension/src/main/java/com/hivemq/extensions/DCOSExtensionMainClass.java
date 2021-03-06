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

package com.hivemq.extensions;

import com.hivemq.extension.sdk.api.ExtensionMain;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.annotations.Nullable;
import com.hivemq.extension.sdk.api.parameter.ExtensionStartInput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStartOutput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStopInput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStopOutput;
import com.hivemq.extension.sdk.api.services.Services;
import com.hivemq.extensions.callbacks.DCOSClusterDiscovery;
import com.hivemq.extensions.configuration.ConfigurationReader;
import com.hivemq.extensions.configuration.DnsDiscoveryConfigExtended;
import com.hivemq.extensions.metrics.StatsDMetrics;
import com.hivemq.extensions.rest.RestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the main class of the DCOS extension, which is instantiated during the HiveMQ start up process.
 *
 * @author Simon Baier
 */
public class DCOSExtensionMainClass implements ExtensionMain {
    private static final Logger log = LoggerFactory.getLogger(DCOSExtensionMainClass.class);

    private @Nullable StatsDMetrics statsdReporter;
    private @Nullable RestService restService;

    @Override
    public void extensionStart(@NotNull ExtensionStartInput extensionStartInput, @NotNull ExtensionStartOutput extensionStartOutput) {
        try {

            final ConfigurationReader configurationReader = new ConfigurationReader(extensionStartInput.getExtensionInformation());
            if (configurationReader.get() == null) {
                extensionStartOutput.preventExtensionStartup("Unspecified error occurred while reading configuration");
                return;
            }
            Services.clusterService().addDiscoveryCallback(new DCOSClusterDiscovery(new DnsDiscoveryConfigExtended(configurationReader)));
            restService = new RestService();
            restService.start();
            final String metricsEnabled = System.getenv("HIVEMQ_METRICS_ENABLED");
            if("true".equals(metricsEnabled)) {
                statsdReporter = new StatsDMetrics(Services.metricRegistry());
                statsdReporter.startReporter();
            }
            log.info("DCOS Extension started");
        } catch (final Exception e) {
            extensionStartOutput.preventExtensionStartup("Unknown error while starting the extensions" + ((e.getMessage() != null) ? ": " + e.getMessage() : ""));
            return;
        }
    }

    @Override
    public void extensionStop(@NotNull ExtensionStopInput extensionStopInput, @NotNull ExtensionStopOutput extensionStopOutput) {
        if(statsdReporter != null) {
            statsdReporter.stopReporter();
        }
        if(restService != null) {
            restService.stop();
        }
    }
}

