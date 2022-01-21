package io.quarkiverse.renarde.oidc.test;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceConfigurableLifecycleManager;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.http.HttpServer;
import io.vertx.mutiny.ext.web.Router;

public abstract class MockOidcTestResource<ConfigAnnotation extends Annotation>
        implements QuarkusTestResourceConfigurableLifecycleManager<ConfigAnnotation> {
    private HttpServer httpServer;
    protected String baseURI;
    private String name;
    private int port;

    MockOidcTestResource(String name, int port) {
        this.name = name;
        this.port = port;
    }

    @Override
    public Map<String, String> start() {
        System.err.println("Starting OIDC Mock: " + name);
        Vertx vertx = Vertx.vertx();
        HttpServerOptions options = new HttpServerOptions();
        options.setPort(port);
        httpServer = vertx.createHttpServer(options);

        Router router = Router.router(vertx);
        httpServer.requestHandler(router);
        registerRoutes(router);

        System.err.println("Going to listen");
        httpServer.listenAndAwait();
        int port = httpServer.actualPort();
        System.err.println("Listening on port " + port);

        Map<String, String> ret = new HashMap<>();
        baseURI = "http://localhost:" + port;
        ret.put("quarkus.oidc." + name + ".auth-server-url", baseURI);
        return ret;
    }

    protected abstract void registerRoutes(Router router);

    @Override
    public void stop() {
        System.err.println("Closing OIDC Mock: " + name);
        httpServer.closeAndAwait();
    }

    protected String hashAccessToken(String string) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(string.getBytes(StandardCharsets.UTF_8));
            // keep 128 first bits, so 8 bytes
            byte[] part = new byte[8];
            System.arraycopy(digest, 0, part, 0, 8);
            return Base64.getUrlEncoder().encodeToString(part);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
