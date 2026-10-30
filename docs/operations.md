# Operations and troubleshooting

## Health model

### `/health/live`

Returns `200` when the Ktor process can answer. It does not query databases or verify the projector.

### `/health/ready`

Returns `200` only when:

- a content bundle has loaded successfully; and
- at least one row exists in `web_profiles`.

The body also reports stale state, catalog version, projected time, and the first stored projection error detail.

A source database failure after successful initialization may leave readiness at `200` with `stale: true`, because last-known profiles remain usable. Alert on both HTTP readiness and stale state.

## Metrics

`/metrics` requires a bearer key and returns Prometheus text format.

Custom metrics:

| Metric | Type | Meaning |
|---|---|---|
| `galithin_projection_decode_failures_total` | counter | HuskSync/profile decode failures. |
| `galithin_projection_lag_milliseconds` | gauge | Difference between latest successfully projected snapshot time and projection time. |
| `galithin_projection_backfill_players` | gauge | Number of players processed during initial backfill. |
| `galithin_rate_limit_rejections_total` | counter | Requests rejected by the application limiter. |
| `galithin_opengraph_cache_hits_total` | counter | Versioned PNGs served from persistent storage. |
| `galithin_opengraph_cache_misses_total` | counter | PNGs successfully generated on cache misses. |
| `galithin_opengraph_render_failures_total` | counter | Chromium launches, page loads, readiness waits, or screenshots that failed. |
| `galithin_opengraph_rate_limit_rejections_total` | counter | Image cache misses rejected by the render-specific hourly limiter. |

Ktor Micrometer also exports HTTP server measurements, and Hikari exports pool metrics for:

- `galithin-read-model`;
- `galithin-husksync-source`;
- `galithin-core-source`;
- `galithin-luckperms-source`.

Exact library metric names can vary with Micrometer versions. Discover them from the deployed `/metrics` output before writing dashboards.

Suggested alerts:

- readiness is `503` for more than five minutes after deployment;
- readiness body has `stale: true` for more than one poll/retry window;
- projection lag exceeds the freshness objective;
- decode failures increase after a HuskSync/Minecraft upgrade;
- any Hikari pool has no usable connections;
- HTTP 5xx rate exceeds the service objective;
- rate-limit rejection rate changes sharply;
- disk lacks room for bundle extraction, temporary files, the embedded read model, Playwright, or generated PNGs.

## Logs

Important messages include:

```text
Loaded web content bundle <catalogVersion>
Starting HuskSync latest-snapshot backfill
Completed HuskSync latest-snapshot backfill for <count> players
Projection cycle failed
Could not project HuskSync snapshot <versionUuid>
Could not load content bundle ... keeping the last valid version
```

Unhandled HTTP exceptions are logged server-side and return a generic public `500` response.

## Backups

Back up before read-model migration deployments:

- `data/galithin-read-model.mv.db`, primarily for quick rollback;
- active and previous content bundles;
- deployed configuration without plaintext secret export where possible.
- `data/opengraph/` when preserving already-shared historical image versions matters.

Stop the API before copying the H2 file so the backup is consistent. The read model is rebuildable from source databases: if it is lost, move the old file aside and restart; the API creates a fresh file and performs a latest-snapshot backfill.

Do not delete `web_projection_state` casually. Removing it intentionally triggers a full backfill.

## Common failures

### Process exits during startup

Check:

1. JDK is version 25;
2. configuration path exists;
3. every required `${ENVIRONMENT_VARIABLE}` is defined;
4. JDBC URLs and credentials are valid;
5. `storage.readModelPath` has a writable parent directory;
6. all three MariaDB source hosts are reachable;
7. the existing plugin database credentials are valid;
8. the content path is syntactically valid.

The HTTP server is created only after runtime components initialize, so a source database configuration failure can prevent the port from binding.

### Liveness works, readiness stays `503`

Inspect the readiness body and logs. Typical causes:

- bundle missing/invalid;
- no HuskSync players exist;
- initial backfill has not projected its first valid profile;
- every latest HuskSync blob uses an unsupported format;
- source queries do not match deployed table names/schema;
- read-model writes fail.

Projection state is stored in the embedded `web_projection_state` table. Prefer the readiness response, metrics, and logs during normal operation. For direct database inspection, stop the API first, then use the H2 shell bundled in the JAR:

