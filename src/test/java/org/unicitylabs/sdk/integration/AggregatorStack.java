package org.unicitylabs.sdk.integration;

import org.unicitylabs.sdk.api.bft.TrustBaseTestUtils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.unicitylabs.sdk.api.bft.trustbase.TrustBaseHistoryView;

/**
 * The aggregator stack the integration suite runs against: a BFT root node, mongodb, redis and a
 * pinned aggregator build, from the same compose file the TypeScript SDK uses.
 *
 * <p>The chain starts empty on every run and the aggregator takes an ephemeral port, so concurrent
 * runs cannot collide. There is deliberately no way to point the suite at a stack it did not
 * start.
 */
public final class AggregatorStack implements AutoCloseable {

  /** The aggregator's own port inside the container; the host port is ephemeral. */
  private static final int AGGREGATOR_PORT = 3000;
  /** Genesis, a replica-set election and the first certified round, on a cold start. */
  private static final Duration STARTUP = Duration.ofMinutes(4);

  private static final Path COMPOSE_DIR = Paths.get("src", "test", "resources", "integration");
  private static final Path DATA_DIR = COMPOSE_DIR.resolve("data");

  private final ComposeContainer environment;
  private final String url;
  private final int port;
  private final String networkName;

  private AggregatorStack(ComposeContainer environment, String url, int port, String networkName) {
    this.environment = environment;
    this.url = url;
    this.port = port;
    this.networkName = networkName;
  }

  /**
   * Start the stack and block until consensus is certifying rounds.
   *
   * <p>A healthy container is not a usable service: until consensus hands the aggregator a
   * reference time it answers every certification request with SERVICE_NOT_READY, so waiting on
   * the healthcheck alone would hand the tests a service that rejects everything.
   *
   * @return the running stack
   * @throws IOException if the genesis directories cannot be prepared
   * @throws InterruptedException if the wait is interrupted
   */
  public static AggregatorStack start() throws IOException, InterruptedException {
    // Genesis is bind-mounted and survives a container teardown. Reusing it against the fresh
    // mongodb and redis volumes below would pair a chain that remembers nothing with a root node
    // that remembers everything.
    deleteRecursively(DATA_DIR);
    Files.createDirectories(DATA_DIR.resolve("genesis"));
    Files.createDirectories(DATA_DIR.resolve("genesis-root"));

    String project = "stsdk" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    ComposeContainer environment = new ComposeContainer(
            project,
            new File(COMPOSE_DIR.resolve("docker-compose.yml").toString()))
            // Containerised compose rather than a local CLI: the build does not need docker on
            // its PATH, only a reachable daemon.
            // Port 0 publishes on an ephemeral host port, so concurrent runs and CI jobs cannot
            // collide on a fixed one.
            .withEnv("AGGREGATOR_PORT", "0")
            .withEnv("USER_UID", String.valueOf(currentUid()))
            .withEnv("USER_GID", String.valueOf(currentGid()))
            .withExposedService("aggregator", AGGREGATOR_PORT,
                    Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(STARTUP))
            .withStartupTimeout(STARTUP);
    environment.start();

    // Everything below can fail on a stack that is already up — readiness can time out, and the
    // lookups can throw. Nothing holds the environment until the constructor runs, so close() can
    // never be reached: without this, a failed startup leaves the whole stack and its genesis
    // behind, and the next run tries to delete a directory that running containers have mounted.
    try {
      int port = environment.getServicePort("aggregator", AGGREGATOR_PORT);
      String url = "http://" + environment.getServiceHost("aggregator", AGGREGATOR_PORT) + ":"
              + port;
      waitForCertification(url);

      String containerId = environment.getContainerByServiceName("aggregator")
              .orElseThrow(() -> new IllegalStateException("no aggregator service in the stack"))
              .getContainerId();

      return new AggregatorStack(environment, url, port, networkOf(containerId));
    } catch (IOException | InterruptedException | RuntimeException e) {
      stopQuietly(environment, e);
      throw e;
    }
  }

  /**
   * Tear down a stack whose startup failed, without losing the failure that caused it.
   *
   * @param environment the stack to stop
   * @param failure the failure being propagated, to attach any cleanup failure to
   */
  private static void stopQuietly(ComposeContainer environment, Exception failure) {
    try {
      environment.stop();
    } catch (RuntimeException e) {
      failure.addSuppressed(e);
    }
    try {
      deleteRecursively(DATA_DIR);
    } catch (IOException e) {
      failure.addSuppressed(e);
    }
  }

