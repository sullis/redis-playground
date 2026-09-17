package io.github.sullis.playground.redis;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Primary/replica behaviour: how each node reports the link, and what a replica will accept. Run
 * once per supported Redis major, because the replication {@code INFO} fields asserted on below
 * are the pre-inclusive-naming ones ({@code role:master}, {@code connected_slaves}, {@code
 * slave0:}) that a major release is the place to rename.
 *
 * <p>Parameterized over the class rather than per test method: a {@code @ParameterizedTest} would
 * start a fresh set of servers for every method, and the servers cost seconds. One
 * {@code @ParameterizedClass} invocation covers every method below on one set.
 *
 * <p>The servers cannot be a static {@code @RegisterExtension} field here, the way the
 * single-version {@link StandaloneCommandsTest} has one: a static extension field is set up once,
 * before any invocation and so before any image, which is why the lifecycle is driven by the two
 * hooks below instead.
 */
@ParameterizedClass(name = "{0}")
@FieldSource("IMAGES")
public class ReplicationTest {
  static final List<DockerImageName> IMAGES = RedisImage.SUPPORTED_MAJORS;

  /**
   * The image this invocation runs. Declaring it is what makes the class parameterized at all --
   * without a constructor parameter or a {@code @Parameter} field, JUnit does not consider the
   * class to take an argument and refuses to inject one into the hooks below.
   */
  @Parameter
  DockerImageName image;

  private static RedisReplication servers;

  @BeforeParameterizedClassInvocation
  static void startServers(final DockerImageName image) {
    servers = RedisReplication.withImage(image, 1);
    servers.start();
  }

  @AfterParameterizedClassInvocation(injectArguments = false)
  static void stopServers() {
    // Runs even when startServers threw, which is why this tolerates a set that was never
    // assigned; a set that was assigned and then failed partway has closed itself already, and
    // RedisReplication.close() is a no-op the second time.
    if (servers != null) {
      servers.close();
      servers = null;
    }
  }

  /**
   * That the servers really are the version this invocation asked for. Without this the matrix
   * looks like it covers two majors whether or not it does: a tag that has moved, or one image
   * silently pulled for the other, would leave every assertion below passing twice on one version.
   */
  @Test
  void theServersRunTheVersionUnderTest() {
    String version = image.getVersionPart();

    assertThat(servers.primaryClient().info("server")).contains("redis_version:" + version);
    assertThat(servers.replicaClient(0).info("server")).contains("redis_version:" + version);
  }

  @Test
  void bothNodesReportTheReplicationLinkAsUp() {
    assertThat(servers.primaryClient().info("replication"))
        .contains("role:master")
        .contains("connected_slaves:1")
        .containsPattern("slave0:ip=.*,state=online");

    Jedis replicaClient = servers.replicaClient(0);

    // This client is connected to the replica itself, so this is the replica's own view.
    assertThat(replicaClient.info("replication"))
        .contains("role:slave")
        .contains("master_link_status:up")
        .contains("slave_read_only:1");

    assertThat(replicaClient.role().get(0).toString()).isEqualTo("slave");
  }

  @Test
  void aWriteOnThePrimaryIsReadableFromTheReplica() {
    Jedis primaryClient = servers.primaryClient();
    String key = UUID.randomUUID().toString();
    String value = "replicated-" + key;
    primaryClient.set(key, value);

    // Replication is asynchronous, so wait for the replica to acknowledge the write before
    // reading it back.
    servers.awaitReplication(primaryClient);
    assertThat(servers.replicaClient(0).get(key)).isEqualTo(value);
  }

  @Test
  void theReplicaRejectsWrites() throws Exception {
    String key = UUID.randomUUID().toString();

    // Over redis-cli rather than the replica's own client, so that the rejection can be read as
    // the text the server sent rather than as a client-side exception.
    assertThat(servers.redisCli(servers.replica(0), "set", key, "nope"))
        .contains("READONLY");

    // The rejection has to mean the write did not happen: a replica that accepted it locally
    // would diverge from the primary rather than report an error.
    assertThat(servers.redisCli(servers.replica(0), "exists", key).trim()).isEqualTo("0");
  }
}
