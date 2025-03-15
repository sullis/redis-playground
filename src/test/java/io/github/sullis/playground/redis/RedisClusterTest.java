package io.github.sullis.playground.redis;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class RedisClusterTest {
  @Container
  static RedisCluster cluster = new RedisCluster();

  @Test
  public void happyPath() {
    for (Startable startable : cluster.getDependencies()) {
      System.out.println("startable: " + startable);
    }

    JedisPool pool = new JedisPool("localhost", cluster.getRedisPort(), cluster.getRedisUser(), cluster.getRedisPass());
    try (Jedis jedis = pool.getResource()) {
      jedis.set("clientName", "Jedis");
    }
  }
}
