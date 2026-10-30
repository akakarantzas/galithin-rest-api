# API reference

## Conventions

- Base path: `/api/v1`.
- Public API responses use `application/json`.
- Errors use `application/problem+json`.
- All timestamps are ISO-8601 UTC strings.
- UUIDs are canonical lowercase strings.
- Unknown JSON fields may be added compatibly; clients should ignore fields they do not recognize.
- Profile data is public. A valid bearer key raises rate limits but does not add fields.

The machine-readable OpenAPI 3.1 contract is available at `/openapi.json` and in [openapi.json](../application/src/main/resources/static/openapi.json).

## Authentication and rate limiting

Public requests may omit `Authorization`:

```http
GET /api/v1/players/PlayerName HTTP/1.1
Host: api.example.com
```

Authenticated clients send the original secret whose SHA-256 digest is configured:

```http
Authorization: Bearer your-secret-api-key
```

An invalid/malformed bearer header returns `401`; it does not fall back to public access. `/metrics` always requires a valid key.

When a token bucket is exhausted, the API returns `429` and `Retry-After: 60`.

## Standard response envelope

Profiles, leaderboards, and catalogs use:

```json
{
  "data": {},
  "meta": {
    "apiVersion": "v1",
    "catalogVersion": "b1a1e8941d6dcc1f017e48b9",
    "sourceSavedAt": "2026-08-08T16:40:02.123Z",
    "projectedAt": "2026-08-08T16:40:03.007Z",
    "stale": false
  }
}
```

| Metadata field | Meaning |
|---|---|
| `apiVersion` | Contract version, currently `v1`. |
| `catalogVersion` | Active content bundle version used by asset URLs. |
| `sourceSavedAt` | Latest projected source snapshot time; `null` for catalogs. |
| `projectedAt` | Latest read-model projection time, or response time for catalogs. |
| `stale` | `true` when last-known data is being served after a marked source/projection failure. |

## Caching and conditional requests

Profile, leaderboard, and catalog responses include a strong SHA-256 `ETag`. Send it back with `If-None-Match`:

```http
If-None-Match: "ed77..."
```

An unchanged response returns `304 Not Modified` with no JSON body.

Default cache headers are:

| Resource | Header |
|---|---|
| Player profile | `public, max-age=5, stale-while-revalidate=30` |
| Leaderboard | `public, max-age=15, stale-while-revalidate=90` |
| Catalog | `public, max-age=300` |
| Asset | `public, max-age=31536000, immutable` |
| Versioned Open Graph image | `public, max-age=31536000, immutable` |
| OpenAPI | `public, max-age=300` |

Values except asset/OpenAPI caching are configurable.

## Endpoints

| Method | Path | Authentication | Success |
|---|---|---|---|
| `GET` | `/api/v1/players/{identifier}` | Optional | `200` |
| `GET` | `/api/v1/players/{identifier}/opengraph` | Optional | `200` or `307` |
| `GET` | `/api/v1/guilds/{identifier}` | Optional | `200` |
| `GET` | `/api/v1/leaderboards/players/level` | Optional | `200` |
| `GET` | `/api/v1/leaderboards/guilds/level` | Optional | `200` |
| `GET` | `/api/v1/catalog/{kind}` | Optional | `200` |
| `GET` | `/assets/{catalogVersion}/{path}` | Optional | `200` |
| `GET` | `/health/live` | None | `200` |
| `GET` | `/health/ready` | None | `200` or `503` |
| `GET` | `/metrics` | Required | `200` |
| `GET` | `/openapi.json` | None | `200` |

## Get a player profile

```http
GET /api/v1/players/{identifier}
```

`identifier` accepts a canonical UUID or player name. Name lookup is case-insensitive and returns the canonical current name stored in the projected profile.

Example:

```bash
curl 'https://api.example.com/api/v1/players/Apostolos'
```

Response shape:

