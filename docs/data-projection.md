# Data projection

The background projector converts plugin-owned data into the API-owned read model. This keeps expensive decompression, SNBT parsing, and PDC interpretation out of HTTP requests.

## Projection cycle

Each cycle:

1. requires a valid active content catalog;
2. refreshes LuckPerms identities and Core guilds;
3. synchronizes active guild rows, retiring guilds no longer present;
4. either performs the initial player backfill or polls after the saved checkpoint;
5. periodically reconciles every player's latest snapshot;
6. sleeps for `projection.pollIntervalMillis`.

Failures are recorded in `web_projection_state` and retried after the poll delay.

## Initial backfill

When no `husksync-v4` checkpoint exists, the projector queries the latest HuskSync row for every player using a window function ordered by:

```text
timestamp DESC, version_uuid DESC
```

Rows are paged by player UUID using `LIMIT/OFFSET` and `projection.batchSize`. The latest snapshot is authoritative for each account. If it has no usable Core sync container, the projector merges missing data from older retained snapshots in descending timestamp/version order, matching the legacy API's behavior. After the backfill, the globally newest `(timestamp, version_uuid)` becomes the incremental checkpoint.

The number of processed players is exposed as `galithin_projection_backfill_players`.

## Incremental polling

Polling uses a composite key:

```text
(timestamp, version_uuid)
```

The query accepts rows where the timestamp is newer, or where the timestamp matches and the version UUID compares greater. Results are processed in ascending composite order.

This deterministic tie-breaker prevents snapshots with equal timestamps from being skipped during normal polling.

## Reconciliation

At `projection.reconciliationIntervalSeconds`, the projector scans every player's current latest snapshot again. Upserts are idempotent. Reconciliation catches late database commits and rows that would otherwise fall behind the monotonic checkpoint.

## HuskSync format

The initial compatibility target is HuskSync v4 packed format 5:

1. Snappy-decompress the database `data` blob.
2. Parse the packed JSON object.
3. Require `formatVersion`/`format_version` to equal `5`.
4. Read textual entries from its `data` map.
5. Parse relevant SNBT and JSON sections.

Minecraft text components can contain mixed string/compound `extra` lists that strict typed-SNBT readers reject. The decoder normalizes those lists before parsing inventory and custom equipment, and can independently recover the Core `sync` compound if unrelated persistent data is malformed.

Used sections include:

| HuskSync data key | Projected information |
|---|---|
| `persistent_data` | level/XP, custom equipment, badges, abilities, talents, profile combat stats, dungeon statistics |
| `inventory` | armor, selected hotbar hand, off hand |
| `statistics` | kills, deaths, play time |
| `advancements` | completed advancement count |

An unsupported format raises a decode failure and does not overwrite that player's previous valid profile. The failed row is retried during reconciliation. Monitor decode-failure metrics after any HuskSync upgrade.

## Player identity

The player name comes from the HuskSync user row, which retains the casing supplied by the online Minecraft player. LuckPerms remains the fallback when HuskSync has no usable name because its stored username can be normalized to lowercase.

First/last joined values use:

- HuskSync's earliest snapshot timestamp for `firstJoinedAt`;
- the projected snapshot timestamp for `lastJoinedAt`.

LuckPerms currently supplies no joined timestamps or ban state to this read-only adapter.

## Rank presentation

The LuckPerms adapter reads:

- `${tablePrefix}players`;
- `${tablePrefix}group_permissions`;
- `${tablePrefix}user_permissions`.

It follows positive, unexpired `group.*` inheritance nodes, accepts `global` or the configured server context, and limits world context to `global`. It selects:

- `rank.id`: the stored primary group;
- `rank.label`: a humanized primary group ID;
- `rank.prefix`: the highest numeric `prefix.<priority>.<value>` node;
- `rank.icon`: the last resolved `meta.icon.<value>` node.

This avoids modifying Core. It is a SQL reconstruction and may differ from a live LuckPerms query when complex contexts, weights, transient nodes, or plugin-specific resolution rules are involved.

## Guild projection

Core guild data is read from:

- `guild_data`;
- `guild_name_index`;
- `guild_tag_index`;
- `guild_progression`;
- `guild_members`;
- `guild_member_contributions`;
- `guild_claims` (count only).

The owner and member rows are materialized in `web_guild_members` with role and total contribution. Public player profiles are joined at guild-read time to add each member's current name, level, rank, badges, and last-seen timestamp. Owners and members are also mapped to a `GuildSummary` in player profiles.

Current XP and raw capacity bonuses come from Core. Level costs and base member/claim limits come from the active content bundle generated from production `guild.yml`, so those public values hot-swap with the rest of the catalog. Claim coordinates, activity logs, invite data, and energy state are not projected.

Each refresh marks all existing `web_guilds` rows inactive, then upserts the currently returned guilds as active in one transaction. Disbanded guilds therefore disappear from the public leaderboard without deleting historical rows.