```bash
java -cp galithin-rest-api.jar org.h2.tools.Shell \
  -url 'jdbc:h2:file:./data/galithin-read-model;MODE=MariaDB;DATABASE_TO_LOWER=TRUE' \
  -user sa \
  -password '' \
  -sql 'SELECT source_name, checkpoint_timestamp, checkpoint_uuid, last_success_at, last_error, stale, catalog_version FROM web_projection_state'
```

### Profiles are stale after gear changes

Check the full chain:

1. Core's `WebProfileSyncCoordinator` is deployed and registered.
2. The player event marks data dirty.
3. The two-second trailing debounce completes while the player remains online.
4. The fingerprint changed.
5. The per-player five-second save floor elapsed.
6. HuskSync wrote a new `husksync_user_data` row.
7. the projector checkpoint advanced.
8. profile cache TTL elapsed or the request ETag changed.

Query recent source rows:

```sql
SELECT version_uuid, player_uuid, timestamp
FROM husksync_user_data
WHERE player_uuid = '<uuid>'
ORDER BY timestamp DESC, version_uuid DESC
LIMIT 5;
```

### Dungeon totals are stale

Dungeon totals are intentionally saved once when the player leaves a run, after outcome statistics are applied. They are not saved after each mob, ore, chest, or room event.

Confirm the Dungeons hook is deployed and a new HuskSync snapshot appears after leaving.

### Player name lookup returns `404`

- Confirm a projected `web_players.normalized_name` row exists.
- Name lookup is case-insensitive but uses the current projected name.
- Try the canonical UUID.
- Confirm the matching `web_profiles` row exists.

If direct inspection is necessary, stop the process and use the H2 shell procedure above to query `web_players` and `web_profiles`. In most cases, profile lookup by UUID plus projector logs is sufficient.

### Player missing from leaderboard

Check `web_players.eligible`. Eligibility can be false due to configured primary group, UUID, or name exclusions. A missing player may still be available through profile lookup.

### Rank prefix/icon differs from in-game

The API reconstructs presentation read-only from persisted LuckPerms SQL. Check:

- correct `tablePrefix`;
- correct `serverContext`;
- nodes are positive and unexpired;
- world context is `global`;
- prefix node uses `prefix.<numeric-priority>.<value>`;
- icon uses `meta.icon.<value>`;
- transient/live-only contexts are not being relied on.

Complex LuckPerms live context resolution can differ by design because Core is not modified to persist identity presentation.

### Guild member count is wrong

The count is current rows in `guild_members` plus one owner. Check for duplicated/inconsistent source membership rows and verify Core schema compatibility.

### Content update does not activate

Look for loader checksum/schema errors. Confirm:

- configured path points to the replaced file;
- file modification time changed;
- ZIP contains `manifest.json`;
- manifest schema is `1`;
- every manifest file exists and checksum matches;
- catalogs deserialize;
- replacement was completed atomically.

The previous catalog deliberately remains active after an invalid replacement.

### Asset returns `404`

Likely causes:

- URL uses an old `catalogVersion`;
- path was guessed instead of taken from `AssetReference`;
- the content update removed the asset;
- traversal/blank path protection rejected it.

Refresh the profile/catalog and request the newly supplied URL.

### `401` on a public endpoint

If an `Authorization` header is present but malformed/invalid, the API returns `401`. Remove it for public access or send a valid exact `Bearer <secret>` header.

### `429 Too Many Requests`

Respect `Retry-After: 60`. Check that the reverse proxy is trusted correctly; otherwise all users may share the proxy's direct IP bucket. Also check CDN/retry behavior for request amplification.

### CORS failure in browser

Add the exact scheme and authority to `server.corsOrigins`, then restart. Paths and trailing slashes should not be used as origins. Confirm the reverse proxy does not replace CORS headers.

## Maintenance procedures

### Rebuild all projected profiles

Stop the API, move `data/galithin-read-model.mv.db` to a timestamped backup name, and restart. The service creates and migrates a new embedded database, then backfills it from source snapshots. Keep the moved file until readiness and profile/leaderboard checks pass.

### HuskSync upgrade

Before production:

1. capture anonymized latest blobs from the new version;
2. verify packed `formatVersion`;
3. add/update decoder fixtures;
4. inspect SNBT keys/components;
5. build and run tests;
6. canary the projector and watch decode failures/lag;
7. retain the old API/read model for rollback.

### Content-only update

Generate, validate, and atomically replace the ZIP. No JAR restart is required.
