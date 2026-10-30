# Galithin REST API

![Galithin REST API](docs/assets/hero-banner.png)

The Galithin REST API is a standalone Java 25/Kotlin service for the Galithin RPG Minecraft server. It projects HuskSync player snapshots, Galithin guild data, LuckPerms rank presentation, and a versioned Nexo content bundle into a web-friendly API.

HTTP requests read only the API-owned embedded H2 read model and the in-memory content catalog. They never decompress HuskSync data or query production plugin databases directly. The service reuses the existing HuskSync, Core, and LuckPerms MariaDB credentials for read-only source connections; it does not require a MariaDB schema or account of its own.

## Documentation

- [Getting started](docs/getting-started.md) — build, configure, generate content, and run locally.
- [Architecture](docs/architecture.md) — modules, data flow, runtime lifecycle, and extension points.
- [API reference](docs/api-reference.md) — endpoints, response fields, pagination, errors, caching, and examples.
- [Configuration](docs/configuration.md) — every YAML setting and environment variable.
- [Data projection](docs/data-projection.md) — HuskSync decoding, equipment rules, guilds, ranks, checkpoints, and freshness.
- [Content bundle](docs/content-bundle.md) — selected content, dependency resolution, catalog format, generation, and hot reload.
- [Rich text and website integration](docs/rich-text.md) — Adventure JSON, sanitized HTML, glyphs, shifts, models, and textures.
- [Security](docs/security.md) — API keys, rate limits, CORS, proxy trust, database grants, and exposure guidance.
- [Pterodactyl deployment](docs/deployment.md) — files, startup command, variables, health checks, reverse proxy, and rollout.
- [Operations and troubleshooting](docs/operations.md) — health, metrics, backups, updates, failure behavior, and diagnostics.
- [Development and testing](docs/development.md) — project layout, commands, tests, load testing, and adding features.
- [In-game freshness hooks](docs/integration-hooks.md) — the limited Core and Dungeons integrations.
- [OpenAPI 3.1 contract](application/src/main/resources/static/openapi.json) — also served from `GET /openapi.json`.

## Quick build

Java 25 is required.

```bash
./gradlew clean test build
```

The executable artifact is:

```text
application/build/libs/galithin-rest-api.jar
```

Generate the production content bundle:

```bash
./gradlew :content-tooling:buildBundle \
  -PgalithinRoot=/path/to/GalithinServer \
  -Poutput=/path/to/content/galithin-web-content.zip
```

Copy `application/src/main/resources/config.example.yml` to `config.yml`, provide the required environment variables, and start the service:

```bash
java -XX:MaxRAMPercentage=70 -jar galithin-rest-api.jar --config config.yml
```

The configuration path may also be supplied through `GALITHIN_API_CONFIG`.

## HTTP surface

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/players/{identifier}` | Public player profile by UUID or case-insensitive name |
| `GET` | `/api/v1/players/{identifier}/opengraph?v={version}` | Persistently cached 1600×900 player image |
| `GET` | `/api/v1/guilds/{identifier}` | Public guild profile by UUID, case-insensitive name, or tag |
| `GET` | `/api/v1/leaderboards/players/level` | Player level leaderboard |
| `GET` | `/api/v1/leaderboards/guilds/level` | Guild level leaderboard |
| `GET` | `/api/v1/catalog/{kind}` | Items, abilities, talents, badges, ranks, or guild progression |
| `GET` | `/assets/{catalogVersion}/{path}` | Immutable content asset |
| `GET` | `/health/live` | Process liveness |
| `GET` | `/health/ready` | Projection and content readiness |
| `GET` | `/metrics` | Protected Prometheus metrics |
| `GET` | `/openapi.json` | OpenAPI contract |

All profile, leaderboard, and catalog responses use a `data`/`meta` envelope. Errors use RFC 9457-style `application/problem+json` responses.

## Compatibility

- Java/JVM 25
- Kotlin 2.3.21
- Ktor 3.4.0
- Embedded H2 read model
- MariaDB source databases
- HuskSync v4 packed snapshot format 5
- Current Galithin Core guild schema
- LuckPerms SQL storage
- Current production Nexo configuration and resource pack

The rich-text renderer adapts the MIT-licensed render-hook approach from [PaperMC Adventure WebUI](https://github.com/PaperMC/adventure-webui). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
