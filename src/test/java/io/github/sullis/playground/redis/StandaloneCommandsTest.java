package io.github.sullis.playground.redis;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import redis.clients.jedis.Jedis;

import static org.assertj.core.api.Assertions.assertThat;

/** Single-node behaviour: server identity and plain key round-trips. */
public class StandaloneCommandsTest {
  // Nothing here observes replication, so a lone primary is the whole fixture.
  @RegisterExtension
  static final RedisReplication servers = RedisReplication.withReplicas(0);

  @Test
  void serverIdentifiesItselfAsARedisPrimary() {
    Jedis client = servers.primaryClient();

    assertThat(client.ping("Hello world")).isEqualTo("Hello world");

    // Redis has no server_name field, the way Valkey does: INFO SERVER names the product only
    // through redis_version, and the mode line is what says this node is not in a cluster.
    assertThat(client.info("server"))
        .contains("redis_version:")
        .contains("redis_mode:standalone");

    assertThat(client.info("replication")).contains("role:master");

    List<Object> role = client.role();
    assertThat(role.get(0).toString()).isEqualTo("master");
  }

  @Test
  void writesAreReadableBackAndVisibleToRandomkey() {
    Jedis client = servers.primaryClient();
    final String valuePrefix = "value-";

    // RANDOMKEY draws from the whole keyspace, so the assertion below is only meaningful if the
    // keys written here are the only ones there are.
    servers.flushKeyspace();

    Set<String> keys = new HashSet<>();
    for (int i = 0; i < 5; i++) {
      String key = UUID.randomUUID().toString();
      keys.add(key);
      client.set(key, valuePrefix + key);
    }

    for (String key : keys) {
      assertThat(client.get(key)).isEqualTo(valuePrefix + key);
    }

    assertThat(client.randomKey()).isIn(keys);
  }
}
