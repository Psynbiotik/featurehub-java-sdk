package io.featurehub.client.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EdgeRetryer implements EdgeRetryService {
  private static final Logger log = LoggerFactory.getLogger(EdgeRetryer.class);
  private final ExecutorService executorService;
  public final int serverConnectTimeoutMs;
  public final int serverDisconnectRetryMs;
  public final int serverByeReconnectMs;
  public final int backoffMultiplier;
  public final int maximumBackoffTimeMs;

  // this will change over the lifetime of reconnect attempts
  private int currentBackoffMultiplier;

  // if this is set, then we stop recognizing any further requests from the connection,
  // we can get subsequent disconnect statements. We know we cannot reconnect so we just stop.
  private boolean notFoundState = false;

  protected EdgeRetryer(int serverConnectTimeoutMs, int serverDisconnectRetryMs, int serverByeReconnectMs,
                      int backoffMultiplier, int maximumBackoffTimeMs) {
    this.serverConnectTimeoutMs = serverConnectTimeoutMs;
    this.serverDisconnectRetryMs = serverDisconnectRetryMs;
    this.serverByeReconnectMs = serverByeReconnectMs;
    this.backoffMultiplier = backoffMultiplier;
    this.maximumBackoffTimeMs = maximumBackoffTimeMs;

    currentBackoffMultiplier = backoffMultiplier;

    executorService = makeExecutorService();
  }

  // broken out for testability, can override with a mock pool
  protected ExecutorService makeExecutorService() {
    return Executors.newFixedThreadPool(1);
  }

  public void edgeResult(EdgeConnectionState state, EdgeReconnector reconnector) {
    log.trace("[featurehub-sdk] retryer triggered {}", state);
    if (notFoundState || executorService.isShutdown()) {
      return;
    }
    if (state == EdgeConnectionState.SUCCESS) {
      currentBackoffMultiplier = backoffMultiplier;
    } else if (state == EdgeConnectionState.API_KEY_NOT_FOUND) {
      log.warn("[featurehub-sdk] terminal failure attempting to connect to Edge, API KEY does not exist.");
      notFoundState = true;
    } else if (state == EdgeConnectionState.SERVER_WAS_DISCONNECTED) {
      executorService.submit(() -> {
        backoff(serverDisconnectRetryMs, true);
        reconnector.reconnect();
      });
    } else if (state == EdgeConnectionState.SERVER_SAID_BYE) {
      executorService.submit(() -> {
        backoff(serverByeReconnectMs, false);
        reconnector.reconnect();
      });
    } else if (state == EdgeConnectionState.SERVER_CONNECT_TIMEOUT) {
      executorService.submit(() -> {
        backoff(serverConnectTimeoutMs, true);
        reconnector.reconnect();
      });
    }
  }

  public void close() {
    executorService.shutdownNow();
  }

  @Override
  public int getServerConnectTimeoutMs() {
    return serverConnectTimeoutMs;
  }

  @Override
  public int getServerDisconnectRetryMs() {
    return serverDisconnectRetryMs;
  }

  @Override
  public int getServerByeReconnectMs() {
    return serverByeReconnectMs;
  }

  @Override
  public int getMaximumBackoffTimeMs() {
    return maximumBackoffTimeMs;
  }

  @Override
  public int getCurrentBackoffMultiplier() {
    return currentBackoffMultiplier;
  }

  @Override
  public int getBackoffMultiplier() {
    return backoffMultiplier;
  }

  @Override
  public boolean isNotFoundState() {
    return notFoundState;
  }

  // holds the thread for a specific period of time and then returns
  // while setting the next backoff incase we come back
  protected void backoff(int baseTime, boolean adjustBackoff) {
    try {
      Thread.sleep(calculateBackoff(baseTime, currentBackoffMultiplier) );
    } catch (InterruptedException ignored) {
    }

    if (adjustBackoff) {
      currentBackoffMultiplier = newBackoff(currentBackoffMultiplier);
    }
  }

  public long calculateBackoff(int baseTime, int backoff) {
    final long randomBackoff = baseTime + (long) ((1 + Math.random()) * backoff);

    final long finalBackoff = randomBackoff > maximumBackoffTimeMs ? maximumBackoffTimeMs : randomBackoff;

    log.trace("[featurehub-sdk] backing off {}", finalBackoff);

    return finalBackoff;
  }

  public int newBackoff(int currentBackoff) {
    int backoff = (int)((1+Math.random()) * currentBackoff);

    if (backoff < 2) {
      backoff = 3;
    }

    return backoff;
  }

  public static final class EdgeRetryerBuilder {
    private int serverConnectTimeoutMs;
    private int serverDisconnectRetryMs;
    private int serverByeReconnectMs;
    private int backoffMultiplier;
    private int maximumBackoffTimeMs;

    private EdgeRetryerBuilder() {
      serverConnectTimeoutMs = propertyOrEnv("featurehub.edge.server-connect-timeout-ms", "5000");
      serverDisconnectRetryMs = propertyOrEnv("featurehub.edge.server-disconnect-retry-ms",
        "5000");
      serverByeReconnectMs = propertyOrEnv("featurehub.edge.server-by-reconnect-ms",
        "3000");
      backoffMultiplier = propertyOrEnv("featurehub.edge.backoff-multiplier", "10");
      maximumBackoffTimeMs = propertyOrEnv("featurehub.edge.maximum-backoff-ms", "30000");
    }

    private int propertyOrEnv(String name, String defaultVal) {
      String val = System.getenv(name.replace(".", "_").replace("-", "_"));

      if (val == null) {
        val = System.getProperty(name, defaultVal);
      }

      return Integer.parseInt(val);
    }

    public static EdgeRetryerBuilder anEdgeRetrier() {
      return new EdgeRetryerBuilder();
    }

    public EdgeRetryerBuilder withServerConnectTimeoutMs(int serverConnectTimeoutMs) {
      this.serverConnectTimeoutMs = serverConnectTimeoutMs;
      return this;
    }

    public EdgeRetryerBuilder withServerDisconnectRetryMs(int serverDisconnectRetryMs) {
      this.serverDisconnectRetryMs = serverDisconnectRetryMs;
      return this;
    }

    public EdgeRetryerBuilder withServerByeReconnectMs(int serverByeReconnectMs) {
      this.serverByeReconnectMs = serverByeReconnectMs;
      return this;
    }

    public EdgeRetryerBuilder withBackoffFactorMs(int backoffFactorMs) {
      this.backoffMultiplier = backoffFactorMs;
      return this;
    }

    public EdgeRetryerBuilder withMaximumBackoffTimeMs(int maximumBackoffTimeMs) {
      this.maximumBackoffTimeMs = maximumBackoffTimeMs;
      return this;
    }

    public EdgeRetryer build() {
      return new EdgeRetryer(serverConnectTimeoutMs, serverDisconnectRetryMs, serverByeReconnectMs, backoffMultiplier
        , maximumBackoffTimeMs);
    }
  }
}