```json
{
  "data": {
    "uuid": "f84c6a79-2c4f-4f14-93f6-a401fd70c95a",
    "name": "Apostolos",
    "level": 42,
    "experience": 182340,
    "rank": {
      "id": "vanguard",
      "label": {
        "adventureJson": "{\"text\":\"Vanguard\"}",
        "plainText": "Vanguard",
        "sanitizedHtml": "<span>Vanguard</span>",
        "sourceMiniMessage": null
      },
      "prefix": null,
      "icon": null
    },
    "badges": [],
    "guild": {
      "id": "0688465b-38da-4fd2-8edf-bf61fcd64630",
      "name": "The Philosophers",
      "tag": "THNK",
      "level": 12
    },
    "equipment": [],
    "statistics": {
      "advancementCount": 37,
      "kills": 84,
      "deaths": 12,
      "firstJoinedAt": "2025-03-01T13:00:00Z",
      "lastJoinedAt": "2026-08-08T16:40:02.123Z",
      "playTimeSeconds": 481920,
      "damage": 47.5,
      "health": 36.0,
      "defense": 0.18,
      "mana": 145.0
    },
    "dungeonStatistics": {
      "values": {}
    },
    "abilities": [],
    "talents": []
  },
  "meta": {
    "apiVersion": "v1",
    "catalogVersion": "b1a1e8941d6dcc1f017e48b9",
    "sourceSavedAt": "2026-08-08T16:40:02.123Z",
    "projectedAt": "2026-08-08T16:40:03.007Z",
    "stale": false
  }
}
```

### Player profile fields

| Field | Type | Meaning |
|---|---|---|
| `uuid` | string | Canonical player UUID. |
| `name` | string | Canonical current name. |
| `level` | integer | Galithin player level, minimum fallback `1`. |
| `experience` | integer | Current Galithin experience, minimum fallback `0`. |
| `rank` | `RankPresentation` | LuckPerms primary-group presentation. |
| `badges` | `BadgePresentation[]` | Up to three selected badges in slot order. |
| `guild` | `GuildSummary \| null` | Current guild summary. |
| `equipment` | `EquipmentItem[]` | Only validated Galithin gear in supported slots. |
| `statistics` | `PlayerStatistics` | Vanilla statistics plus the calculated combat stats shown by `/profile`. |
| `dungeonStatistics.values` | object | Dynamic registered dungeon aggregate values. |
| `abilities` | `ProgressionEntry[]` | Unlocked/selected abilities. |
| `talents` | `ProgressionEntry[]` | Unlocked/selected talents. |

### Player statistics

In addition to advancement, kill, death, join, and play-time values, `PlayerStatistics`
contains the four calculated values from the in-game profile menu:

| Field | Type | Meaning |
|---|---|---|
| `damage` | number | Calculated attack damage. |
| `health` | number | Calculated maximum health. |
| `defense` | number | Calculated defense ratio. Multiply by 100 for the in-game percentage (`0.18` = `18%`). |
| `mana` | number | Calculated maximum mana. |

These values are reconstructed by the API from the existing HuskSync player and
equipment data plus the versioned Core modifier definitions in the content bundle.
They include rolled item values, percentage stacking, custom modifiers, supported
stat trinkets, and active conditional talents. No Core plugin change is required.

### Rank presentation

| Field | Type | Meaning |
|---|---|---|
| `id` | string | LuckPerms primary group ID. |
| `label` | `RichText` | Human-readable group label. |
| `prefix` | `RichText \| null` | Highest-priority inherited LuckPerms prefix. |
| `icon` | `RichText \| null` | Resolved `meta.icon.*` value. |

### Badge presentation

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Badge catalog ID. |
| `slot` | integer | Zero-based selected slot. |
| `name` | `RichText` | Display name. |
| `lore` | `RichText[]` | Display lore. |
| `assets` | `AssetReference \| null` | Optional icon assets. |

### Equipment item

```json
{
  "slot": "MAIN_HAND",
  "itemId": "armory-sword",
  "nexoId": "aether_sword",
  "material": "DIAMOND_SWORD",
  "amount": 1,
  "itemUuid": "a7ae14c3-7746-4352-bd88-cc34999980b3",
  "rarity": "legendary",
  "tier": "5",
  "requiredLevel": 40,
  "durability": 1475,
  "maximumDurability": 1561,
  "repairs": 2,
  "name": {
    "adventureJson": "{\"text\":\"Aether Sword\",\"color\":\"#55ffff\"}",
    "plainText": "Aether Sword",
    "sanitizedHtml": "<span style=\"color:#55ffff\">Aether Sword</span>",
    "sourceMiniMessage": null
  },
  "lore": [],
  "modifiers": {
    "damage.modifier-value": 18.0
  },
  "attributes": {},
  "assets": {
    "catalogVersion": "b1a1e8941d6dcc1f017e48b9",
    "modelUrl": "/assets/b1a1e8941d6dcc1f017e48b9/assets/minecraft/models/custom/gear/swords/aether_sword.json",
    "equipmentUrl": null,
    "textures": {}
  },
  "tooltipStyle": {
    "id": "minecraft:legendary",
    "catalogVersion": "b1a1e8941d6dcc1f017e48b9",
    "backgroundUrl": "/assets/b1a1e8941d6dcc1f017e48b9/assets/minecraft/textures/gui/sprites/tooltip/legendary_background.png",
    "frameUrl": "/assets/b1a1e8941d6dcc1f017e48b9/assets/minecraft/textures/gui/sprites/tooltip/legendary_frame.png"
  }
}
```

