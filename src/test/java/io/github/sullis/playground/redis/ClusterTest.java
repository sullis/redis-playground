package io.github.sullis.playground.redis;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.FieldSource;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.RedisClusterClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cluster-mode behaviour: how a client finds the nodes, and where a key ends up. Run once per
 * supported Redis major, for the same reason {@link ReplicationTest} is: the {@code CLUSTER INFO}
 * field names and the slot split asserted on below are a protocol surface, and a major release is
 * where one would change.
 *
 * <p>Parameterized over the class rather than per test method: a {@code @ParameterizedTest} would
 * form a fresh three-node cluster for every method, and forming one costs seconds. The cluster
 * cannot be a static {@code @RegisterExtension} field either -- a static extension field is set up
 * once, before any invocation and so before any image -- which is why the two hooks below drive
 * the lifecycle.
 */
@ParameterizedClass(name = "{0}")
@FieldSource("IMAGES")
public class ClusterTest {
  private static final int NUM_SHARDS = 3;

  static final List<DockerImageName> IMAGES = RedisImage.SUPPORTED_MAJORS;

  /**
   * The image this invocation runs. Declaring it is what makes the class parameterized at all --
   * without a constructor parameter or a {@code @Parameter} field, JUnit does not consider the
   * class to take an argument and refuses to inject one into the hooks below.
   */
  @Parameter
  DockerImageName image;

  private static RedisCluster cluster;

  @BeforeParameterizedClassInvocation
  static void startCluster(final DockerImageName image) throws Exception {
    cluster = RedisCluster.withShards(NUM_SHARDS).onImage(image);
    cluster.start();
  }

  @AfterParameterizedClassInvocation(injectArguments = false)
  static void stopCluster() {
    // Runs even when startCluster threw, which is why this tolerates a cluster that was never
    // assigned; one that was assigned and then failed partway has closed itself already, and
    // RedisCluster.close() is a no-op the second time.
    if (cluster != null) {
      cluster.close();
      cluster = null;
    }
  }

  /**
   * A key's slot is a property of the key alone -- CRC16(key) % 16384 -- and {@code --cluster
   * create} hands the shards an even, ascending split of the range, so these three keys land one
   * per shard on every run. That is what makes the per-node assertions below exact rather than
   * statistical.
   */
  private static final String SHARD_0_KEY = "alpha";
  private static final int SHARD_0_SLOT = 865; // of 0-5460

  private static final String SHARD_1_KEY = "bravo";
  private static final int SHARD_1_SLOT = 8623; // of 5461-10922

  private static final String SHARD_2_KEY = "echo";
  private static final int SHARD_2_SLOT = 14438; // of 10923-16383

  /**
   * That the nodes really are the version this invocation asked for. Without this the matrix looks
   * like it covers two majors whether or not it does: a tag that has moved, or one image silently
   * pulled for the other, would leave every assertion below passing twice on one version.
   */
  @Test
  void theNodesRunTheVersionUnderTest() throws Exception {
    String version = image.getVersionPart();

    for (int index = 0; index < NUM_SHARDS; index++) {
      assertThat(cluster.redisCli(index, "info", "server"))
          .as("INFO SERVER from node %d", index)
          .contains("redis_version:" + version)
          .contains("redis_mode:cluster");
    }
  }

  @Test
  void clientDiscoversEveryNodeFromASingleSeedAddress() {
    RedisClusterClient client = cluster.client();

    assertThat(client.ping()).isEqualTo("PONG");

    // The client was given one seed address, so a pool per node is the discovery working: it only
    // knows about the other two because it asked the seed for the topology.
    assertThat(client.getClusterNodes().keySet())
        .containsExactlyInAnyOrderElementsOf(cluster.nodeAddresses());

    assertThat(cluster.node(0).clusterShards()).hasSize(NUM_SHARDS);
  }

  @Test
  void everyNodeAgreesTheSlotsAreFullyCovered() {
    for (int index = 0; index < NUM_SHARDS; index++) {
      assertThat(cluster.node(index).clusterInfo())
          .as("CLUSTER INFO from node %d", index)
          .contains("cluster_state:ok")
          .contains("cluster_slots_assigned:16384")
          .contains("cluster_known_nodes:" + NUM_SHARDS);
    }
  }

  @Test
  void aKeyIsStoredOnlyByTheShardThatOwnsItsSlot() {
    RedisClusterClient client = cluster.client();

    // The per-node key counts below are only meaningful if the keys written here are the only
    // ones there are.
    cluster.flushKeyspace();

    // Pin the slots, so that a change in how keys hash shows up here rather than as a confusing
    // failure of the per-node counts further down.
    assertThat(cluster.node(0).clusterKeySlot(SHARD_0_KEY)).isEqualTo(SHARD_0_SLOT);
    assertThat(cluster.node(0).clusterKeySlot(SHARD_1_KEY)).isEqualTo(SHARD_1_SLOT);
    assertThat(cluster.node(0).clusterKeySlot(SHARD_2_KEY)).isEqualTo(SHARD_2_SLOT);

    // One write per shard. The client picks the node from the key's slot; nothing here says where
    // the write should go.
    for (String key : new String[] {SHARD_0_KEY, SHARD_1_KEY, SHARD_2_KEY}) {
      client.set(key, "value-" + key);
    }

    // Asking each node for its own key count is the assertion that the keyspace is really split:
    // a cluster that routed everything to one node would still read back correctly below.
    for (int index = 0; index < NUM_SHARDS; index++) {
      assertThat(cluster.node(index).dbSize())
          .as("keys held by node %d", index)
          .isEqualTo(1L);
    }

    for (String key : new String[] {SHARD_0_KEY, SHARD_1_KEY, SHARD_2_KEY}) {
      assertThat(client.get(key)).isEqualTo("value-" + key);
    }
  }
}
