package com.hivemq.extensions.rest;

import javax.servlet.*;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.IOException;

@Path("/health")
public class Health {

    @GET
    @Path("shutdown")
    public void shutdown() {
        System.exit(0);
    }
}