| Field | Type | Meaning |
|---|---|---|
| `slot` | enum | `HEAD`, `CHEST`, `LEGS`, `FEET`, `BACKPACK`, `NECKLACE`, `RING`, `MAGNET`, `MAIN_HAND`, or `OFF_HAND`. |
| `itemId` | string | Galithin armory group/item identifier. |
| `nexoId` | string or null | Nexo item ID when saved in PDC. |
| `material` | string | Uppercase Minecraft material without namespace. |
| `amount` | integer | Stack amount. |
| `itemUuid` | string or null | Galithin unique item UUID when present. Legacy rolled items and trinkets may omit it. |
| `rarity`, `tier` | string or null | Saved rarity/tier. |
| `requiredLevel` | integer or null | Required player level. |
| `durability` | integer or null | Remaining or saved durability. |
| `maximumDurability` | integer or null | Maximum durability. |
| `repairs` | integer or null | Repair count. |
| `name`, `lore` | rich text | Actual saved item display components with catalog fallback. |
| `modifiers` | number map | Flattened armory modifier values. |
| `attributes` | JSON object | Complete projected armory attributes for extensible website display. |
| `assets` | asset reference or null | Catalog item-model, wearable-equipment, and texture references. `equipmentUrl` identifies the Minecraft humanoid equipment definition for wearable armor. |
| `tooltipStyle` | tooltip style reference or null | Exact saved hover-lore style with versioned background and frame texture URLs. Rolled gear normally has one; backpacks and trinkets normally do not. |

Vanilla, empty, malformed, unrelated Nexo items, mismatched armor, and hand items outside sword/axe/shield groups are omitted rather than returned as empty slots. Dedicated Core custom slots accept catalogued backpacks and trinkets without rolled-armory group or attribute fields.

### Progression entry

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Ability or talent ID. |
| `group` | string or null | Ability group from Core registry data. |
| `specialization` | string or null | Player's selected group specialization. |
| `unlocked` | boolean | Currently true for every returned entry. |
| `selected` | boolean | Whether the entry/group is selected. |
| `baseLevel` | integer | Stored player level. |
| `equipmentBonus` | integer | Total applicable bonus from equipped gear. |
| `effectiveLevel` | integer | Base plus equipment, capped at maximum. |
| `maximumLevel` | integer | Highest catalog level. |
| `name`, `lore` | rich text | Presentation data rendered for the effective level. |
| `currentValues` | object | Values at effective level. |
| `nextValues` | object or null | Values at the next level. |
| `assets` | asset reference or null | Icon/model assets. |

Returns `404` when no projected player matches the UUID/name.

## Player Open Graph image

```http
GET /api/v1/players/{identifier}/opengraph?v={version}
```

The image endpoint returns a 1600×900 PNG of the existing website render page. The API validates the player and computes a deterministic 16-character equipment hash before it starts Chromium.

An unversioned request redirects with `307 Temporary Redirect` to the current version:

```http
Location: /api/v1/players/Gnarly/opengraph?v=94561fc30feaa4c3
Cache-Control: public, max-age=300
```

A cache miss is rendered by an external Chromium child process, written atomically to persistent storage, and returned with:

```http
Content-Type: image/png
Cache-Control: public, max-age=31536000, immutable
ETag: "og-94561fc30feaa4c3"
```

Only the UUID and visible equipment identity/assets contribute to the version. XP, statistics, and projection timestamps do not. A previously generated historical version remains addressable after equipment changes. An unknown stale version redirects to the current version.

Invalid players, unavailable rendering, exhausted render capacity, and render-specific throttling redirect to the configured static fallback. Throttled responses also include `Retry-After`. Cache hits do not consume the render-specific allowance.

## Player level leaderboard

```http
GET /api/v1/leaderboards/players/level?limit=50&cursor=...&search=Apostolos
```

Query parameters:

| Parameter | Default | Rules |
|---|---:|---|
| `limit` | `50` | Integer, clamped to `1..100`. A non-integer returns `400`. |
| `cursor` | absent | Opaque cursor returned by the previous page. |
| `search` | absent | Case-insensitive name substring, or an exact global position (`42` and `#42` are equivalent). Maximum 64 characters. |
| `around` | absent | Positive global position around which to center the page. Cannot be combined with `search`. |

