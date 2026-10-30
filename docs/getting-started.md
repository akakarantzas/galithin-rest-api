# Getting started

This guide builds the executable JAR and production content bundle, creates the required configuration, and starts the API.

## Prerequisites

- JDK 25 available to Gradle and the runtime.
- Network access to the existing HuskSync, Galithin Core, and LuckPerms MariaDB schemas.
- The database host, database name, username, and password already configured by each plugin. No new MariaDB user or schema is required.
- A writable local directory for the embedded API read model.
- A Galithin server checkout containing `plugins/galithin-core`, `plugins/Nexo`, and the `galithin-core` source tree when generating the content bundle.
- HuskSync v4 data using packed snapshot format 5.

The API creates and migrates an embedded H2 database at `./data/galithin-read-model.mv.db` by default. Core, HuskSync, and LuckPerms schemas are not changed.

## 1. Build the service

From the repository root:

```bash
./gradlew clean test build
```

The shaded executable is written to:

```text
application/build/libs/galithin-rest-api.jar
```

Confirm that Java can read the artifact without starting the service:

```bash
jar tf application/build/libs/galithin-rest-api.jar >/dev/null
```

## 2. Generate the content bundle

Run the generator against the server root:

```bash
./gradlew :content-tooling:buildBundle \
  -PgalithinRoot=/Users/apostolos/Projects/GalithinServer \
  -Poutput=build/content/galithin-web-content.zip
```

The generator performs an atomic replacement of the output file. A successful production generation prints the number of checksummed files and the catalog version.

The current bundle includes web-visible gear, abilities, talents, badge dependencies, configured Nexo glyphs, Nexo fonts, and recursively required rendering assets. See [Content bundle](content-bundle.md).

## 3. Prepare storage and source credentials

No MariaDB SQL is required. Reuse the connection values already configured in the production plugins:

- HuskSync credentials for the HuskSync schema;
- Galithin Core credentials for the guild schema;
- LuckPerms credentials for the LuckPerms SQL schema.

The YAML supports different credentials for each source. The API opens all three source pools in JDBC read-only mode and never runs migrations against them. Reusing the plugin accounts is operationally simple, though it gives the API process whatever server-side privileges those accounts already have.

Create a writable local directory if it does not already exist:

```bash
mkdir -p data
```

The API automatically creates and migrates `data/galithin-read-model.mv.db`. Do not create `web_*` tables manually.

## 4. Create an API key

Bearer authentication is optional for public v1 data, but a key is required for `/metrics` and receives a higher rate limit.

Generate a strong secret, then calculate its SHA-256 digest. On macOS:

```bash
printf '%s' 'replace-with-a-long-random-secret' | shasum -a 256
```

Store the original secret in the authorized client. Put only the 64-character lowercase digest in `GALITHIN_API_KEY_SHA256` or directly in `security.apiKeySha256`.

## 5. Configure the service

Copy the example:

```bash
cp application/src/main/resources/config.example.yml config.yml
```

Set at least these environment variables:

```text
HUSKSYNC_DB_URL
HUSKSYNC_DB_USER
HUSKSYNC_DB_PASSWORD
GALITHIN_CORE_DB_URL
GALITHIN_CORE_DB_USER
GALITHIN_CORE_DB_PASSWORD
LUCKPERMS_DB_URL
LUCKPERMS_DB_USER
LUCKPERMS_DB_PASSWORD
GALITHIN_CONTENT_BUNDLE
```

Example JDBC URL:

```text
jdbc:mariadb://database.internal:3306/husksync?useUnicode=true&characterEncoding=utf8
```

`GALITHIN_READ_MODEL_PATH` is optional and defaults to `./data/galithin-read-model`. Do not include the `.mv.db` suffix.

Set the website origin in `server.corsOrigins`. If a reverse proxy supplies the client address, add only that proxy's CIDR to `server.trustedProxies`.

See [Configuration](configuration.md) for every option.

## 6. Start the service

```bash
java -XX:MaxRAMPercentage=70 \
  -jar application/build/libs/galithin-rest-api.jar \
  --config config.yml
```

Configuration path precedence is:

1. `--config <path>`;
2. `GALITHIN_API_CONFIG`;
3. `config.yml` in the process working directory.

Relative paths, including `content.bundlePath` and `storage.readModelPath`, resolve from the process working directory.

## 7. Verify startup

Liveness should become available as soon as the HTTP server starts:

```bash
curl -i http://127.0.0.1:8080/health/live
```

Readiness remains `503` until a valid content bundle is loaded and at least one player profile has been projected:

```bash
curl -i http://127.0.0.1:8080/health/ready
```

After readiness returns `200`, try a leaderboard and profile:

```bash
curl 'http://127.0.0.1:8080/api/v1/leaderboards/players/level?limit=10'
curl 'http://127.0.0.1:8080/api/v1/players/PlayerName'
```

The initial run backfills each player's latest HuskSync snapshot in pages. Progress is exposed through `galithin_projection_backfill_players` on `/metrics`.

## Next steps

- Use [Pterodactyl deployment](deployment.md) for production packaging and rollout.
- Use [API reference](api-reference.md) when implementing the website.
- Use [Operations](operations.md) to configure monitoring and diagnose readiness.