  /**
   * Get the aggregator endpoint.
   *
   * @return base URL
   */
  public String getUrl() {
    return this.url;
  }

  /**
   * Get the host port the aggregator is published on.
   *
   * @return host port
   */
  public int getPort() {
    return this.port;
  }

  /**
   * Get the name of the network the stack's services share.
   *
   * <p>A container that needs to talk to the aggregator joins this and addresses it as
   * {@code aggregator:3000}, which needs no published port and no host-gateway assumption.
   *
   * <p>Read off the running container rather than derived from the compose project name: how
   * Testcontainers names the network it creates is its business, and guessing it produced a name
   * that did not exist.
   *
   * @return docker network name
   */
  public String getNetworkName() {
    return this.networkName;
  }

  /**
   * Fetch the trust base history the aggregator of this run serves: the complete bundle (tag 39015)
   * from the genesis entry to the tip, as {@code GET /api/v1/trustbases} answers it. The stack is
   * the test's own, so the bundle is the local deployment's authority and its genesis entry is the
   * pin.
   *
   * @return bundle bytes
   * @throws IOException if the aggregator does not serve a bundle
   */
  public byte[] fetchTrustBaseBundle() throws IOException {
    return TrustBaseTestUtils.fetchServedBundle(this.url);
  }

  /**
   * Open the trust base history the aggregator of this run serves (see
   * {@link #fetchTrustBaseBundle()}), pinned by the identity of its own genesis entry.
   *
   * @return trust base
   * @throws IOException if the bundle cannot be fetched
   */
  public TrustBaseHistoryView getTrustBase() throws IOException {
    return TrustBaseTestUtils.fromBundle(fetchTrustBaseBundle());
  }

  @Override
  public void close() {
    this.environment.stop();
    try {
      deleteRecursively(DATA_DIR);
    } catch (IOException e) {
      // Best effort: the next start deletes it again before generating genesis.
    }
  }

  /**
   * Ask docker which network a container is attached to.
   *
   * @param containerId container to inspect
   * @return the single network name
   * @throws IOException if docker cannot be run
   * @throws InterruptedException if the wait is interrupted
   */
  private static String networkOf(String containerId) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(
            "docker", "inspect", "-f",
            "{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{end}}", containerId)
            .redirectErrorStream(true)
            .start();
    String name;
    try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      name = reader.readLine();
    }
    process.waitFor(30, TimeUnit.SECONDS);
    if (name == null || name.trim().isEmpty()) {
      throw new IllegalStateException("could not read the network of container " + containerId);
    }

    return name.trim();
  }

  private static void waitForCertification(String url) throws InterruptedException {
    OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build();
    long deadline = System.currentTimeMillis() + STARTUP.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (blockHeightAboveZero(client, url)) {
        return;
      }
      Thread.sleep(1000);
    }

    throw new IllegalStateException("Aggregator at " + url + " did not certify a block in time");
  }

  private static boolean blockHeightAboveZero(OkHttpClient client, String url) {
    Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"get_block_height\",\"params\":{}}",
                    MediaType.get("application/json")))
            .build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null) {
        return false;
      }
      String body = response.body().string();
      int at = body.indexOf("\"blockNumber\":\"");
      if (at < 0) {
        return false;
      }
      String value = body.substring(at + 15, body.indexOf('"', at + 15));

      return !value.isEmpty() && !"0".equals(value);
    } catch (IOException e) {
      return false;
    }
  }

  private static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException e) {
          // Leftovers are harmless; the compose stack recreates what it needs.
        }
      });
    }
  }

  private static long currentUid() {
    return posixId("uid");
  }

  private static long currentGid() {
    return posixId("gid");
  }

  private static long posixId(String which) {
    try {
      Process process = new ProcessBuilder("id", "-" + which.charAt(0)).start();
      try (java.io.BufferedReader reader = new java.io.BufferedReader(
              new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        return Long.parseLong(reader.readLine().trim());
      }
    } catch (Exception e) {
      // The compose file defaults to 1001, which is what CI runners use.
      return 1001L;
    }
  }
}
