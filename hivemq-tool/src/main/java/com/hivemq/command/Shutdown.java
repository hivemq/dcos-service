package com.hivemq.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

@Parameters(commandDescription = "Shut down a broker using its SRV record for the API.")
public class Shutdown {

    @Parameter(names = {"-u", "--uri"}, required = true, description = "Address for the Mesos-DNS SRV record pointing to the HiveMQ-DCOS extension API")
    String hostUri;

    public String getHostUri() {
        return hostUri;
    }
}