Ordering:

```text
level DESC, experience DESC, UUID ASC
```

Every row receives a unique global `position` after deterministic tie-breaking.
Search is applied after those positions are calculated, so filtered rows retain their global leaderboard place. Keep the same `search` value when following a `nextCursor`.

To place a player in the context of nearby rankings, first resolve their position with `search`, then request a surrounding window:

```http
GET /api/v1/leaderboards/players/level?around=42&limit=25
```

For this example, the response starts at position 30 and includes positions through 54 when they exist. Near the beginning or end of a leaderboard, the window naturally contains fewer rows on the side where no rankings exist. Use the returned `nextCursor` without `around` to continue scrolling downward from the window.

```json
{
  "data": {
    "entries": [
      {
        "position": 1,
        "uuid": "f84c6a79-2c4f-4f14-93f6-a401fd70c95a",
        "name": "Apostolos",
        "level": 42,
        "experience": 182340,
        "rank": {
          "id": "vanguard",
          "label": {
            "adventureJson": "{\"text\":\"Vanguard\"}",
            "plainText": "Vanguard",
            "sanitizedHtml": "<span>Vanguard</span>",
            "sourceMiniMessage": null
          },
          "prefix": null,
          "icon": null
        },
        "badges": [],
        "guild": null,
        "projectedAt": "2026-08-08T16:40:03.007Z"
      }
    ],
    "nextCursor": "NDI6MTgyMzQwOmY4NGM2YTc5LTJjNGYtNGYxNC05M2Y2LWE0MDFmZDcwYzk1YQ"
  },
  "meta": {
    "apiVersion": "v1",
    "catalogVersion": "b1a1e8941d6dcc1f017e48b9",
    "sourceSavedAt": "2026-08-08T16:40:02.123Z",
    "projectedAt": "2026-08-08T16:40:03.007Z",
    "stale": false
  }
}
```

Excluded accounts are omitted. Do not decode or construct cursors; their representation is not part of the public contract.

## Get a guild profile

```http
GET /api/v1/guilds/{identifier}
```

`identifier` accepts a guild UUID, display name, or tag, all case-insensitive except the UUID's canonical value. If one guild's name and another guild's tag are identical, the name match takes precedence. Leaderboard clients should use the guild UUID for stable links.

Example response data:

```json
{
  "id": "0688465b-38da-4fd2-8edf-bf61fcd64630",
  "name": "Philosophers",
  "tag": "THNK",
  "ownerUuid": "f84c6a79-2c4f-4f14-93f6-a401fd70c95a",
  "progression": {
    "level": 12,
    "currentExperience": 640,
    "requiredExperience": 4652,
    "experienceToNextLevel": 4012,
    "totalExperience": 18420,
    "maximumLevel": 100
  },
  "memberCapacity": { "used": 8, "limit": 15 },
  "claimCapacity": { "used": 3, "limit": 8 },
  "members": [
    {
      "uuid": "f84c6a79-2c4f-4f14-93f6-a401fd70c95a",
      "name": "Apostolos",
      "role": "owner",
      "level": 42,
      "rank": null,
      "badges": [],
      "lastSeenAt": "2026-08-08T16:40:02.123Z",
      "totalContribution": 9200,
      "profileAvailable": true
    }
  ],
  "projectedAt": "2026-08-08T16:40:03.007Z"
}
```

Members are ordered by role (`owner`, `mod`, `member`), contribution descending, then name/UUID. `level`, `rank`, badges, and last-seen time come from the member's projected public player profile. They are nullable when no player profile is available; `profileAvailable` tells the website whether the player detail route can be opened. `name` falls back to LuckPerms identity data.

Capacity `limit` is `null` when Core imposes no cap at the current level. The endpoint intentionally omits claim coordinates, raw guild activity logs, invite data, and energy state.

Progression requirements and level-based member/claim limits are calculated from the active content bundle generated from production `plugins/galithin-core/guild.yml`. A content-bundle hot swap updates these values without an API configuration change.

## Guild level leaderboard

```http
GET /api/v1/leaderboards/guilds/level?limit=50&cursor=...&search=Philosophers
```

The player leaderboard query parameters and position search behavior also apply here. Text searches are case-insensitive and match a guild name or tag by substring. An exact player name returns that player's guild leaderboard entry. The response shape is unchanged for every search type.

Ordering:

