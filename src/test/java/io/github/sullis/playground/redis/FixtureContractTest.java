package io.github.sullis.playground.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What the fixtures do before anything is running: the guards that reject a set of servers which
 * could not work, and the teardown of one that was declared but never started.
 *
 * <p>No containers here, deliberately. Every case below is settled by the declaration alone, and
 * the classes that do pay for containers cannot reach any of them -- a test cannot ask its own
 * {@code @RegisterExtension} fixture for a client the fixture is supposed to refuse, because the
 * refusal would come out as a failed test rather than as an assertion.
 */
public class FixtureContractTest {
  /**
   * The shard floor sits in {@link RedisCluster}'s constructor rather than in one factory, so that
   * every route to a cluster is held to it -- this is the assertion that says so. Redis will not
   * form a cluster with fewer than three primaries, and a fixture that asked for two would surface
   * as a startup timeout rather than as anything readable.
   */
  @Test
  void aClusterCannotBeDeclaredWithFewerThanThreeShards() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> RedisCluster.withShards(2))
        .withMessageContaining("at least 3 shards");
  }

  /** There is always a primary, so the count is of the replicas beside it and cannot be negative. */
  @Test
  void serversCannotBeDeclaredWithANegativeReplicaCount() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> RedisReplication.withReplicas(-1))
        .withMessageContaining("cannot be negative");
  }

  /**
   * An empty requirepass is how Redis spells "no password", so servers declared with one would
   * quietly not be the password-protected servers the caller asked for, and a test asserting that
   * an unauthenticated client is refused would fail with nothing to point at.
   */
  @Test
  void serversCannotBeDeclaredWithAnEmptyPassword() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> RedisReplication.withPassword("", 0))
        .withMessageContaining("a password is needed");
  }

  /**
   * Asked of a lone primary, {@code replicaClient()} has nothing to read from. It says so rather
   * than handing back a client whose reads quietly land on the primary, which would make a test
   * that meant to observe replica-side state pass without observing anything.
   */
  @Test
  void aReplicaClientIsRefusedWhenThereAreNoReplicas() {
    RedisReplication servers = RedisReplication.withReplicas(0);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> servers.replicaClient(0))
        .withMessageContaining("without replicas");
  }

  /**
   * Every client of a server without {@code requirepass} is already unauthenticated, so asking for
   * one is a test that means to assert something it cannot observe there.
   */
  @Test
  void anUnauthenticatedClientIsRefusedWhenTheServersHaveNoPassword() {
    RedisReplication servers = RedisReplication.withReplicas(0);

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(servers::unauthenticatedClient)
        .withMessageContaining("without a password");
  }

  /**
   * A cluster that was declared and never started still has to tear down, and twice: a caller that
   * drives the lifecycle itself -- {@link ClusterTest} -- closes unconditionally in a hook that
   * runs even when the start threw, and a start that fails partway has already closed itself.
   */
  @Test
  void aClusterThatNeverStartedClosesQuietly() {
    RedisCluster cluster = RedisCluster.withShards(3);

    assertThatCode(() -> {
      cluster.close();
      cluster.close();
    }).doesNotThrowAnyException();
  }

  /**
   * The same contract for the replication fixture, where the second close is the more delicate of
   * the two: {@link RedisReplication#close()} closes the Docker network in a {@code finally}, and
   * closing a network twice throws. The {@code closed} flag is what keeps that out of the teardown.
   */
  @Test
  void serversThatNeverStartedCloseQuietly() {
    RedisReplication servers = RedisReplication.withReplicas(1);

    assertThatCode(() -> {
      servers.close();
      servers.close();
    }).doesNotThrowAnyException();
  }
}
