package com.hivemq.extensions.rest;

import com.hivemq.extension.sdk.api.annotations.NotNull;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class RestService {
    @NotNull
    private static final Logger log = LoggerFactory.getLogger(RestService.class);


    public static final String REST_PORT_KEY = System.getenv("HIVEMQ_REST_PORT");

    @NotNull
    private final Server server;

    public RestService() {
        final String portString = REST_PORT_KEY;
        int port;
        if (portString != null && !portString.isEmpty()) {
            port = Integer.parseInt(portString);
        } else {
            throw new IllegalArgumentException("No port defined for REST endpoint");
        }
        this.server = new Server(new InetSocketAddress(port));
    }

    public void start() {
        final ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);
        ServletHolder jerseyServlet = context.addServlet(
                org.glassfish.jersey.servlet.ServletContainer.class, "/*");
        jerseyServlet.setInitOrder(0);

        // Tells the Jersey Servlet which REST service/class to load.
        jerseyServlet.setInitParameter(
                "jersey.config.server.provider.classnames",
                Health.class.getCanonicalName());

        try {
            server.start();
        } catch (final Exception e) {
            log.error("Error starting the Jetty Server for DCOS Extension");
            log.debug("Original exception was:", e);
        }
        log.info("Started Jetty Server on URI {}", server.getURI());
    }

    public void stop() {
        try {
            server.stop();
        } catch (Exception ex) {
            log.error("Failed stopping REST service: {}", ex);
        }
        server.destroy();
    }
}