```text
level DESC, totalExperience DESC, guild UUID ASC
```

Guild row fields:

| Field | Type | Meaning |
|---|---|---|
| `position` | integer | Unique global position. |
| `id` | string | Guild UUID. |
| `name` | string | Display name. |
| `tag` | string or null | Display tag. |
| `level` | integer | Guild level. |
| `totalExperience` | integer | Total guild experience. |
| `ownerUuid` | string | Owner UUID. |
| `memberCount` | integer | Owner plus current member rows. |
| `projectedAt` | string | Guild projection timestamp. |

Inactive/disbanded guilds are excluded.

## Catalog

```http
GET /api/v1/catalog/{kind}
```

Accepted kinds:

- `items` — web-visible gear only;
- `abilities`;
- `talents`;
- `badges`;
- `ranks`;
- `guilds` — guild progression costs, maximum level, and level reward limits from Core `guild.yml`.

Catalog entry fields:

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Stable catalog ID. |
| `kind` | enum | `ITEMS`, `ABILITIES`, `TALENTS`, `BADGES`, or `RANKS`. |
| `name`, `lore` | rich text | Presentation. |
| `group` | string or null | Progression group. |
| `maximumLevel` | integer or null | Progression maximum. |
| `levels` | object | Numeric level keys to arbitrary value objects. |
| `assets` | asset reference or null | Model and textures. |
| `metadata` | object | Extensible metadata, such as `material` or `iconItemId`. |

The `guilds` catalog is a single object rather than a `CatalogEntry[]`. It contains `baseExperienceCost`, `experienceIncreasePerLevel`, `maximumLevel`, and a `rewards` map keyed by unlock level. Each reward exposes nullable `maximumMembers` and `maximumClaims` values.

The website should cache catalogs by `meta.catalogVersion` and reuse them when rendering profiles.

After a content hot swap, a stored profile can temporarily contain an older `assets.catalogVersion` until the next source snapshot or full reconciliation. When it differs from response `meta.catalogVersion`, prefer the matching current catalog entry's assets.

## Assets

```http
GET /assets/{catalogVersion}/{path}
```

Use URLs supplied by `AssetReference`; do not guess pack paths. Only the currently active catalog version resolves. An old version returns `404` after the API swaps to a newer bundle, so clients should refresh catalog/profile JSON before requesting missing assets.

Asset traversal (`..`), blank paths, missing files, and catalog-version mismatches return `404`.

Common asset types include JSON model/item/equipment/font definitions, PNG textures, and `.png.mcmeta` animation metadata.

## Rich text

Every rich name/lore line has:

| Field | Meaning |
|---|---|
| `adventureJson` | Canonical Adventure component JSON. |
| `plainText` | Search/accessibility fallback without shift markers. |
| `sanitizedHtml` | Immediately renderable escaped HTML with safe inline styles/glyph nodes. |
| `sourceMiniMessage` | Original source when the value came from MiniMessage; otherwise `null`. |

See [Rich text](rich-text.md) before inserting `sanitizedHtml` into a website.

## Health

### Liveness

```http
GET /health/live
```

```json
{ "status": "up" }
```

Liveness confirms that the HTTP process can answer. It does not check databases or projection.

### Readiness

```http
GET /health/ready
```

```json
{
  "status": "ready",
  "stale": false,
  "catalogVersion": "b1a1e8941d6dcc1f017e48b9",
  "projectedAt": "2026-08-08T16:40:03.007Z",
  "detail": null
}
```

Returns `200` when the content catalog is valid and at least one profile exists. Otherwise it returns `503` and `status: not_ready`. A stale source may still be ready when last-known projection data exists.

## Metrics

```http
GET /metrics
Authorization: Bearer <valid-api-key>
```

Returns Prometheus text format. See [Operations](operations.md) for custom and library-provided metrics.

## Errors

Error bodies follow this shape:

```json
{
  "type": "https://galithin.com/problems/404",
  "title": "Not found",
  "status": 404,
  "detail": "Player 'Unknown' was not found",
  "instance": "/api/v1/players/Unknown"
}
```

| Status | Typical causes |
|---:|---|
| `400` | Blank identifier, invalid limit/cursor, unknown catalog kind. |
| `401` | Invalid bearer header/key, or no key on `/metrics`. |
| `404` | Unknown player, asset/version/path, or route. |
| `429` | Token bucket exhausted; includes `Retry-After: 60`. |
| `500` | Unexpected read/serialization failure. Public detail is intentionally generic. |
| `503` | Readiness prerequisites are not met. |
