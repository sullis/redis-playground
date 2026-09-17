package io.github.sullis.playground.redis;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class RedisServerTest {
  @Container
  static RedisServer redis = new RedisServer();

  @Test
  public void happyPath() {
    JedisPool pool = new JedisPool(redis.getHost(), redis.getRedisPort(), redis.getRedisUser(), redis.getRedisPass());
    try (Jedis jedis = pool.getResource()) {
      jedis.set("clientName", "Jedis");
      assertThat(jedis.get("clientName")).isEqualTo("Jedis");
    }
  }
}
