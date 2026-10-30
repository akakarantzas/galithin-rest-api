# Security

## Public-data model

V1 exposes public player/guild profiles, leaderboard, and catalog data. A bearer key only provides a larger rate-limit bucket and access to metrics; it does not unlock additional profile fields.

Do not project email addresses, IP addresses, moderation notes, or other private data into `PlayerProfile` without introducing an explicit authorization boundary and a separate private contract.

## API keys

The service stores configured SHA-256 digests, not plaintext secrets.

Create a long random secret and calculate its digest:

```bash
printf '%s' 'replace-with-a-long-random-secret' | shasum -a 256
```

Configuration:

```yaml
security:
  apiKeySha256:
    - ${GALITHIN_API_KEY_SHA256}
```

Client request:

```http
Authorization: Bearer replace-with-a-long-random-secret
```

Comparison uses `MessageDigest.isEqual`. Keys should be rotated by temporarily configuring both old and new digests, deploying clients with the new secret, and then removing the old digest.

Never commit plaintext secrets or database passwords. Prefer Pterodactyl environment variables marked non-viewable where supported.

## Rate limiting

The API uses an in-memory token bucket per client IP or authenticated key digest.

Defaults:

| Identity | Refill | Burst |
|---|---:|---:|
| Public client IP | 120 requests/minute | 30 |
| Valid API key | 1,200 requests/minute | 100 |

The limiter applies to profiles, leaderboards, catalogs, assets, and metrics. Health/OpenAPI routes are outside the limiter.

For multiple API replicas, each process has independent buckets. Enforce a shared outer limit at the reverse proxy/CDN if a global limit is required.

## Trusted proxies and client IPs

The direct peer address is the rate-limit identity by default. `X-Forwarded-For` is honored only when that direct peer belongs to `server.trustedProxies`.

Example for a local reverse proxy:

```yaml
server:
  trustedProxies:
    - 127.0.0.1/32
    - ::1/128
```

Do not trust `0.0.0.0/0` or `::/0`. Configure the proxy to replace, not blindly append user-provided forwarding headers.

## CORS

Only configured exact origins may make browser cross-origin GET requests. The API allows the `Authorization` and `If-None-Match` request headers.

```yaml
server:
  corsOrigins:
    - https://www.galithin.com
    - https://staging.galithin.com
```

CORS is not an authentication mechanism. Non-browser clients can call public endpoints regardless of CORS.

## TLS and reverse proxy

The embedded Netty service serves HTTP. Terminate TLS at a trusted reverse proxy or ingress and expose only HTTPS publicly.

Recommended proxy behavior:

- use TLS 1.2 or later;
- set a bounded request/header size;
- pass the original host and a sanitized client address;
- apply an outer request limit;
- restrict `/metrics` additionally by network when possible;
- do not cache `application/problem+json` errors broadly;
- preserve `ETag`, `Cache-Control`, `Content-Type`, and `Retry-After` headers.

## Database permissions

The API reuses the database credentials already configured for HuskSync, Galithin Core, and LuckPerms. It opens those JDBC pools in read-only mode and only issues `SELECT` statements against plugin schemas. It does not create users, grant permissions, migrate source schemas, or write plugin tables.

Reusing existing credentials avoids MariaDB administration but means the API process holds whatever server-side privileges those plugin accounts already have. Protect Pterodactyl environment variables, restrict database network access to known hosts, and do not expose MariaDB publicly.

The API's writable data is limited to its local embedded H2 file. Restrict filesystem access to the Pterodactyl service account and keep `data/galithin-read-model.mv.db` outside any public web root. Flyway migrations run only inside that file.

## Content bundle trust

Treat the bundle as a deployable artifact. The loader protects against path traversal and verifies declared SHA-256 checksums, but anyone who can replace the complete ZIP can also supply a new valid manifest.

Restrict filesystem write access to the deployment account. Generate the bundle from reviewed server configuration/resource-pack sources.

Rich text escapes text and emits controlled nodes, but the website should still use a Content Security Policy. Limit image sources to the API/CDN and avoid enabling arbitrary inline script.

## Error handling and logging

Unexpected failures return a generic `500` detail and log the exception server-side. Configuration/database errors may contain sensitive connection details in startup logs; protect log access.

Do not log bearer tokens at the reverse proxy. Ktor call logging should be configured/filtered externally if headers are ever added to log formats.

## Current boundaries

- The API has no Core identity or ban-state table by design.
- Configured exclusions affect leaderboards, not profile lookup.
- API-key authentication is not user authentication.
- Metrics reveal operational/database pool details and must remain protected.
- Asset URLs are public and versioned, not signed.