Guild ordering is:

```text
level DESC, total_experience DESC, guild UUID ASC
```

## Equipment extraction and validation

Returned slots are:

```text
HEAD, CHEST, LEGS, FEET, BACKPACK, NECKLACE, RING, MAGNET, MAIN_HAND, OFF_HAND
```

Vanilla inventory slots are mapped as follows:

| Inventory slot | API slot |
|---:|---|
| `36` | `FEET` |
| `37` | `LEGS` |
| `38` | `CHEST` |
| `39` | `HEAD` |
| `40` | `OFF_HAND` |
| `held_item_slot` | `MAIN_HAND` |

Custom equipment is read from the synchronized `custom_armory_items` PDC container (`backpack`, `necklace`, `ring`, and `magnet`).

Inventory armor and weapons require a non-air material and the Core
`armory-item-attributes` marker. When a saved item also has an armory `group`,
the group must begin with `armory-`. Every rolled item must also match its API
slot: helmets in `HEAD`, chestplates in `CHEST`, leggings in `LEGS`, boots in
`FEET`, swords or axes in `MAIN_HAND`, and shields in `OFF_HAND`. The decoder
uses catalog metadata or the Nexo ID to enforce the same rule for legacy items
without a saved group. Group and item UUID remain optional because legacy
rolled items do not consistently contain them.

Dedicated custom slots use a different contract: backpacks and trinkets are
Nexo items owned by Core's `custom_armory_items` container and intentionally do
not have rolled-armory attributes or groups. They require a non-air material,
a Nexo ID, and an exact match in the public item catalog. Catalog slot metadata
must also match the container: backpacks only appear in `BACKPACK`, ring and
necklace trinkets only appear in their configured `RING` or `NECKLACE` slot,
and magnets only appear in `MAGNET`. The catalog is the allowlist that removes
malformed, misplaced, and unrelated Nexo items.

Names and lore prefer the actual modern item components saved by HuskSync. Catalog definitions are the fallback and provide models/textures. When the saved item has a `minecraft:tooltip_style`, the API returns its exact resource key plus versioned background and frame URLs. A style is omitted when either sprite is absent from the active content bundle, so stale or unknown keys do not produce broken asset references.

## Statistics

Player statistics include:

- completed advancement count;
- `player_kills`;
- `deaths`;
- first/last joined timestamps;
- `play_one_minute / 20` (or its newer `play_time` name) as play time in seconds;
- calculated damage, maximum health, defense, and maximum mana reconstructed from
  synchronized player attributes and equipped-item rolls.

HuskSync 4 serializes untyped Bukkit statistics inside the `generic` object.
The decoder also accepts the older `genericStatistics` and `generic_statistics`
container names so retained snapshots remain projectable after upgrades.

The active content bundle contains the four relevant definitions from Core's
`armory/modifiers` YAML files. The projector combines those definitions with each
item's saved roll and modifier order, then applies Core's stacking policies. It
also evaluates the supported health-based talents from saved health and talent
metadata and applies the `life-core` and `mana-heart` trinkets. Custom modifier
definitions are read directly from item PDC. Broken items and items outside the
content bundle's Core level bands do not contribute. Defense remains a ratio
(`0.18` is rendered as `18%`). No Core plugin change or new HuskSync snapshot
format is needed.

Dungeon statistics are projected generically from the synchronized `player_statistic_data.statistics` container. Each registered statistic name becomes a key in `dungeonStatistics.values`, so new aggregates can appear without adding fixed v1 fields.

## Abilities and talents

The decoder combines:

- synchronized player metadata and selection state;
- catalog group, levels, names, lore, and assets;
- equipment-provided ability-level bonuses.

Only unlocked/leveled or selected entries are returned. Effective level is:

```text
min(baseLevel + equipmentBonus, maximumLevel)
```

`currentValues` uses the effective-level catalog definition. `nextValues` is absent at maximum level or when no next definition exists.
For linked ability groups, only the specialization selected in Core is returned; sibling holders merely share its level and are not separate unlocks. Progression lore is rendered with the effective level's values, matching the in-game profile menu.

## Badges

Up to the first three selected badge IDs are returned in selection order. Missing catalog definitions fall back to a plain-text ID.

## Eligibility

Player leaderboard eligibility is false when the lowercase primary group, UUID, or current name is present in configuration exclusions. Profiles remain publicly queryable.

The current read-only identity adapter does not source bans. Explicitly exclude banned accounts by UUID/name or by a dedicated excluded LuckPerms group.

## Freshness metadata

Every API envelope contains:

- `sourceSavedAt`: newest projected HuskSync source timestamp known by read-model status;
- `projectedAt`: newest projection timestamp;
- `stale`: whether a profile or projection source is marked stale;
- `catalogVersion`: active content catalog version.

`/health/ready` requires at least one projected profile and a valid catalog. It may remain ready with `stale: true` while serving last-known data after a source failure.
