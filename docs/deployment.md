# Pterodactyl deployment

## Required files

Create a server directory containing:

```text
galithin-rest-api.jar
config.yml
content/
└── galithin-web-content.zip
data/
├── (galithin-read-model.mv.db is created automatically)
├── opengraph/
└── playwright/
```

Use the shaded JAR from `application/build/libs/galithin-rest-api.jar` and the bundle produced by `:content-tooling:buildBundle`.

The API does not need the Minecraft server plugins folder at runtime.

## Pterodactyl runtime

- Java image/runtime: JDK 25.
- Main allocation: the internal HTTP port, usually `8080`.
- Recommended starting memory: 2 GB with two simultaneous image renders.
- Recommended CPU target: at least 2 vCPU for the acceptance load profile.
- Stop signal: normal process termination with a grace period.

Startup command:

```bash
java -XX:MaxRAMPercentage=70 \
  -XX:+ExitOnOutOfMemoryError \
  -jar galithin-rest-api.jar \
  --config config.yml
```

Do not add Minecraft/Paper flags intended for a game server. This is a Netty web service.

## Environment variables

Configure these Pterodactyl variables or inject them through your secret-management layer:

```text
SERVER_PORT
GALITHIN_API_KEY_SHA256
GALITHIN_CONTENT_BUNDLE
GALITHIN_READ_MODEL_PATH
GALITHIN_SITE_URL
GALITHIN_OG_FALLBACK_URL
GALITHIN_OG_CACHE_PATH
PLAYWRIGHT_BROWSERS_PATH
PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
HUSKSYNC_DB_URL
HUSKSYNC_DB_USER
HUSKSYNC_DB_PASSWORD
GALITHIN_CORE_DB_URL
GALITHIN_CORE_DB_USER
GALITHIN_CORE_DB_PASSWORD
LUCKPERMS_DB_URL
LUCKPERMS_DB_USER
LUCKPERMS_DB_PASSWORD
```

Recommended content path:

```text
/home/container/content/galithin-web-content.zip
```

Recommended read-model path:

```text
/home/container/data/galithin-read-model
```

Pterodactyl commonly uses `/home/container` as the process working directory, but verify the egg/image. Absolute paths avoid ambiguity.

## Example production configuration

```yaml
server:
  host: 0.0.0.0
  port: ${SERVER_PORT:-8080}
  corsOrigins:
    - https://www.galithin.com
  trustedProxies:
    - 10.0.0.10/32

security:
  apiKeySha256:
    - ${GALITHIN_API_KEY_SHA256}
  publicRequestsPerMinute: 120
  publicBurst: 30
  authenticatedRequestsPerMinute: 1200
  authenticatedBurst: 100

projection:
  pollIntervalMillis: 2000
  reconciliationIntervalSeconds: 300
  batchSize: 250

exclusions:
  groups:
    - admin-test
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
  cacheDirectory: ${GALITHIN_OG_CACHE_PATH:-/home/container/data/opengraph}
  browserExecutablePath: ${PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH:-}
  maxConcurrentRenders: 2
  rendersPerIpPerHour: 10
  renderTimeoutSeconds: 25
  retryAfterSeconds: 300

content:
  bundlePath: ${GALITHIN_CONTENT_BUNDLE}

storage:
  readModelPath: ${GALITHIN_READ_MODEL_PATH:-/home/container/data/galithin-read-model}
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

## Chromium worker

The API launches Chromium as a child process only for image cache misses. Browser calls run on blocking worker threads instead of Netty request threads, and `maxConcurrentRenders` limits their CPU and memory impact.

Use a container image with Chromium's required native libraries. Choose one browser setup:

1. Install Playwright's matching Chromium into persistent storage and use the same `PLAYWRIGHT_BROWSERS_PATH` for installation and API startup:

   ```bash
   PLAYWRIGHT_BROWSERS_PATH=/home/container/data/playwright \
   java -cp galithin-rest-api.jar com.microsoft.playwright.CLI install chromium
   ```

   Add `PLAYWRIGHT_BROWSERS_PATH=/home/container/data/playwright` to the Pterodactyl environment and leave `browserExecutablePath` empty.

2. Provide a compatible system Chromium/Chrome binary and set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to its absolute path.

The one-time browser installation does not start the API. If the image lacks Chromium's native libraries and the server cannot install OS packages, build them into the Pterodactyl image. Do not download a browser on every API restart.

Set `maxConcurrentRenders: 1` on a constrained node. Tune memory and concurrency from container usage plus the `galithin_opengraph_*` metrics.

## Reverse proxy

Terminate HTTPS before the API. Example Nginx location:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_connect_timeout 5s;
    proxy_read_timeout 30s;
}
```

If Nginx is on another host/container, use its exact private IP/CIDR in `trustedProxies`. The API does not currently use forwarded scheme/host for URL construction, because asset URLs are relative.

Preserve compression and caching headers. Avoid recompressing PNGs and already compressed assets unnecessarily.

## Health checks

Use:

- `/health/live` for a process liveness check;
- `/health/ready` before routing website traffic.

Readiness can take time on the first start because it requires at least one successfully projected player. A rolling deployment should keep the old instance serving until the new instance returns `200` from readiness.

## First rollout

Deploy in this order:

1. deploy the limited Core HuskSync freshness coordinator;
2. deploy the Dungeons run-end save hook;
3. copy the existing HuskSync, Core, and LuckPerms connection values into Pterodactyl secrets;
4. create persistent `data/`, `data/opengraph/`, and browser directories;
5. install/provision Chromium and its native libraries;
6. generate and upload the content bundle;
7. upload the API JAR and configuration;
8. start the API and watch embedded migration/backfill logs;
9. wait for readiness;
10. validate profile, Open Graph, leaderboard, catalog, asset, and metrics requests;
11. update website metadata to use the API image endpoint;
12. route website traffic.

No Core identity migration is required or used.

## Updating the JAR

1. Build and test the new commit.
2. Upload the new JAR under a temporary name.
3. Stop the Pterodactyl process normally.
4. replace `galithin-rest-api.jar`.
5. start and wait for readiness.
6. retain the prior JAR until validation completes.

Flyway migrations run automatically against the local H2 file. Stop the API before copying `data/galithin-read-model.mv.db` as a backup; do not copy the file while it is open.

## Updating content without restarting

Upload the new ZIP as a temporary file, then rename it over the configured bundle path. The API checks for modification every two seconds, validates checksums, and swaps the active catalog.

Do not upload directly over the live ZIP byte-by-byte. A partial file is rejected, but repeated watcher errors add noise and the update will not activate until valid.

## Rollback

- JAR rollback: restore the previous JAR and start it. Confirm its Flyway compatibility with the local read-model migration version.
- Content rollback: atomically restore the previous content ZIP. Its version becomes active again.
- Read-model rollback: normally unnecessary because projections are reproducible from source snapshots. If needed, stop the API, move the current `.mv.db` file to a safe backup name, and restart to create and backfill a fresh database.

## Post-deployment checks

```bash
curl -fsS https://api.example.com/health/live
curl -fsS https://api.example.com/health/ready
curl -fsS 'https://api.example.com/api/v1/leaderboards/players/level?limit=1'
curl -fsS https://api.example.com/openapi.json
curl -fsSI https://api.example.com/api/v1/players/Gnarly/opengraph
```

Follow the Open Graph redirect and confirm a 1600×900 PNG, an immutable cache header, and a cache hit on the second request. Then request one real profile, one `modelUrl`/texture URL, and protected metrics with a bearer key.
