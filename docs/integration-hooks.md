# In-game freshness hooks

The API can project any snapshot already written by HuskSync without plugin changes. Two limited hooks improve how quickly web-visible state reaches HuskSync.

These hooks do not add Core database tables, identity migrations, API endpoints, or alternate persistence systems.

## Core web profile sync coordinator

Location:

```text
galithin-core/spigot/src/main/java/com/galithin/core/spigot/feature/web/WebProfileSyncCoordinator.java
```

The coordinator listens for web-visible changes and requests an existing `SyncUtils.save(player)` HuskSync save.

### Events

- inventory click;
- inventory drag;
- held hotbar slot change;
- hand swap;
- armor equip change;
- Galithin custom armory equip;
- Galithin custom armory unequip;
- advancement completion;
- player kills, mob kills, and deaths.

### Coalescing and protection

- Two-second trailing debounce (`40` ticks).
- At most one explicit snapshot every five seconds per player.
- SHA-256 fingerprint prevents saves when web-visible equipment/PDC state is unchanged.
- Pending tasks are cancelled when the player quits or Core disables.

The fingerprint covers:

- four armor contents;
- main hand;
- off hand;
- serialized player persistent data container.

It deliberately does not poll or write a new Core identity schema.

### Lifecycle

`CorePlugin` creates and registers the coordinator during enable and closes it during disable.

## Dungeons run-end save

Location:

```text
galithin-dungeons/src/main/java/com/galithin/dungeon/listener/metrics/DungeonLifecycleMetricsListener.java
```

When a player leaves a dungeon, the listener applies the final outcome statistics and then calls `SyncUtils.save(player)` once.

It does not save after every mob, ore, chest, room, or soul-flame event. This keeps HuskSync/database write load bounded while making accumulated run totals visible shortly after the run ends.

## Expected freshness

Typical path for a gear change:

```text
game event
→ 2-second trailing debounce
→ up to 5-second per-player save floor
→ HuskSync database commit
→ projector poll (default 2 seconds)
→ profile cache expiry (default 5 seconds)
```

The exact visible latency depends on where the event occurs within those windows. Browser/CDN caching can add another delay unless it revalidates with ETags.

Dungeon totals follow the same path after the single leave save.

## Verification

After deploying the hooks:

1. equip/change a custom gear item;
2. confirm a new HuskSync version row appears;
3. confirm projection lag returns toward normal;
4. request the profile after the cache TTL;
5. verify the correct equipment slot and item UUID.

For dungeons, compare the latest HuskSync timestamp before and after leaving a run.

## Compatibility

The hooks depend on the existing Galithin `SyncUtils.save` integration and Bukkit/armory events. If that save abstraction or event types change, compile Core/Dungeons and perform the freshness checks above before deployment.
