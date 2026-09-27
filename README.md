# Distributed Rate Limiter

A Redis-backed, distributed rate limiter using the token bucket algorithm, implemented as a Spring Boot service. Built to explore how atomicity, clock consistency, and memory management actually work in a distributed system under concurrent load — not just to call a library.

## What it does

Given a key (user ID, IP, API key — whatever the caller wants to rate-limit by), the limiter tracks a token bucket per key in Redis and answers a simple question: *should this request be allowed right now?* The state is shared across any number of application instances, so the limit holds even when requests for the same key land on different servers.

## How it works

### Token bucket, not sliding window
Each key gets a bucket with a fixed `capacity` (max burst size) that refills at `refillRate` tokens/second. Chosen over sliding-window approaches because it's the cheapest to store — each key needs only two numbers (`tokens`, `last_refreshed`), regardless of request volume, versus a sliding-window log that grows with traffic.

### Atomicity via a single Lua script
The "read current tokens, decide, write back" sequence has to happen as one atomic unit, or two concurrent requests can both read the same token count and both get allowed — over-granting capacity. See [`token_bucket.lua`](src/main/resources/scripts/token_bucket.lua): the whole check-and-consume logic runs as a single `EVAL`, which Redis's single-threaded command execution guarantees is atomic against every other client, not just other threads in this JVM.

**Proof** [`NonluaTokenBucketLimiter`](src/main/java/com/george/distributed_rate_limiter/core/NonluaTokenBucketLimiter.java) deliberately reimplements the same logic as two separate Redis round trips instead of one Lua script. Under 50 concurrent requests racing for a bucket with `capacity=10`:

- **Lua version:** allowed exactly 10/ 10
- **NonLua version:** allowed 50 / 10, the rate limiter did not limit the rate.

### Clock source: Redis's own `TIME`, not the app server's
Refill math depends on elapsed time. If each app instance used its own system clock, small clock drift between instances would make the refill amount inconsistent depending on which instance last touched a given key. The Lua script calls Redis's `TIME` command instead, so there's one single source of truth for "now," regardless of which instance's request triggered the script.

### TTL: 2× the time to fully refill
Without a TTL, every key ever seen (every user, every IP) would live in Redis forever — a slow, silent memory leak, especially painful if the rate-limit dimension is something high-cardinality like IP address. The TTL is set to `ceil(capacity / refillRate) * 2`: the "1×" mark is already enough for the bucket to be logically full again, so 2× is pure defensive margin against clock imprecision and script execution latency, not a value derived from the algorithm itself — a real engineering trade-off (cheap memory margin vs. theoretical precision), not a magic number.

## Project structure

```
distributed-rate-limiter/
├── src/main/java/com/george/distributed_rate_limiter/
│   ├── core/
│   │   ├── RateLimiter.java              # interface
│   │   ├── TokenBucketLimiter.java       # Lua-backed, atomic implementation
│   │   └── NonluaTokenBucketLimiter.java  # intentionally non-atomic, for comparison only
│   └── demo/
│       └── DemoController.java           # GET /api/ping — try the limiter live
├── src/main/resources/
│   ├── application.properties
│   └── scripts/token_bucket.lua
├── src/test/java/.../core/
│   └── RateLimiterConcurrencyTest.java   # Testcontainers-backed concurrency proof
├── Dockerfile
├── docker-compose.yml
└── load-test.js                          # k6 load test script
```

## Getting started

### Prerequisites
- Java 17
- Docker Desktop (with WSL2 integration, on Windows)

### Run locally with Docker Compose

```bash
docker compose up --build -d
```

This starts Redis and the app together. Verify it's up:

```bash
curl "http://localhost:8080/api/ping?userId=alice"
```

Repeat quickly enough (more than `capacity` times within the refill window) and you'll start seeing `429 rate limit exceeded` instead of `pong`.

### Configuration

Set via environment variables (see `docker-compose.yml`) or `application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `ratelimiter.capacity` | 50 | Max tokens in the bucket (burst size) |
| `ratelimiter.refill-rate` | 2.0 | Tokens added per second (steady-state rate) |

## Testing

```bash
mvn test
```

`RateLimiterConcurrencyTest` spins up a real Redis container (via Testcontainers) and fires 50 concurrent requests at the same key, asserting:
- the Lua-based limiter never exceeds `capacity`
- the NonLua non-atomic limiter *does* exceed it — proving the race condition is real, not hypothetical

## Load testing

```bash
docker run --rm -i grafana/k6 run - < load-test.js
```

Results from a local run at 100 concurrent virtual users (`capacity=50`, `refill-rate=2.0`):

- **Total requests:** 53,555
- **Peak throughput:** 1,785 req/s
- **Requests allowed:** 109 (theoretical expectation ≈110, given the refill rate and test duration — essentially exact, no over-allowance)
- **Requests rejected (429):** 53,446 — a 99.8% rejection rate under sustained heavy load
- **Average latency:** 46.13 ms

The allowed count landing almost exactly on the theoretical value, even at 100x oversubscription, is the load-test-level confirmation of the same atomicity guarantee the concurrency test proves directly.

## Design trade-offs and open questions

- **Per-key limiting doesn't protect the system as a whole.** If 10,000 users are each within their own limit, the aggregate load on downstream resources can still exceed what the system can handle. A proper production setup would layer a *global* bucket on top of the per-key ones — see below.
- **A global bucket introduces a hot-key problem.** Unlike per-key limiting (which naturally spreads load across many Redis keys), a global bucket is a single key that every request contends for. Options for a future iteration: shard the global bucket into N sub-buckets, or maintain an approximate local counter per instance that syncs periodically — both are real trade-offs between precision and contention, not free wins.
- **Redis Cluster isn't supported yet.** The Lua script only ever touches one key per call, so it's cluster-safe today, but a global bucket implemented naively (touching both a global key and a per-key bucket in one script) would hit `CROSSSLOT` errors unless the keys are hash-tagged into the same slot (e.g. `rate_limiter:{global}` / `rate_limiter:{global}:user123`).

## An interesting debugging story

While wiring this up with Spring's constructor injection, `@Value("${ratelimiter.capacity:10}")` on a constructor parameter silently failed — Spring reported "No qualifying bean of type 'int'" as if the annotation weren't there at all. Inspecting the compiled bytecode directly with `javap` confirmed it: the constructor had no `RuntimeVisibleParameterAnnotations` attribute whatsoever, and the constant pool had zero references to the `@Value` class — the annotation was never seen by the compiler, despite being correctly present and imported in the source.

The class-level `@Component` annotation, by contrast, compiled and was read by Spring just fine. That pointed at something specific to *parameter*-level annotation retention rather than the annotation mechanism generally being broken — the leading suspect is the project's `maven-compiler-plugin` configuration, which wires in Lombok's annotation processor globally across every class in the build, even ones that never use a single Lombok annotation.

Root cause: the original `pom.xml` explicitly redeclared the `maven-compiler-plugin`'s `default-compile` and `default-testCompile` executions in order to wire in Lombok's `annotationProcessorPaths` — and in doing so, silently dropped Spring Boot's parent POM default of `<parameters>true</parameters>`. Without that flag, javac doesn't retain the parameter-level metadata Spring needs to resolve `@Value` on constructor parameters, even though the annotation itself is `RUNTIME`-retained and correctly targets `PARAMETER`. Explicitly adding `<parameters>true</parameters>` back into the compiler plugin configuration resolved it.

