package io.github.sullis.playground.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A running set of Redis servers -- one primary plus {@code numReplicas} replicas, each in its own
 * container on a private Docker network -- together with the clients and keyspace plumbing needed
 * to drive them.
 *
 * <p>This is replication, not cluster mode: replicas are attached with {@code --replicaof} and
 * every client here is a plain {@link Jedis} pointed at one node, so there are no hash slots and
 * no {@code CLUSTER} membership involved. {@link RedisCluster} is the fixture for those.
 *
 * <p>A test class that wants one version owns one of these as a static field annotated
 * {@code @RegisterExtension}, which leaves the lifecycle to JUnit: started once before the class
 * and stopped after it, including when the start itself fails partway. A class parameterized over
 * versions cannot use that -- a static extension field is set up once, before any parameter
 * exists -- so it calls {@link #start()} and {@link #close()} from its own
 * {@code @BeforeParameterizedClassInvocation} hooks instead. Either way the servers start once per
 * set of test methods rather than per method, because starting them costs seconds.
 */
final class RedisReplication implements BeforeAllCallback, AfterAllCallback {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisReplication.class);

  /**
   * Network alias for the primary. A replica must reach the primary over the Docker network, so it
   * cannot use the host/mapped-port pair returned by getHost()/getFirstMappedPort().
   */
  private static final String PRIMARY_ALIAS = "redis-primary";

  /**
   * Every container gets its own network namespace, so the primary and its replicas can all listen
   * on the same port without colliding.
   */
  private static final int REDIS_PORT = 6379;

  /** Bounds how long a node has to satisfy its wait strategy before its start() fails. */
  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

  /** Bounds {@link #awaitReplication}, which fails as a short acknowledgement count. */
  private static final Duration REPLICATION_TIMEOUT = Duration.ofSeconds(15);

  private final DockerImageName image;

  private final int numReplicas;

  /**
   * The password every node requires, or null for nodes started without {@code requirepass} at
   * all. Fixed here at startup rather than set later, because a replica has to be told the
   * primary's password on its own command line ({@code --masterauth}) before it can sync.
   */
  private final String password;

  private final Network network = Network.newNetwork();

  /**
   * Populated as containers start rather than once they all have: a container registered here
   * before its {@code start()} is one {@link #close()} can still stop if a later container in the
   * set never comes up.
   */
  private final List<GenericContainer<?>> containers = new ArrayList<>();

  /**
   * Clients are built on first use and kept for the life of the servers, so that a test class
   * driving several methods pays for one connection per node rather than one per method. Tracked
   * here so that {@link #close()} closes whichever ones a test class actually asked for.
   */
  private final List<Jedis> clients = new ArrayList<>();

  private Jedis primaryClient;

  /** One per replica, built on demand; null at an index whose replica no test has asked about. */
  private final List<Jedis> replicaClients = new ArrayList<>();

  /**
   * Set by the first {@link #close()}, so that a second one is a no-op. A start that fails partway
   * closes what it built and then still meets its caller's own teardown, and closing the network
   * twice would throw.
   */
  private boolean closed;

  private RedisReplication(final DockerImageName image, final int numReplicas,
      final String password) {
    if (numReplicas < 0) {
      throw new IllegalArgumentException("a replica count cannot be negative, got " + numReplicas);
    }
    this.image = image;
    this.numReplicas = numReplicas;
    this.password = password;
    for (int i = 0; i < numReplicas; i++) {
      replicaClients.add(null);
    }
  }

  /**
   * Declares the servers on the image every fixture shares; nothing starts until JUnit calls
   * {@link #beforeAll}. {@code withReplicas(0)} is a lone primary, which is how the standalone
   * tests get one.
   */
  static RedisReplication withReplicas(final int numReplicas) {
    return new RedisReplication(RedisImage.DEFAULT_REDIS_IMAGE, numReplicas, null);
  }

  /**
   * As {@link #withReplicas}, but on a caller-supplied image -- for a test about one Redis version
   * in particular, or a run pointed at a mirror of the upstream image.
   *
   * <p>It has to be a Redis image or a rebuild of one, not a Valkey image: the nodes are started by
   * running {@code redis-server} by name and {@link #redisCli} shells out to {@code redis-cli},
   * neither of which a Valkey image ships.
   */
  static RedisReplication withImage(final DockerImageName image, final int numReplicas) {
    return new RedisReplication(image, numReplicas, null);
  }

  /**
   * Declares servers that require {@code password} on every connection, for a test about
   * authentication rather than about replication. Every node gets it: {@code requirepass} on all of
   * them and {@code masterauth} on the replicas, since a replica authenticates to the primary the
   * same way any other client does.
   */
  static RedisReplication withPassword(final String password, final int numReplicas) {
    if (password == null || password.isEmpty()) {
      // An empty requirepass is how Redis spells "no password", so it would silently produce
      // servers that are not what the caller asked for.
      throw new IllegalArgumentException("a password is needed, got an empty one");
    }
    return new RedisReplication(RedisImage.DEFAULT_REDIS_IMAGE, numReplicas, password);
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    start();
  }

  /**
   * Starts the servers, for a caller that drives the lifecycle itself rather than through
   * {@code @RegisterExtension} -- see the class comment. A start that fails partway tears down
   * whatever did come up before it throws, so the caller owes it no cleanup it is not already
   * doing.
   */
  void start() {
    try {
      startContainers();
    } catch (RuntimeException e) {
      // A failed start has no matching teardown call -- JUnit does not run afterAll for a failed
      // beforeAll -- so whatever did come up has to be stopped here rather than left running.
      close();
      throw e;
    }
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    close();
  }

  /**
   * Starts the nodes in primary-first order: a replica's wait strategy blocks on its initial sync,
   * which cannot complete until the primary is accepting connections.
   */
  private void startContainers() {
    final int numContainers = 1 + numReplicas;
    for (int i = 0; i < numContainers; i++) {
      final boolean isPrimary = i == 0;
      // slf4j interleaves every container's output into one stream, so without a prefix per
      // container the only hint at which node logged a line is redis's own M/S role character.
      final String role = isPrimary ? "primary" : "replica-" + (i - 1);
      List<String> command = new ArrayList<>(List.of("redis-server", "--port", String.valueOf(REDIS_PORT),
          // Without this the primary waits repl-diskless-sync-delay (5 seconds by default) before
          // forking for the replica's initial sync, which is dead time in every run.
          "--repl-diskless-sync-delay", "0",
          // Nothing here reads persisted data, so skip RDB snapshotting entirely.
          "--save", ""));
      if (password != null) {
        command.addAll(List.of("--requirepass", password));
      }
      GenericContainer<?> container = new GenericContainer<>(image)
          .withNetwork(network)
          .withExposedPorts(REDIS_PORT)
          .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix(role))
          .withStartupTimeout(STARTUP_TIMEOUT);
      if (isPrimary) {
        // The default port-listening probe can succeed before the server is actually serving
        // commands, so wait for the line redis logs once it is ready.
        container = container.withNetworkAliases(PRIMARY_ALIAS)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
      } else {
        // Replicate from the primary's in-network address, not its host-mapped port.
        command.addAll(List.of("--replicaof", PRIMARY_ALIAS, String.valueOf(REDIS_PORT)));
        if (password != null) {
          // A replica is a client of the primary, so requirepass applies to it too. Without this
          // the sync fails on NOAUTH and the wait strategy below times out.
          command.addAll(List.of("--masterauth", password));
        }
        // Do not hand out the replica until it has finished its initial sync.
        container = container.waitingFor(Wait.forLogMessage(".*REPLICA sync: Finished with success.*\\n", 1));
      }
      container = container.withCommand(command.toArray(new String[0]));
      containers.add(container);
      container.start();
      // The image is logged because a class parameterized over versions runs this twice, and
      // surefire labels the two invocations [1] and [2] rather than by image.
      LOGGER.info("started container id={} role={} image={}", container.getContainerId(), role, image);
    }
  }

  GenericContainer<?> primary() {
    return containers.get(0);
  }

  GenericContainer<?> replica(final int index) {
    return containers.get(1 + index);
  }

  /** A client connected to the primary, where both reads and writes are accepted. */
  Jedis primaryClient() {
    if (primaryClient == null) {
      primaryClient = newClient(primary());
    }
    return primaryClient;
  }

  /**
   * A client connected straight to one replica, for asserting replica-side state.
   *
   * <p>Pointed at the node rather than routed to it: Jedis has no read-preference to set on a
   * standalone connection, so "read from the replica" is spelled here as a second connection.
   * That makes the assertion stronger than a preference would -- a reply on this client came from
   * that replica and nowhere else -- at the cost of being no test of client-side routing, which
   * standalone Jedis does not do.
   */
  Jedis replicaClient(final int index) {
    if (numReplicas == 0) {
      throw new IllegalStateException("servers were started without replicas");
    }
    Jedis cached = replicaClients.get(index);
    if (cached != null) {
      return cached;
    }
    Jedis client = newClient(replica(index));
    replicaClients.set(index, client);
    return client;
  }

  /**
   * An unauthenticated client, for a test about what a password-protected server refuses. Not
   * cached: it exists to be rejected, and a caller may well want to close it and try again.
   */
  Jedis unauthenticatedClient() {
    if (password == null) {
      // Every client of a server without requirepass is already unauthenticated, so asking for
      // one is a test that means to assert something it cannot observe here.
      throw new IllegalStateException("servers were started without a password");
    }
    GenericContainer<?> target = primary();
    Jedis client = new Jedis(target.getHost(), target.getFirstMappedPort());
    clients.add(client);
    return client;
  }

  /** Builds a client over the host-mapped address of one container, authenticating if there is a
   * password to authenticate with. */
  private Jedis newClient(final GenericContainer<?> target) {
    String host = target.getHost();
    int port = target.getFirstMappedPort();
    LOGGER.info("connecting to {}:{} authenticated={}", host, port, password != null);
    Jedis client = new Jedis(host, port,
        DefaultJedisClientConfig.builder().password(password).build());
    clients.add(client);
    return client;
  }

  /**
   * Clears the keyspace, for a test that has to observe only the keys it wrote itself. Called by
   * that test rather than before every one, so that the assertion depending on an empty keyspace
   * sits next to the thing that empties it. FLUSHALL replicates, so this clears the replicas too
   * -- but only once they have applied it, hence the barrier.
   */
  void flushKeyspace() {
    Jedis client = primaryClient();
    client.flushAll();
    awaitReplication(client);
  }

  /**
   * Blocks until every replica has acknowledged the writes already issued on {@code client}'s
   * connection, so that a replica-side read afterwards is a deterministic assertion instead of a
   * poll. WAIT returns the number of replicas that acknowledged, which is asserted here: a timeout
   * shows up as a short count rather than as a later, more confusing read failure.
   *
   * <p>{@code client} has to be the one whose writes are being waited on -- WAIT reports on the
   * offset of the connection it arrives on -- and it has to be connected to the primary, since a
   * replica has no replicas of its own to wait for.
   */
  void awaitReplication(final Jedis client) {
    if (numReplicas == 0) {
      return;
    }
    assertThat(client.waitReplicas(numReplicas, REPLICATION_TIMEOUT.toMillis()))
        .as("replicas that acknowledged the write")
        .isEqualTo(numReplicas);
  }

  /**
   * Runs redis-cli inside a container. This is the only way to send a write to a replica: a client
   * here is connected to one node, and a write it rejects is rejected as an exception rather than
   * as a reply a test can read.
   *
   * <p>redis-cli exits 0 even when the server replies with an error, so the exit code below only
   * confirms the process itself ran -- the reply has to be asserted on by the caller.
   */
  String redisCli(final GenericContainer<?> container, final String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of("redis-cli", "-p", String.valueOf(REDIS_PORT)));
    if (password != null) {
      command.addAll(List.of("-a", password));
    }
    command.addAll(List.of(args));
    Container.ExecResult result = container.execInContainer(command.toArray(new String[0]));
    assertThat(result.getExitCode()).as("redis-cli process exit code").isZero();
    return result.getStdout();
  }

  /**
   * Closes the clients, stops every node and then the network they are attached to. Each step is
   * independent, and the network is closed either way: a leaked container or network outlives the
   * JVM, so one that refuses to go away must not take the others down with it.
   *
   * <p>Safe to call on a set that never started, and safe to call twice, so a caller driving the
   * lifecycle itself can tear down unconditionally.
   */
  void close() {
    if (closed) {
      return;
    }
    closed = true;
    for (Jedis client : clients) {
      try {
        client.close();
      } catch (RuntimeException e) {
        // A client that will not close cleanly must not abort the container teardown below.
        LOGGER.warn("failed to close a client", e);
      }
    }
    try {
      // Replicas first, so that none is left reconnecting to a primary that is already gone.
      for (int i = containers.size() - 1; i >= 0; i--) {
        GenericContainer<?> container = containers.get(i);
        try {
          container.stop();
        } catch (RuntimeException e) {
          LOGGER.warn("failed to stop container id={}", container.getContainerId(), e);
        }
      }
    } finally {
      network.close();
    }
  }
}
