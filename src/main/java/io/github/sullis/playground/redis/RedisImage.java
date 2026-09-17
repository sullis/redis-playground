package io.github.sullis.playground.redis;

import java.util.List;
import org.testcontainers.utility.DockerImageName;

/**
 * The Redis image the fixtures run unless a test asks for another one. Held in one place so that a
 * version bump is one edit, and so that {@link RedisReplication} and {@link RedisCluster} cannot
 * drift onto different versions by default -- two fixtures on two versions would make a difference
 * between them look like a difference between replication and cluster mode. A test that wants a
 * particular version says so explicitly, through {@link RedisReplication#withImage} or
 * {@link RedisCluster#onImage}.
 */
final class RedisImage {
  /**
   * Pinned to a patch rather than floating on {@code 7} or {@code latest}: a run either reproduces
   * or it is not evidence of anything.
   */
  static final DockerImageName REDIS_7 = DockerImageName.parse("docker.io/library/redis:7.4.11");

  /** Pinned for the same reason as {@link #REDIS_7}. */
  static final DockerImageName REDIS_8 = DockerImageName.parse("docker.io/library/redis:8.10.1");

  /** The version a fixture runs when a test does not ask for one: the newest supported major. */
  static final DockerImageName DEFAULT_REDIS_IMAGE = REDIS_8;

  /**
   * The images a test parameterized over versions runs against -- one per supported major, newest
   * last, so that a matrix run ends on the same version the unparameterized tests use.
   *
   * <p>One entry per major rather than per patch release: the point is to catch a behaviour or a
   * reply format that changed between majors, and a patch release that changed either would be a
   * bug in Redis rather than something a test here should be pinned against.
   */
  static final List<DockerImageName> SUPPORTED_MAJORS = List.of(REDIS_7, REDIS_8);

  private RedisImage() {
  }
}
