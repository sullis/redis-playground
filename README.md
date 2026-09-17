# redis-playground

[![CI](https://github.com/sullis/redis-playground/actions/workflows/ci.yml/badge.svg)](https://github.com/sullis/redis-playground/actions/workflows/ci.yml)

Executable notes on [Redis](https://redis.io/) behaviour, written as JUnit tests that drive real
servers. Each test starts Redis in Docker via [Testcontainers](https://testcontainers.com/) and
talks to it with [Jedis](https://github.com/redis/jedis), so an assertion here is a statement about
what a server actually does rather than about what the docs say it does.

There is no library to depend on and nothing to publish. The Testcontainers fixtures live under
`src/main/java` and the tests that drive them under `src/test/java`.

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

## Code coverage

A `mvn test` run writes a JaCoCo report to `target/site/jacoco/index.html`. What it measures is the
fixtures under `src/main/java`, which is the only tree JaCoCo's report goal reads. Take it as
"which fixture paths does the suite actually exercise" rather than as a quality bar — a fixture
method no test calls shows up here as an uncovered one.

Because surefire forks, the agent writes one execution file per fork to `target/jacoco/`
(`${surefire.forkNumber}` in the agent's `argLine`, expanded per fork), which a `merge` execution
folds into `target/jacoco.exec` before the report runs. Eight JVMs appending to a single shared
file would race.

A `check` execution then fails the build under **94% instruction coverage** over the bundle
(`jacoco.minimum.coverage` in the pom). Instructions rather than branches: fixture branch coverage
is mostly error paths a passing run never takes. One bundle-wide rule rather than a per-class one,
so that a small fixture with an uncovered branch or two need not clear the same bar as the tree.

There is little room left above that floor, and the remainder is not a to-do list. What the suite
does not reach is the fixtures' own failure handling — the port search giving up, and the `catch`
blocks in `start()` and `close()` that log and carry on — and reaching those means making a
container or a client fail on demand, which tests a mock rather than anything Redis does.

The floor applies to the fixtures as a whole, so a run narrowed to one class will trip it —
fixture coverage is not a meaningful number when surefire only ran `ClusterTest`. Add
`-Djacoco.check.skip=true` to those runs:

```sh
mvn -ntp test -Dtest=ClusterTest -Djacoco.check.skip=true
```

## What it covers

| Test | Topology | Asserts |
| --- | --- | --- |
| `StandaloneCommandsTest` | one node | the server reports itself as a standalone primary; writes read back and show up in `RANDOMKEY` |
| `AuthenticationTest` | primary + replica, `requirepass` | the server holds the password it was given; an unauthenticated client is refused with `NOAUTH` and an authenticated one is served; the replica authenticated to the primary and is replicating |
| `ReplicationTest` | primary + replica | the primary runs the version under test; both nodes report the replication link up; a primary write is readable from the replica; the replica rejects writes |
| `ClusterTest` | three shards | every node runs the version under test in cluster mode; a client discovers every node from a single seed address; every node agrees the slots are covered; a key lands only on the shard owning its slot |
| `ClusterExtensionTest` | three shards | `RedisCluster` forms a ready cluster when used as a plain `@RegisterExtension` field |
| `FixtureContractTest` | none | the guards that reject a topology which could not work, and the teardown of one that was declared but never started |

`ReplicationTest` and `ClusterTest` run once per supported Redis major — 7 and 8 today, pinned to
exact patches in `RedisImage` — because the reply formats they assert on (`role:master`,
`connected_slaves`, `CLUSTER INFO` fields) are a protocol surface, and a major release is the place
to change one. A pin is a patch rather than a floating `7` or `latest` so that a run either
reproduces or is not evidence of anything. The unparameterized tests run
`RedisImage.DEFAULT_REDIS_IMAGE`, the newest supported major.

## Fixtures

Two Testcontainers fixtures stand up every topology in the table above:

- `RedisReplication.withReplicas(n)` — a primary and `n` replicas, one container each, with a
  barrier that waits for the replication link before a test runs. `withReplicas(0)` is how the
  standalone tests get a lone primary.
- `RedisReplication.withPassword(password, n)` — the same shape with `requirepass` on every node
  and `masterauth` on the replicas, since a replica authenticates to its primary the way any other
  client does. `unauthenticatedClient()` is the matching client for the refusal case.
- `RedisCluster.withShards(n)` — an `n`-shard cluster splitting the 16384 hash slots, with a
  barrier that waits for slot coverage. `n` is at least 3, because Redis itself will not form a
  cluster with fewer primaries.

Both default to `RedisImage.DEFAULT_REDIS_IMAGE`, and both can be put on a version a test names,
though they say so differently: `RedisReplication.withImage(image, n)` is a second factory, while a
cluster takes the modifier `RedisCluster.withShards(n).onImage(image)`, so that every cluster is
declared the one way. The image has to be a Redis image or a rebuild of one: the fixtures run
`redis-server` by name, shell out to `redis-cli`, and wait on Redis's own readiness log line, so a
Valkey image surfaces as a startup timeout rather than as a clear error.

Unlike the replication fixture, a cluster runs all of its nodes in **one** container, announcing
`127.0.0.1` on ports published one-to-one to the host and drawn consecutively from a random base
between 20000 and 40000. A cluster client is given seed addresses but then connects to the ones the
cluster *advertises*, and loopback is the only address that means the same thing to a peer node and
to a test JVM on the host. `RedisCluster`'s class comment has the long version, including why
`--cluster-announce-ip` does not rescue a container-per-node shape.

Jedis has no read-preference to set on a standalone connection, so "read from the replica" is
spelled here as a second connection pointed at that replica, and a node-local question inside a
cluster is a plain `Jedis` on that node's published port rather than a routed command. That makes
each assertion stronger than a preference would — a reply came from that node and nowhere else — at
the cost of saying nothing about client-side routing, which standalone Jedis does not do.

## Adding a test

Pick the fixture the behaviour needs, then pick a lifecycle:

- **One version is enough.** Hold the fixture in a `static` field annotated
  `@RegisterExtension` and leave the lifecycle to JUnit. `StandaloneCommandsTest` is the short
  example.
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
