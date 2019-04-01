package com.hivemq.extensions.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

@Path("/health")
public class Health {

    private static final Logger log = LoggerFactory.getLogger(Health.class);


    @GET
    @Path("shutdown")
    public void shutdown() {
        // Note that this is initial placeholder logic and will be extended in the future as the extension SDK evolves
        log.info("Shutting down broker by REST call...");
        System.exit(0);
    }
}
