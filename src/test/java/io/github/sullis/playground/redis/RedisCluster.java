package io.github.sullis.playground.redis;

import java.io.File;
import org.testcontainers.containers.DockerComposeContainer;

public class RedisCluster extends DockerComposeContainer {
  private static final String RESOURCE_PATH = "/docker-compose.yaml";
  private static final File COMPOSE_FILE = new File(RedisCluster.class.getResource(RESOURCE_PATH).getFile());

  static {
    System.out.println("exists? " + COMPOSE_FILE.exists());
  }

  public RedisCluster() {
    super(COMPOSE_FILE);
    withExposedService("redis-node-5", 6379);
  }

  public String getRedisUser() {
    return null;
  }

  public String getRedisPass() {
    return "bitnami";
  }

  public int getRedisPort() {
    return this.getServicePort("redis-node-5", 6379);
  }
}
