# Content bundle

The content bundle separates presentation data from the API JAR. Gear models, textures, glyphs, names, lore, and progression definitions can be updated by replacing one versioned ZIP.

## Generate a bundle

```bash
./gradlew :content-tooling:buildBundle \
  -PgalithinRoot=/path/to/GalithinServer \
  -Poutput=/path/to/content/galithin-web-content.zip
```

Required source layout:

```text
GalithinServer/
├── galithin-core/
└── plugins/
    ├── galithin-core/
    └── Nexo/
        ├── glyphs/
        ├── items/
        └── pack/pack.zip
```

The generator writes a temporary ZIP beside the destination and moves it into place atomically. If the filesystem does not support atomic moves, it uses a replace move.

## Included catalogs

The bundle contains:

```text
catalog/items.json
catalog/abilities.json
catalog/talents.json
catalog/badges.json
catalog/ranks.json
catalog/glyphs.json
catalog/guilds.json
```

### Guild progression

`guilds.json` is generated from production `plugins/galithin-core/guild.yml`. It contains the public level-cost inputs, maximum guild level, and level-based member/claim limits. The guild profile endpoint uses this same active bundle data to calculate required XP and capacities, and the website can fetch it through `/api/v1/catalog/guilds`.

Raw activity, energy, invites, permissions, and claim locations are not content-bundle data and are not exposed.

### Items

`items.json` intentionally contains only gear that can appear in public equipment:

- armor from `Nexo/items/gear/armors/armors.yml`, excluding hidden helper entries;
- axes;
- magnets;
- shields;
- swords;
- only backpacks referenced by Core `backpacks.yml`;
- only trinkets referenced by Core `trinkets/trinkets.yml`.

Ability/talent/badge Nexo definitions are loaded as build dependencies so their public catalogs receive assets, but they are not duplicated into the generic item catalog.

Rolled armory gear also carries its matching Core group and the Damage, Health,
Defense, and Mana modifier definitions from `plugins/galithin-core/armory/modifiers`.
The API combines this versioned metadata with the roll values already stored in
HuskSync item PDC. The same metadata carries Core's armory level bands so
level-ineligible gear does not contribute stats. Core does not need to publish a
separate web projection.

### Explicit exclusions

The production bundle does not copy the complete resource pack. It excludes:

- blocks and carpentry content;
- furniture and props;
- sounds;
- BetterHUD assets/fonts;
- BetterModel and ModelEngine data;
- unrelated cosmetics and general Nexo items;
- backpack upgrades;
- unidentified mystery bags;
- unrelated menu inventories.

This keeps the production ZIP small and makes accidental scope growth visible.

Ability lore comes from Core's unlocked specialization menus, including the configured stats, cooldown, mana cost, and other level-dependent values. The broad selection menu remains a fallback for abilities without a detailed unlocked entry. Talent lore uses the equivalent level-aware templates from the talent selection menu.

## Asset dependency closure

For every selected definition, the generator follows:

- `Pack.model` model JSON;
- `Pack.texture`/`Pack.textures` PNGs;
- modern `Components.item_model` item JSON;
- nested model references inside modern item definitions;
- recursive model `parent` and `overrides[].model` references;
- model texture references and optional `.png.mcmeta` animation data;
- `Components.equippable.asset_id` equipment JSON, exposed per catalog item as `assets.equipmentUrl`;
- player `humanoid` and `humanoid_leggings` armor textures.
- tooltip background/frame sprites configured by Core's armory rarity styles, including their nine-slice `.mcmeta` files.

Vanilla parent models such as `minecraft:item/generated` may be absent from the custom pack and are treated as client built-ins. Missing custom models/textures fail generation.

## Glyphs and fonts

All existing textures referenced by Nexo glyph YAML are included. The generated `glyphs.json` stores:

```json
{
  "id": "gear_legendary",
  "codePoint": 42113,
  "assetPath": "assets/minecraft/textures/custom/gear/badges/legendary.png",
  "width": 9,
  "height": 9,
  "ascent": 7
}
```

The exact numeric code point is content-dependent. The bundle also includes Nexo `default.json` and `shift.json` font definitions plus textures referenced by those font bitmap providers.

An orphan glyph definition whose PNG is absent cannot be published. The generator currently warns and skips such a mapping. If catalog MiniMessage actually uses the missing named glyph, rich-text generation fails rather than publishing broken HTML.

## Manifest and version

`manifest.json` contains:

```json
{
  "schemaVersion": 1,
  "catalogVersion": "b1a1e8941d6dcc1f017e48b9",
  "createdAt": "2026-08-08T17:02:31.529Z",
  "files": {
    "catalog/items.json": "<sha256>",
    "assets/minecraft/textures/...png": "<sha256>"
  }
}
```

The version is the first 24 hexadecimal characters of a SHA-256 over the content-transform revision, selected source configuration (including `guild.yml`), and included assets. Unrelated resource-pack content does not affect it. The transform revision changes whenever generator behavior alters catalog output, preventing corrected content from reusing an immutable cache namespace.

Every payload file except `manifest.json` is listed with its SHA-256 digest.

## Runtime validation and hot reload

At startup the API:

1. extracts the ZIP to a temporary directory, or uses a configured directory directly;
2. requires manifest schema version `1`;
3. validates every declared file path and checksum;
4. deserializes all known catalogs and glyphs;
5. atomically swaps the active in-memory snapshot.

The API checks the bundle file modification time every two seconds. A valid replacement becomes active without restarting the JAR. An invalid replacement is logged and the previous valid bundle remains active.

When the API closes, temporary extracted directories are removed.

## Deploy an update

Generate the ZIP outside the live API directory when possible, validate it, then replace the configured path in one move:

```bash
mv galithin-web-content.zip.new galithin-web-content.zip
```

After replacement:

1. watch logs for `Loaded web content bundle <version>`;
2. check `/health/ready` for the new `catalogVersion`;
3. request `/api/v1/catalog/items` and one supplied asset URL;
4. purge any HTML/API edge cache if it ignores origin cache headers.

Assets are immutable by version, but the server only exposes assets for the active version. The website should refresh the catalog and resolve the current entry when an old asset URL returns `404`.

Catalog data swaps immediately. Asset references serialized inside existing `web_profiles` are refreshed only when that player receives a new snapshot or during the next full reconciliation (five minutes by default). During this transition, prefer the current catalog's assets by item/ability/talent ID whenever an embedded `assets.catalogVersion` differs from response `meta.catalogVersion`.

## Add new gear

Place the Nexo item in one of the selected gear files, or reference the backpack/trinket ID from the corresponding Core configuration. Ensure every custom model/texture exists in `pack.zip`, regenerate the bundle, and confirm the item appears in `catalog/items.json`.

## Add an ability or talent

Add the Core progression definition and menu presentation, add/update the Nexo icon definition, regenerate the bundle, and verify:

- the catalog ID is unique;
- every numeric level resolves;
- menu placeholders resolve;
- MiniMessage parses;
- the icon asset is present;
- `maximumLevel`, `levels`, group, name, and lore are correct.

The runtime profile decoder automatically uses the updated catalog when the player PDC contains the matching ID.
