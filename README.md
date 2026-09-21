# redis-playground

[![CI](https://github.com/sullis/redis-playground/actions/workflows/ci.yml/badge.svg)](https://github.com/sullis/redis-playground/actions/workflows/ci.yml)

Executable notes on [Redis](https://redis.io/) behaviour, written as JUnit tests that drive real
servers. Each test starts Redis in Docker via [Testcontainers](https://testcontainers.com/) and
talks to it with [Jedis](https://github.com/redis/jedis), so an assertion here is a statement about
what a server actually does rather than about what the docs say it does.

There is no library to depend on and nothing to publish. The Testcontainers fixtures live under
`src/main/java` and the tests that drive them under `src/test/java`.

A whole test, abridged from
[`StandaloneCommandsTest`](src/test/java/io/github/sullis/playground/redis/StandaloneCommandsTest.java):

```java
public class StandaloneCommandsTest {
  // Nothing here observes replication, so a lone primary is the whole fixture.
  @RegisterExtension
  static final RedisReplication servers = RedisReplication.withReplicas(0);

  @Test
  void serverIdentifiesItselfAsARedisPrimary() {
    Jedis client = servers.primaryClient();

    assertThat(client.info("server")).contains("redis_mode:standalone");
    assertThat(client.info("replication")).contains("role:master");
  }
}
```

## What it covers

| Test | Topology | Asserts |
| --- | --- | --- |
| [`StandaloneCommandsTest`](src/test/java/io/github/sullis/playground/redis/StandaloneCommandsTest.java) | one node | the server reports itself as a standalone primary; writes read back and show up in `RANDOMKEY` |
| [`AuthenticationTest`](src/test/java/io/github/sullis/playground/redis/AuthenticationTest.java) | primary + replica, `requirepass` | the server holds the password it was given; an unauthenticated client is refused with `NOAUTH` and an authenticated one is served; the replica authenticated to the primary and is replicating |
| [`ReplicationTest`](src/test/java/io/github/sullis/playground/redis/ReplicationTest.java) | primary + replica | the primary runs the version under test; both nodes report the replication link up; a primary write is readable from the replica; the replica rejects writes |
| [`ClusterTest`](src/test/java/io/github/sullis/playground/redis/ClusterTest.java) | three shards | every node runs the version under test in cluster mode; a client discovers every node from a single seed address; every node agrees the slots are covered; a key lands only on the shard owning its slot |
| [`ClusterExtensionTest`](src/test/java/io/github/sullis/playground/redis/ClusterExtensionTest.java) | three shards | `RedisCluster` forms a ready cluster when used as a plain `@RegisterExtension` field |
| [`FixtureContractTest`](src/test/java/io/github/sullis/playground/redis/FixtureContractTest.java) | none | the guards that reject a topology which could not work, and the teardown of one that was declared but never started |

`ReplicationTest` and `ClusterTest` run once per supported Redis major — 7 and 8 today, pinned to
exact patches in [`RedisImage`](src/main/java/io/github/sullis/playground/redis/RedisImage.java) —
because the reply formats they assert on (`role:master`, `connected_slaves`, `CLUSTER INFO` fields)
are a protocol surface, and a major release is the place to change one. A pin is a patch rather
than a floating `7` or `latest` so that a run either reproduces or is not evidence of anything. The
unparameterized tests run `RedisImage.DEFAULT_REDIS_IMAGE`, the newest supported major.

## Requirements

- Java 17 or newer (`.sdkmanrc` pins `25.0.2-tem`; run `sdk env` to use it). The compiler targets
  17, and CI builds on 17 and 21.
- A running Docker daemon, reachable by Testcontainers
- Maven 3.9+ (no wrapper; CI invokes `mvn` directly)

## Running the tests

```sh
mvn -ntp -B clean test           # everything
mvn -ntp test -Dtest=ClusterTest # one class
mvn -ntp test -Dtest='ClusterTest#aKeyIsStoredOnlyByTheShardThatOwnsItsSlot'
```

The first run pulls the Redis images, so allow it some time. Surefire runs the test classes in
parallel across up to 8 forked JVMs (`forkCount`), and cluster formation costs a few seconds per
Redis version.

A run narrowed to one class trips the coverage floor described below, so those runs want
`-Djacoco.check.skip=true` too.

Three checks run before any container starts, so that a build broken in these ways fails in
seconds rather than after the suite. The enforcer rules hold the Maven and Java floors above and
reject a dependency that resolves *below* a version something else asked for — the case that
matters here is `docker-java-api`, pinned by hand to track whatever Testcontainers resolves, which
would otherwise surface at runtime as a `NoSuchMethodError`. The compiler runs `-Xlint:all` with
warnings fatal. The javadoc pass publishes nothing: it is there to catch a `{@link}` left pointing
at a method that has since been renamed. It runs over the tests as well as the fixtures, because
the test classes are the ones that explain themselves by naming classes they never call. Missing
`@param`/`@return` tags are deliberately not an error, because both trees explain themselves in
prose rather than in tag boilerplate.

## Fixtures

Two Testcontainers fixtures stand up every topology in the table above, between them through three
factories:

- [`RedisReplication`](src/main/java/io/github/sullis/playground/redis/RedisReplication.java)
  - `withReplicas(n)` — a primary and `n` replicas, one container each, with a barrier that waits
    for the replication link before a test runs. `withReplicas(0)` is how the standalone tests get
    a lone primary.
  - `withPassword(password, n)` — the same shape with `requirepass` on every node and `masterauth`
    on the replicas, since a replica authenticates to its primary the way any other client does.
    `unauthenticatedClient()` is the matching client for the refusal case.
- [`RedisCluster`](src/main/java/io/github/sullis/playground/redis/RedisCluster.java)
  - `withShards(n)` — an `n`-shard cluster splitting the 16384 hash slots, with a barrier that
    waits for slot coverage. `n` is at least 3, because Redis itself will not form a cluster with
    fewer primaries.

Both default to `RedisImage.DEFAULT_REDIS_IMAGE`, and both can be put on a version a test names,
though they say so differently: `RedisReplication.withImage(image, n)` is a third factory, while a
cluster takes the modifier `RedisCluster.withShards(n).onImage(image)`, so that every cluster is
declared the one way. The image has to be a Redis image or a rebuild of one: the fixtures run
`redis-server` by name, shell out to `redis-cli`, and wait on Redis's own readiness log line, so a
Valkey image surfaces as a startup timeout rather than as a clear error.

### What a test calls

`RedisReplication`:

| Member | Gives you |
| --- | --- |
| `primaryClient()` | a `Jedis` on the primary, where reads and writes are both accepted |
| `replicaClient(i)` | a `Jedis` connected straight to replica `i`; rejects the call if the fixture has no replicas |
| `unauthenticatedClient()` | a `Jedis` with no password, for the refusal case; rejects the call if the fixture has no password |
| `flushKeyspace()` | `FLUSHALL` on the primary, then waits for the replicas to apply it |
| `awaitReplication(client)` | `WAIT` until every replica has acknowledged that client's writes |
| `primary()`, `replica(i)` | the containers themselves |
| `redisCli(container, args…)` | `redis-cli` inside a container, for a reply read as text |

`RedisCluster`:

| Member | Gives you |
| --- | --- |
| `client()` | a `RedisClusterClient` seeded with node 0's address only, so that discovery is exercised rather than bypassed |
| `node(i)` | a `Jedis` straight at node `i`, for a question about that node alone — a `DBSIZE` here is its key count and nobody else's |
| `flushKeyspace()` | `FLUSHALL` on every node, since one sent to a single shard would leave the others populated |
| `numShards()`, `nodePort(i)`, `nodeAddresses()` | the shape of the cluster and the addresses it advertises |
| `redisCli(i, args…)` | `redis-cli` against node `i`, for a reply read as text |

Both fixtures also expose `start()` and `close()`, which only a version-parameterized class calls;
see [Adding a test](#adding-a-test). Every client above is built on first use and closed with the
fixture, so a test does not close what it is handed; the cached ones hand back the same connection
on a second call, and `unauthenticatedClient()` deliberately does not, since it exists to be
rejected.

### Why a cluster is one container

Unlike the replication fixture, a cluster runs all of its nodes in **one** container, announcing
`127.0.0.1` on ports published one-to-one to the host and drawn consecutively from a random base
between 20000 and 40000. A cluster client is given seed addresses but then connects to the ones the
cluster *advertises*, and loopback is the only address that means the same thing to a peer node and
to a test JVM on the host. `RedisCluster`'s class comment has the long version, including why
`--cluster-announce-ip` does not rescue a container-per-node shape.

Jedis has no read-preference to set on a standalone connection, so "read from the replica" is
spelled here as a second connection pointed at that replica, and a node-local question inside a
cluster is a plain `Jedis` on that node's published port rather than a command routed by
`RedisClusterClient`. That makes each assertion stronger than a preference would — a reply came
from that node and nowhere else — at the cost of saying nothing about client-side routing, which
standalone Jedis does not do.

## Adding a test

Pick the fixture the behaviour needs, then pick a lifecycle:

- **One version is enough.** Hold the fixture in a `static` field annotated
  `@RegisterExtension` and leave the lifecycle to JUnit, as in the example at the top of this file.
- **The behaviour is a reply format, or anything a major release could change.** Make the class
  `@ParameterizedClass` over `RedisImage.SUPPORTED_MAJORS` and drive the fixture from
  `@BeforeParameterizedClassInvocation` / `@AfterParameterizedClassInvocation`, as `ReplicationTest`
  and `ClusterTest` do. A static `@RegisterExtension` field cannot work here: it is set up once,
  before any invocation and so before any image exists.

Parameterize over the class rather than over each `@Test` — a `@ParameterizedTest` would rebuild
the topology for every method, and forming a cluster costs seconds.

One smaller convention: call `flushKeyspace()` first in a test that asserts over the whole
keyspace, such as one using `RANDOMKEY` or a per-node `DBSIZE`, because the methods of a class
share one fixture.

## Code coverage

A `mvn test` run writes a JaCoCo report to `target/site/jacoco/index.html`, and a `check` execution
fails the build under **94% instruction coverage** (`jacoco.minimum.coverage` in the pom). What the
bundle measures is the fixtures under `src/main/java`, so read it as "which fixture paths does the
suite exercise" rather than as a quality bar, and do not read the remainder above the floor as a
to-do list. The pom comments carry the reasoning — why instructions rather than branches, why one
bundle-wide rule, and why the agent writes one execution file per surefire fork.

The floor applies to the fixtures as a whole, so a run narrowed to one class will trip it:

```sh
mvn -ntp test -Dtest=ClusterTest -Djacoco.check.skip=true
```

## Testcontainers

- [Testcontainers Redis module](https://testcontainers.com/modules/redis/)
- [testcontainers-redis java library](https://github.com/redis-developer/testcontainers-redis)

## Apache NiFi and Redis

- [nifi-redis-bundle](https://github.com/apache/nifi/tree/main/nifi-extension-bundles/nifi-redis-bundle)
- [NiFi Redis testcontainers](https://github.com/apache/nifi/tree/main/nifi-extension-bundles/nifi-redis-bundle/nifi-redis-extensions/src/test/java/org/apache/nifi/redis/testcontainers)

## Redis resources

- [Jedis](https://github.com/redis/jedis)
- [official Docker image](https://hub.docker.com/_/redis)
- [Redis cluster specification](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/)

## License

[Apache License 2.0](LICENSE)
