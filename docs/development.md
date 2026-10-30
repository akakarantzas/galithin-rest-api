# Development and testing

## Toolchain

- JDK 25
- Gradle wrapper 9.4.0
- Kotlin 2.3.21
- Ktor 3.4.0
- H2 2.4.240
- MariaDB JDBC 3.5.7
- Flyway 11.20.3
- HikariCP 7.0.2
- Adventure 5.1.1
- JUnit 5.14.3

Use the checked-in Gradle wrapper. Do not require a globally installed Gradle.

## Repository layout

```text
galithin-rest-api/
├── application/       Ktor process and shaded JAR
├── content-tooling/   production content generator
├── domain/            stable models and ports
├── sources/           databases, decoders, projection, catalog, rich text
├── docs/              operator and consumer documentation
├── load/              k6 acceptance profile
└── build.gradle.kts
```

See [Architecture](architecture.md) for module responsibilities.

## Build commands

Compile and test everything:

```bash
./gradlew clean test build
```

Compile a specific module:

```bash
./gradlew :sources:compileKotlin
```

Run source-adapter tests:

```bash
./gradlew :sources:test
```

Build the shaded JAR:

```bash
./gradlew :application:shadowJar
```

Generate content:

```bash
./gradlew :content-tooling:buildBundle \
  -PgalithinRoot=/path/to/GalithinServer \
  -Poutput=build/content/galithin-web-content.zip
```

The build does not need to start a development server.

## Existing tests

The source module currently covers:

- HuskSync packed format-5 Snappy decoding;
- unsupported packed versions;
- profile equipment filtering;
- namespaced vanilla statistics;
- gradients/decorations;
- glyph and shift rendering;
- missing glyph handling;
- malicious text escaping.
- embedded read-model migrations and persistence;
- profile/guild upserts and retirement;
- deterministic embedded leaderboard pagination;
- projection success/failure state retention.

Tests live under `sources/src/test/kotlin`.

## Required tests for changes

### Decoder changes

Add anonymized fixtures for:

- the exact HuskSync packed version;
- modern item components;
- nested Galithin PDC containers;
- every new custom equipment slot/type;
- malformed and vanilla filtering;
- progression and dungeon data.

Never commit real player UUIDs, names, item UUIDs, chat, or moderation data.

### Database/projection changes

Use file-backed H2 tests for read-model migrations, writes, queries, and cursor ordering. Use Testcontainers/MariaDB integration tests for source adapters and production-source SQL behavior, including:

- equal timestamps and UUID checkpoint tie-breaks;
- restart/idempotency;
- late-row reconciliation;
- guild synchronization/disbanding;
- eligibility exclusions;
- cursor ordering and unique positions.

### HTTP changes

Use Ktor's test host for:

- UUID and case-insensitive name lookup;
- envelope metadata;
- problem details/content types;
- ETags and `304`;
- CORS;
- valid/invalid bearer keys;
- public/authenticated rate limits;
- cursor validation;
- asset cache headers and traversal rejection;
- readiness behavior.

### Content changes

Generate against a controlled fixture pack and assert:

- item allowlist behavior;
- exclusion of unrelated namespaces/sounds/models;
- recursive model/item/equipment dependencies;
- missing custom model/texture failure;
- font/glyph coverage;
- duplicate IDs;
- all progression levels and placeholders;
- manifest/checksum validation;
- deterministic catalog version for unchanged inputs.

## Load testing

The repository includes [k6.js](../load/k6.js). It targets 250 reads/second with:

- p95 below 100 ms;
- fewer than 0.1% failed requests.

Run against an already deployed instance:

```bash
BASE_URL=https://api.example.com \
PROFILE_ID=f84c6a79-2c4f-4f14-93f6-a401fd70c95a \
DURATION=2m \
k6 run load/k6.js
```

Without `PROFILE_ID`, the script exercises only the player leaderboard. With it, requests alternate between profile and leaderboard.

Public defaults allow far fewer than 250 requests/second per IP. For load tests, use an API key only after extending the harness to send it, raise limits in a dedicated environment, or distribute client identities at the test ingress. Do not weaken production limits for an ad hoc test.

## Adding a profile field

1. Add it to a domain DTO in `domain/Models.kt` with backward-compatible null/default behavior where possible.
2. Decode it from the correct adapter.
3. update read-model migration/query code if it needs indexing; JSON-only fields require no column.
4. add anonymized tests.
5. update OpenAPI and `docs/api-reference.md`.
6. assess whether the field is safe for public profiles.

Removing or changing an existing v1 field requires a new API version.

## Adding a leaderboard

Use keyset pagination, not offset pagination. The ordering must end with a unique stable identifier. Return an opaque cursor and globally deterministic positions.

Add:

- indexed read-model fields/table;
- projector writes;
- domain row/cursor types;
- repository method/query;
- route and cache key;
- OpenAPI/docs;
- integration and HTTP tests.

## Adding equipment types

Update all of:

- `EquipmentSlot` if it is a new slot;
- HuskSync inventory/PDC mapping;
- strict group/category validation;
- content generator selection;
- profile and content tests;
- website slot renderer;
- OpenAPI enum and docs.

Do not loosen validation to “any Nexo item”; the public API contract intentionally exposes Galithin gear only.

## Adding a catalog kind

1. Add the enum value.
2. generate `catalog/<kind>.json`.
3. load/use it in the projector/domain.
4. update the HTTP accepted kind enum.
5. update OpenAPI and docs.

## Schema migrations

Add immutable migrations under:

```text
sources/src/main/resources/db/migration
```

Do not modify an already deployed migration. Add the next `V<N>__description.sql` and test upgrade from the prior version.

## Dependency upgrades

High-risk compatibility boundaries:

- HuskSync packed format and database columns;
- Minecraft item/SNBT component formats;
- Core guild table schema;
- LuckPerms SQL schema/context behavior;
- Adventure serializers/MiniMessage;
- MariaDB source-query compatibility;
- H2/Flyway compatibility and file locking;
- Java 25 runtime compatibility.

Upgrade one boundary at a time, add fixtures, and canary projection metrics before production rollout.

## Documentation policy

When behavior changes, update in the same change:

- the relevant `docs/*.md` page;
- `README.md` if the quick-start/surface changes;
- `application/src/main/resources/static/openapi.json` for contract changes;
- `config.example.yml` for configuration changes.
