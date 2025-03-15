package io.github.sullis.playground.redis;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
public class RedisClusterTest {
  @Container
  static GenericContainer container = new GenericContainer(DockerImageName.parse("bitnami/redis-cluster:latest"));

  @Test
  public void happyPath() {
    assertThat(container.isRunning()).isTrue();
  }
}
