package io.github.sullis.playground.redis;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class RedisServer extends GenericContainer<RedisServer> {
  private static final DockerImageName IMAGE = DockerImageName.parse("docker.io/library/redis:8.10.1");
  private static final int REDIS_PORT = 6379;
  private static final String PASSWORD = "playground";

  public RedisServer() {
    super(IMAGE);
    withExposedPorts(REDIS_PORT);
    withCommand("redis-server", "--requirepass", PASSWORD);
  }

  public String getRedisUser() {
    return null;
  }

  public String getRedisPass() {
    return PASSWORD;
  }

  public int getRedisPort() {
    return getMappedPort(REDIS_PORT);
  }
}
