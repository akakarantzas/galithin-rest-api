# Configuration

The service reads one YAML file at startup. Environment substitutions use `${NAME}` for a required variable and `${NAME:-default}` for a default value.

Substitution happens before YAML parsing. Quote values that may contain YAML-significant characters.

## Complete example

```yaml
server:
  host: 0.0.0.0
  port: ${SERVER_PORT:-8080}
  corsOrigins:
    - https://www.galithin.com
  trustedProxies:
    - 127.0.0.1/32

security:
  apiKeySha256:
    - ${GALITHIN_API_KEY_SHA256:-}
  publicRequestsPerMinute: 120
  publicBurst: 30
  authenticatedRequestsPerMinute: 1200
  authenticatedBurst: 100

projection:
  pollIntervalMillis: 2000
  reconciliationIntervalSeconds: 300
  batchSize: 250

exclusions:
  groups: []
  uuids: []
  names: []

cache:
  profileSeconds: 5
  leaderboardSeconds: 15
  catalogSeconds: 300

openGraph:
  enabled: true
  renderPageBaseUrl: ${GALITHIN_SITE_URL:-https://galithin.com}
  fallbackImageUrl: ${GALITHIN_OG_FALLBACK_URL:-https://galithin.com/static/images/statsBg.png}
  cacheDirectory: ${GALITHIN_OG_CACHE_PATH:-./data/opengraph}
  browserExecutablePath: ${PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH:-}
  maxConcurrentRenders: 2
  rendersPerIpPerHour: 10
  renderTimeoutSeconds: 25
  retryAfterSeconds: 300

content:
  bundlePath: ${GALITHIN_CONTENT_BUNDLE:-./content/galithin-web-content.zip}

storage:
  readModelPath: ${GALITHIN_READ_MODEL_PATH:-./data/galithin-read-model}
  maximumPoolSize: 8

luckPerms:
  tablePrefix: luckperms_
  serverContext: global

databases:
  huskSync:
    jdbcUrl: ${HUSKSYNC_DB_URL}
    username: ${HUSKSYNC_DB_USER}
    password: ${HUSKSYNC_DB_PASSWORD}
    maximumPoolSize: 4
  core:
    jdbcUrl: ${GALITHIN_CORE_DB_URL}
    username: ${GALITHIN_CORE_DB_USER}
    password: ${GALITHIN_CORE_DB_PASSWORD}
    maximumPoolSize: 4
  luckPerms:
    jdbcUrl: ${LUCKPERMS_DB_URL}
    username: ${LUCKPERMS_DB_USER}
    password: ${LUCKPERMS_DB_PASSWORD}
    maximumPoolSize: 2
```

The repository copy is [config.example.yml](../application/src/main/resources/config.example.yml).

## Server settings

| Key | Default | Meaning |
|---|---:|---|
| `server.host` | `0.0.0.0` | Address Netty binds to. |
| `server.port` | `8080` | HTTP listening port. |
| `server.corsOrigins` | empty | Exact browser origins permitted by CORS, including scheme and authority. Paths are ignored. |
| `server.trustedProxies` | empty | IPv4 or IPv6 CIDRs allowed to provide `X-Forwarded-For`. |

Only the first address in `X-Forwarded-For` is used, and only when the direct peer is inside a configured trusted proxy range. Invalid CIDR entries are ignored.

Do not add broad public networks to `trustedProxies`. Doing so lets clients choose another rate-limit identity.

## Security and rate limits

| Key | Default | Meaning |
|---|---:|---|
| `security.apiKeySha256` | empty | SHA-256 hex digests of accepted bearer secrets. Blank entries are discarded. |
| `security.publicRequestsPerMinute` | `120` | Token refill rate for an unauthenticated client IP. |
| `security.publicBurst` | `30` | Maximum public token-bucket capacity. |
| `security.authenticatedRequestsPerMinute` | `1200` | Token refill rate per valid API key. |
| `security.authenticatedBurst` | `100` | Maximum authenticated token-bucket capacity. |

API keys raise rate limits; they do not unlock private fields. `/metrics` is the only endpoint that requires a valid key.

Rate buckets are in memory and reset when the service restarts. They are not shared across multiple API replicas.

## Projection settings

| Key | Default | Constraints and meaning |
|---|---:|---|
| `projection.pollIntervalMillis` | `2000` | Delay between projection cycles and between retries after errors. |
| `projection.reconciliationIntervalSeconds` | `300` | Interval for a full latest-snapshot reconciliation. |
| `projection.batchSize` | `250` | Rows per query; clamped to `10..2000`. |

Lower poll intervals reduce latency but increase reads against HuskSync, Core, and LuckPerms. Core guilds and LuckPerms identities are refreshed once per projection cycle.

## Leaderboard exclusions

| Key | Meaning |
|---|---|
| `exclusions.groups` | LuckPerms primary group IDs excluded from player leaderboards. |
| `exclusions.uuids` | Player UUIDs excluded from player leaderboards. |
| `exclusions.names` | Player names excluded from player leaderboards. |

All three lists are normalized to lowercase. Exclusions affect player leaderboard eligibility only; a known player profile remains addressable through `/api/v1/players/{identifier}`.

Ban state is not currently sourced because the API intentionally does not add a Core identity schema. Use UUID/name/group exclusions for accounts that must not appear on leaderboards.

