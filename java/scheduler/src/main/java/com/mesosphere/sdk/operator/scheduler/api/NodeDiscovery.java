package com.mesosphere.sdk.operator.scheduler.api;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

/**
 * Discovery mechanisms for the HiveMQ service
 */
@Path("/discovery")
public class NodeDiscovery {
    private final String nodeCount;

    public NodeDiscovery(final int count) {
        this.nodeCount = String.valueOf(count);
    }

    /**
     * Simply reports the current node count for the HiveMQ pods to query in a lightweight way so pod instances don't put too much stress on the scheduler.
     *
     * @return node count as a simple string
     */
    @GET
    @Path("nodeCount")
    @Produces("text/plain")
    public String nodeCount() {
        return nodeCount;
    }
}
