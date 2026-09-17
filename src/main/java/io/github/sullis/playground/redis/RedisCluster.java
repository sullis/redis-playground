package io.github.sullis.playground.redis;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.RedisClusterClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A running Redis cluster -- {@code numShards} primaries owning an even split of the 16384 hash
 * slots -- together with the {@link RedisClusterClient} that reaches it.
 *
 * <p>This is cluster mode, not replication: there is no {@code --replicaof} link and no single
 * primary, so the sibling {@link RedisReplication} is the fixture for anything about the
 * replication stream. What this one adds is hash slots, {@code CLUSTER} membership, and
 * per-command routing.
 *
 * <p>A test class that wants one version owns one of these as a static field annotated
 * {@code @RegisterExtension}, which leaves the lifecycle to JUnit. A class parameterized over
 * versions calls {@link #start()} and {@link #close()} from its own invocation hooks instead,
 * because a static extension field is set up once, before any parameter exists. Both shapes work
 * the same way for {@link RedisReplication}.
 *
 * <h2>Why every node lives in one container</h2>
 *
 * <p>A cluster client is given seed addresses but then connects to the addresses the cluster
 * <em>advertises</em>, so those have to be reachable from wherever the client runs. That rules out
 * the container-per-node shape {@link RedisReplication} uses: nodes would advertise their private
 * Docker addresses, which a test JVM on the host cannot reach at all under Docker Desktop.
 *
 * <p>{@code --cluster-announce-ip} does not rescue that shape either, because an announced address
 * is used by the cluster bus as well as by clients: announce something host-reachable and the
 * nodes can no longer gossip with each other. The announced address therefore has to be correct
 * from inside the cluster <em>and</em> from the host, which leaves one arrangement -- every node in
 * a single container announcing loopback, on ports published one-to-one to the host. Inside the
 * container {@code 127.0.0.1:port} really is a peer node; from the host it is the published port.
 */
final class RedisCluster implements BeforeAllCallback, AfterAllCallback {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisCluster.class);

  /**
   * The address every node announces, and the one the client connects to. Loopback is the only
   * address that means the same thing inside the container and on the host -- see the class
   * comment.
   */
  private static final String LOOPBACK = "127.0.0.1";

  /**
   * Client ports are drawn consecutively from a random base rather than left to docker's random
   * mapping, which cannot be used here: the port has to be identical inside and outside the
   * container. The band is bounded well below 65535 because each node also listens on its cluster
   * bus port, which is its client port plus 10000; taking the ports consecutively keeps one node's
   * bus port clear of another node's client port.
   */
  private static final int MIN_BASE_PORT = 20_000;
  private static final int MAX_BASE_PORT = 40_000;
  private static final int PORT_ATTEMPTS = 20;
  private static final Random RANDOM = new Random();

  /** Bounds how long the nodes have to come up before the container's start() fails. */
  private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

  /** Bounds {@link #awaitClusterState}, which fails as a node still reporting a non-ok state. */
  private static final Duration CLUSTER_READY_TIMEOUT = Duration.ofSeconds(30);

  /** Not final: {@link #onImage} replaces it before {@link #start}, which is the first read. */
  private DockerImageName image;

  private final int numShards;

  private final List<Integer> ports;

  /**
   * Assigned before the container is started rather than after, so that a start which fails
   * partway still leaves {@link #close()} something to stop.
   */
  private GenericContainer<?> container;

  /**
   * Built on first use and kept for the life of the cluster: the client holds a connection pool
   * per node, which is not worth rebuilding per test method.
   */
  private RedisClusterClient client;

  /** One direct connection per node, built on demand; null at an index no test has asked about. */
  private final List<Jedis> nodeClients = new ArrayList<>();

  /**
   * Set by the first {@link #close()}, so that a second one is a no-op. A start that fails partway
   * closes what it built and then still meets its caller's own teardown.
   */
  private boolean closed;

  private RedisCluster(final DockerImageName image, final int numShards) {
    if (numShards < 3) {
      // Redis itself refuses to form a cluster with fewer than three primaries. Checked here
      // rather than in one factory, so that every way of building a cluster is held to it.
      throw new IllegalArgumentException("a cluster needs at least 3 shards, got " + numShards);
    }
    this.image = image;
    this.numShards = numShards;
    this.ports = reservePorts(numShards);
    for (int i = 0; i < numShards; i++) {
      nodeClients.add(null);
    }
  }

  /**
   * Declares a cluster of {@code numShards} primaries and no replicas, on the image every fixture
   * shares; nothing starts until JUnit calls {@link #beforeAll}. Three is the smallest shard count
   * that says anything about slot ownership.
   */
  static RedisCluster withShards(final int numShards) {
    return new RedisCluster(RedisImage.DEFAULT_REDIS_IMAGE, numShards);
  }

  /**
   * Puts this cluster on a caller-supplied image instead of the shared default -- for a test about
   * one Redis version in particular, or a run pointed at a mirror of the upstream image. Reads as
   * a modifier on {@link #withShards} rather than as a second factory, so that every cluster is
   * declared the one way: {@code withShards(3).onImage(image)}.
   *
   * <p>Call it before the cluster starts: the image is read once, when the container is built, so
   * a call after that would be ignored rather than move a running cluster. Unenforced, because the
   * guard would be fixture code no test reaches.
   *
   * <p>It has to be a Redis image or a rebuild of one, not a Valkey image: {@link #serverCommand}
   * runs {@code redis-server} by name and both {@link #formCluster} and {@link #redisCli} shell
   * out to {@code redis-cli}, neither of which a Valkey image ships.
   */
  RedisCluster onImage(final DockerImageName image) {
    this.image = image;
    return this;
  }

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    start();
  }

  /**
   * Starts the nodes and forms the cluster, for a caller that drives the lifecycle itself rather
   * than through {@code @RegisterExtension} -- see the class comment. A start that fails partway
   * tears down whatever did come up before it throws.
   */
  void start() throws Exception {
    try {
      startContainer();
      formCluster();
      awaitClusterState();
    } catch (Exception | AssertionError e) {
      // A failed start has no matching teardown call -- JUnit does not run afterAll for a failed
      // beforeAll -- so a container that did come up has to be stopped here rather than left
      // running.
      close();
      throw e;
    }
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    close();
  }

  private void startContainer() {
    container = new GenericContainer<>(image)
        .withExposedPorts(ports.toArray(new Integer[0]))
        // Publish each port to the identical host port, which withExposedPorts alone will not do.
        .withCreateContainerCmdModifier(cmd -> {
          Ports bindings = new Ports();
          ports.forEach(port -> bindings.bind(ExposedPort.tcp(port), Ports.Binding.bindPort(port)));
          cmd.getHostConfig().withPortBindings(bindings);
        })
        .withCommand("sh", "-c", serverCommand())
        .withLogConsumer(new Slf4jLogConsumer(LOGGER).withPrefix("cluster"))
        // The default port-listening probe can succeed before a server is serving commands, so
        // wait for the line each node logs once it is ready -- one per node.
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", numShards))
        .withStartupTimeout(STARTUP_TIMEOUT);
    container.start();
    // The image is logged because a class parameterized over versions runs this once per version,
    // and surefire labels the invocations [1] and [2] rather than by image.
    LOGGER.info("started container id={} ports={} image={}", container.getContainerId(), ports, image);
  }

  /** Starts one {@code redis-server} per shard in the background and keeps PID 1 alive. */
  private String serverCommand() {
    StringBuilder script = new StringBuilder();
    for (int port : ports) {
      script.append("redis-server")
          .append(" --port ").append(port)
          .append(" --cluster-enabled yes")
          // The nodes share a filesystem, so they would otherwise all write one nodes.conf.
          .append(" --cluster-config-file /tmp/nodes-").append(port).append(".conf")
          // See the class comment: loopback is correct for the bus and for the host alike.
          .append(" --cluster-announce-ip ").append(LOOPBACK)
          // Nothing here reads persisted data, so skip RDB snapshotting entirely.
          .append(" --save ''")
          // All nodes write to the one container stdout, and redis's own prefix is a pid, which
          // says nothing about which node logged the line. sed -u because a buffered pipe would
          // hold back the readiness line the wait strategy above is watching for.
          .append(" 2>&1 | sed -u 's/^/[node-").append(port).append("] /' &\n");
    }
    return script.append("wait\n").toString();
  }

  /**
   * Assigns the slots and joins the nodes. Driven by {@code redis-cli} rather than by the client's
   * own {@code clusterMeet}/{@code clusterAddSlots}: forming a cluster is this fixture's setup, and
   * a test asserting on a half-formed one would be asserting on the fixture.
   */
  private void formCluster() throws Exception {
    List<String> command = new ArrayList<>(List.of("redis-cli", "--cluster", "create"));
    ports.forEach(port -> command.add(LOOPBACK + ":" + port));
    // Take the proposed slot split instead of waiting on a prompt no one is there to answer.
    command.add("--cluster-yes");

    Container.ExecResult result = container.execInContainer(command.toArray(new String[0]));
    assertThat(result.getExitCode()).as("redis-cli --cluster create exit code").isZero();
    assertThat(result.getStdout())
        .as("redis-cli --cluster create output")
        .contains("[OK] All 16384 slots covered.");
  }

  /**
   * Blocks until every node agrees the cluster is healthy. {@code --cluster create} returns as soon
   * as the slots are assigned, which is before the nodes gossip their way to a shared view, and a
   * client built inside that window fails to resolve a topology at all. Asked over redis-cli rather
   * than over a client, because building the client is itself one of the things that needs the
   * cluster to be ready.
   */
  void awaitClusterState() {
    Awaitility.await("every node reports cluster_state:ok")
        .atMost(CLUSTER_READY_TIMEOUT)
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          for (int index = 0; index < numShards; index++) {
            assertThat(redisCli(index, "cluster", "info"))
                .as("CLUSTER INFO from node %d", index)
                .contains("cluster_state:ok");
          }
        });
  }

  int numShards() {
    return numShards;
  }

  int nodePort(final int index) {
    return ports.get(index);
  }

  /** Every node's address as the cluster advertises it, which is how the client keys its pools. */
  List<String> nodeAddresses() {
    return ports.stream().map(port -> LOOPBACK + ":" + port).toList();
  }

  /**
   * A client for the cluster, seeded with one node's address only: the rest of the topology is
   * discovered, and handing it every address would hide a discovery that does not work.
   */
  RedisClusterClient client() {
    if (client == null) {
      LOGGER.info("connecting to seed {}:{}", LOOPBACK, nodePort(0));
      client = RedisClusterClient.create(new HostAndPort(LOOPBACK, nodePort(0)));
    }
    return client;
  }

  /**
   * A client connected straight to one node, for a question about that node alone that the cluster
   * client cannot be trusted to answer -- it routes, and routing is often the thing under test. A
   * DBSIZE on this connection is that node's key count and nobody else's.
   */
  Jedis node(final int index) {
    Jedis cached = nodeClients.get(index);
    if (cached != null) {
      return cached;
    }
    Jedis client = new Jedis(LOOPBACK, nodePort(index));
    nodeClients.set(index, client);
    return client;
  }

  /**
   * Clears the keyspace, for a test that has to observe only the keys it wrote itself. Sent to
   * every node explicitly: a FLUSHALL that reached one shard would leave the others populated,
   * which is a confusing way to fail.
   */
  void flushKeyspace() {
    for (int index = 0; index < numShards; index++) {
      node(index).flushAll();
    }
  }

  /**
   * Runs redis-cli against one node from inside the container, for a reply a test wants to read as
   * text rather than as whatever the client parsed it into.
   *
   * <p>redis-cli exits 0 even when the server replies with an error, so the exit code below only
   * confirms the process itself ran -- the reply has to be asserted on by the caller.
   */
  String redisCli(final int nodeIndex, final String... args) throws Exception {
    List<String> command =
        new ArrayList<>(List.of("redis-cli", "-p", String.valueOf(nodePort(nodeIndex))));
    command.addAll(List.of(args));
    Container.ExecResult result = container.execInContainer(command.toArray(new String[0]));
    assertThat(result.getExitCode()).as("redis-cli process exit code").isZero();
    return result.getStdout();
  }

  /**
   * Picks {@code count} consecutive free host ports.
   *
   * <p>Free at the moment of the check, that is: the port is released again as the probe socket
   * closes, so something else can still take it before docker binds it. That race is the price of
   * needing fixed ports at all, and it surfaces as a container that fails to start rather than as
   * a wrong answer.
   */
  private static List<Integer> reservePorts(final int count) {
    for (int attempt = 0; attempt < PORT_ATTEMPTS; attempt++) {
      int base = MIN_BASE_PORT + RANDOM.nextInt(MAX_BASE_PORT - MIN_BASE_PORT - count);
      List<Integer> candidate = IntStream.range(0, count).map(i -> base + i).boxed().toList();
      if (candidate.stream().allMatch(RedisCluster::isFree)) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "could not find " + count + " consecutive free ports in " + PORT_ATTEMPTS + " attempts");
  }

  private static boolean isFree(final int port) {
    try (ServerSocket probe = new ServerSocket(port)) {
      return probe.getLocalPort() == port;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Closes the clients and stops the container. Each step is independent: a leaked container
   * outlives the JVM, so a client that refuses to close must not take the teardown down with it.
   *
   * <p>Safe to call on a cluster that never started, and safe to call twice, so a caller driving
   * the lifecycle itself can tear down unconditionally.
   */
  void close() {
    if (closed) {
      return;
    }
    closed = true;
    List<AutoCloseable> toClose = new ArrayList<>(nodeClients);
    toClose.add(client);
    for (AutoCloseable closeable : toClose) {
      if (closeable == null) {
        continue;
      }
      try {
        closeable.close();
      } catch (Exception e) {
        LOGGER.warn("failed to close a client", e);
      }
    }
    if (container != null) {
      try {
        container.stop();
      } catch (RuntimeException e) {
        LOGGER.warn("failed to stop container id={}", container.getContainerId(), e);
      }
    }
  }
}
