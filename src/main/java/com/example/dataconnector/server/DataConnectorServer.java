package com.example.dataconnector.server;

import com.example.dataconnector.connector.ConnectorFactory;
import com.example.dataconnector.connector.ConnectorRegistry;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Starts the gRPC server.
 *
 * <pre>
 *   java -jar target/data-connector-api.jar [port] [config-file]
 *   defaults:                                50051  config/connectors.json
 * </pre>
 *
 * Besides our own service it registers two standard ones: <b>server reflection</b> (so tools
 * like grpcurl can discover the API without the .proto file) and <b>health checking</b> (so
 * load balancers / Kubernetes know the server is up).
 */
public final class DataConnectorServer {

  private static final Logger LOG = Logger.getLogger(DataConnectorServer.class.getName());

  private final Server server;
  private final ConnectorRegistry registry;

  public DataConnectorServer(int port, ConnectorRegistry registry) {
    this.registry = registry;
    HealthStatusManager health = new HealthStatusManager();
    this.server =
        NettyServerBuilder.forPort(port)
            .addService(ServerInterceptors.intercept(new DataConnectorServiceImpl(registry), new LoggingInterceptor()))
            .addService(ProtoReflectionService.newInstance())
            .addService(health.getHealthService())
            .maxInboundMessageSize(8 * 1024 * 1024)
            .build();
  }

  public DataConnectorServer start() throws IOException {
    server.start();
    LOG.info("Data Connector API listening on port " + server.getPort());
    registry.all().forEach(c -> LOG.info("  connector '" + c.id() + "' (" + c.type() + "): " + c.description()));
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.err.println("Shutting down gRPC server...");
                  stop();
                }));
    return this;
  }

  public void stop() {
    try {
      server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      registry.close();
    }
  }

  public int port() {
    return server.getPort();
  }

  public void blockUntilShutdown() throws InterruptedException {
    server.awaitTermination();
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tT %4$-7s %3$s - %5$s%6$s%n");
    int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(System.getenv().getOrDefault("PORT", "50051"));
    Path config = Path.of(args.length > 1 ? args[1] : "config/connectors.json");
    Path baseDir = config.toAbsolutePath().getParent().getParent(); // repo root
    ConnectorRegistry registry = ConnectorFactory.load(config, baseDir);
    new DataConnectorServer(port, registry).start().blockUntilShutdown();
  }
}
