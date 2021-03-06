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
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterDiscoveryInput;
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterDiscoveryOutput;
import com.hivemq.extension.sdk.api.services.cluster.parameter.ClusterNodeAddress;
import com.hivemq.extensions.configuration.DnsDiscoveryConfigExtended;
import org.junit.Before;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.MockitoAnnotations.initMocks;

public class DCOSClusterDiscoveryTest {

    DCOSClusterDiscovery DCOSClusterDiscovery;

    @Mock
    DnsDiscoveryConfigExtended configuration;

    ClusterNodeAddress cla = new ClusterNodeAddress("localhost", 1883);

    @NotNull
    ClusterDiscoveryInput input = new ClusterDiscoveryInput() {
        @Override
        public @NotNull
        ClusterNodeAddress getOwnAddress() {
            return cla;
        }

        @Override
        public @NotNull
        String getOwnClusterId() {
            return "123";
        }

        @Override
        public int getReloadInterval() {
            return 1;
        }
    };

    @NotNull
    ClusterDiscoveryOutput output = new ClusterDiscoveryOutput() {
        //added just to make addresses accessible for testing
        private String addresses;

        @Override
        public void provideCurrentNodes(@NotNull List<ClusterNodeAddress> list) {
            if (list == null) {
                list = new ArrayList<ClusterNodeAddress>();
            }
            list.add(cla);
            addresses = list.iterator().next().getHost();
        }

        @Override
        public String toString() {
            return addresses;
        }        @Override
        public void setReloadInterval(int i) {
            i = 1;
        }


    };

    @Before
    public void setUp() {
        initMocks(this);
        DCOSClusterDiscovery = new DCOSClusterDiscovery(configuration);
    }

    // FIXME need to rework this test if even possible for the DCOS extension.

}
