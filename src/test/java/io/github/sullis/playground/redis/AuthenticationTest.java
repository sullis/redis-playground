package io.github.sullis.playground.redis;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisDataException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What a server started with {@code requirepass} accepts. One replica as well as a primary,
 * because the password reaches further than the clients: a replica is a client of its primary too,
 * and a replica that could not authenticate would never finish its initial sync.
 */
public class AuthenticationTest {
  private static final String PASSWORD = "playground";

  @RegisterExtension
  static final RedisReplication servers = RedisReplication.withPassword(PASSWORD, 1);

  @Test
  void anUnauthenticatedClientIsRefused() {
    Jedis client = servers.unauthenticatedClient();

    // The connection itself succeeds -- requirepass rejects commands, not TCP -- so the refusal
    // arrives as a reply to the first command rather than as a failure to connect.
    assertThatExceptionOfType(JedisDataException.class)
        .isThrownBy(() -> client.get("any-key"))
        .withMessageContaining("NOAUTH");
  }

  /**
   * That the server really is holding the password this fixture set. Without it the NOAUTH
   * assertion above would pass just as well against a server that rejected the client for some
   * other reason, or that was never given a password to check against.
   */
  @Test
  void theServerRequiresThePasswordTheFixtureSet() throws Exception {
    assertThat(servers.redisCli(servers.primary(), "config", "get", "requirepass"))
        .contains(PASSWORD);
  }

  @Test
  void anAuthenticatedClientIsServed() {
    Jedis client = servers.primaryClient();
    String key = UUID.randomUUID().toString();

    client.set(key, "authenticated");

    assertThat(client.get(key)).isEqualTo("authenticated");
  }

  /**
   * That the replica authenticated to the primary. Its fixture waits on the initial sync before
   * any test runs, so a replica holding a key written here is the masterauth wiring working end to
   * end rather than a second assertion about replication.
   */
  @Test
  void theReplicaAuthenticatedToThePrimaryAndIsReplicating() {
    Jedis primary = servers.primaryClient();
    String key = UUID.randomUUID().toString();
    primary.set(key, "replicated");
    servers.awaitReplication(primary);

    assertThat(servers.replicaClient(0).get(key)).isEqualTo("replicated");
  }
}