## Response cache settings

| Key | Default | Applies to |
|---|---:|---|
| `cache.profileSeconds` | `5` | Player and guild profile in-memory cache and browser `max-age`. |
| `cache.leaderboardSeconds` | `15` | Both leaderboard caches and browser `max-age`. |
| `cache.catalogSeconds` | `300` | Browser catalog `max-age`; catalog data itself is held in memory. |

Profile and leaderboard responses also advertise `stale-while-revalidate` at six times their `max-age`. Assets always use `public, max-age=31536000, immutable`.

The response cache is local to one process and lazily removes expired entries after it exceeds 10,000 keys.

## Open Graph image settings

| Key | Default | Meaning |
|---|---:|---|
| `openGraph.enabled` | `true` | Enables cache-miss rendering. Cached files and canonical redirects remain available when disabled. |
| `openGraph.renderPageBaseUrl` | `https://galithin.com` | Website origin containing `/stats/{player}/opengraph`. |
| `openGraph.fallbackImageUrl` | website stats background | Absolute fallback image URL used by temporary redirects. |
| `openGraph.cacheDirectory` | `./data/opengraph` | Persistent generated-PNG directory. |
| `openGraph.browserExecutablePath` | empty | Optional Chromium/Chrome executable. Empty uses Playwright's matching installed browser. |
| `openGraph.maxConcurrentRenders` | `2` | Concurrent Chromium processes, clamped to `1..8`. |
| `openGraph.rendersPerIpPerHour` | `10` | Cache misses permitted per client identity per process/hour, clamped to `1..1000`. |
| `openGraph.renderTimeoutSeconds` | `25` | Navigation/readiness/screenshot timeout, clamped to `1..120`. |
| `openGraph.retryAfterSeconds` | `300` | `Retry-After` used when the render limiter or capacity rejects a miss. |

The cache directory must be writable and persistent across restarts. The render limiter is in memory and is intentionally separate from the general API token bucket. Generated images are retained so previously shared versioned URLs remain stable; prune them only if that history is no longer required.

## Content settings

| Key | Required | Meaning |
|---|---|---|
| `content.bundlePath` | yes | Path to a content ZIP or an already-extracted bundle directory. |

The bundle is loaded at startup and checked for file modification every two seconds. Invalid replacements leave the previous valid catalog active.

## Embedded read-model storage

| Key | Default | Meaning |
|---|---:|---|
| `storage.readModelPath` | `./data/galithin-read-model` | Local H2 database path. H2 appends `.mv.db`; do not include that suffix. |
| `storage.maximumPoolSize` | `8` | Maximum embedded read-model connections, clamped to `1..32`. |

The parent directory is created automatically. It must be writable and persistent across Pterodactyl restarts. Flyway creates and upgrades the `web_*` schema inside this local file. Only one API process may open a given read-model file.

## LuckPerms settings

| Key | Default | Meaning |
|---|---:|---|
| `luckPerms.tablePrefix` | `luckperms_` | Prefix for LuckPerms SQL tables. Only letters, digits, and underscores are accepted. |
| `luckPerms.serverContext` | `global` | Server context accepted in addition to LuckPerms `global` nodes. |

The reader uses `players`, `group_permissions`, and `user_permissions` under this prefix. It resolves the primary group, inherited group nodes, highest-priority prefix, and `meta.icon.*` value.

## MariaDB source pool settings

Every pool supports:

| Key | Required | Meaning |
|---|---|---|
| `jdbcUrl` | yes | MariaDB JDBC URL. |
| `username` | yes | Database username. |
| `password` | yes | Database password. Prefer environment substitution. |
| `maximumPoolSize` | no | Maximum Hikari connections. |

Default pool sizes are 4 for HuskSync, 4 for Core, and 2 for LuckPerms. All three pools are opened in JDBC read-only mode. Reuse the values already configured in those plugins; the API does not require its own MariaDB credentials.

All pools use a five-second connection timeout, two-second validation timeout, and 60-second keepalive.

## Required environment variables

The example configuration requires:

| Variable | Purpose |
|---|---|
| `SERVER_PORT` | Optional port override. |
| `GALITHIN_API_KEY_SHA256` | Optional bearer-key digest. |
| `GALITHIN_CONTENT_BUNDLE` | Content bundle path. |
| `GALITHIN_READ_MODEL_PATH` | Optional embedded database path; defaults to `./data/galithin-read-model`. |
| `GALITHIN_SITE_URL` | Website origin containing the hidden player render page. |
| `GALITHIN_OG_FALLBACK_URL` | Absolute static image used when generation is unavailable. |
| `GALITHIN_OG_CACHE_PATH` | Persistent generated-image directory. |
| `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` | Optional installed Chromium/Chrome executable. |
| `HUSKSYNC_DB_URL`, `HUSKSYNC_DB_USER`, `HUSKSYNC_DB_PASSWORD` | HuskSync source. |
| `GALITHIN_CORE_DB_URL`, `GALITHIN_CORE_DB_USER`, `GALITHIN_CORE_DB_PASSWORD` | Core guild source. |
| `LUCKPERMS_DB_URL`, `LUCKPERMS_DB_USER`, `LUCKPERMS_DB_PASSWORD` | LuckPerms source. |

`GALITHIN_API_CONFIG` is separate from YAML substitution and selects the configuration file when `--config` is absent.
