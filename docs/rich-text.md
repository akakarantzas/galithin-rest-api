# Rich text and website integration

Minecraft text is not plain HTML. The API provides four representations so the website can choose immediate rendering, canonical client-side rendering, or accessible/search text.

## RichText contract

```json
{
  "adventureJson": "{\"text\":\"Aether Sword\",\"color\":\"#55ffff\",\"bold\":true}",
  "sourceMiniMessage": "<bold><#55ffff>Aether Sword</#55ffff></bold>",
  "plainText": "Aether Sword",
  "sanitizedHtml": "<span style=\"color:#55ffff;font-weight:bold\">Aether Sword</span>"
}
```

| Representation | Recommended use |
|---|---|
| `adventureJson` | Canonical storage, advanced client-side rendering, interaction metadata. |
| `sourceMiniMessage` | Debugging/content tooling; may be `null` for saved item components. |
| `plainText` | Accessibility labels, indexing, OpenGraph text, no-script fallback. |
| `sanitizedHtml` | Immediate visual rendering on the Galithin website. |

## Sanitized HTML guarantees

The renderer creates HTML from parsed Adventure components; it never passes source text through as HTML.

It escapes:

- `&`;
- `<` and `>`;
- double and single quotes.

It emits only controlled `span` and glyph `img` nodes. Supported styles include safe colors, font identifiers, bold, italic, underline, strikethrough, obfuscated blur, and text shadows.

Color values must be a safe named color or `#RRGGBB`/`#RRGGBBAA`. Font identifiers must match a restricted identifier pattern.

The result is intended for website display, but the website should still enforce its normal Content Security Policy. Do not concatenate separate untrusted HTML around the value.

Example in React:

```tsx
type RichText = {
  adventureJson: string;
  plainText: string;
  sanitizedHtml: string;
  sourceMiniMessage: string | null;
};

export function MinecraftText({ value }: { value: RichText }) {
  return (
    <span
      aria-label={value.plainText}
      dangerouslySetInnerHTML={{ __html: value.sanitizedHtml }}
    />
  );
}
```

Only use `dangerouslySetInnerHTML` with `sanitizedHtml` returned by this API, not `sourceMiniMessage` or arbitrary user input.

## Glyph rendering

Known Nexo glyph characters become:

```html
<img
  class="galithin-glyph"
  alt="gear_legendary"
  src="/assets/{catalogVersion}/assets/minecraft/textures/custom/gear/badges/legendary.png"
  width="9"
  height="9"
  style="vertical-align:-2px">
```

The image URL includes the active catalog version. Width, height, and baseline offset come from the glyph catalog/configuration.

Named MiniMessage syntax such as `<glyph:gear_legendary>` is replaced only when the name exists in the active registry. Unknown named glyphs fail content generation instead of becoming arbitrary markup.

The website can style glyphs without changing individual inline dimensions:

```css
.galithin-glyph {
  display: inline-block;
  image-rendering: pixelated;
  max-width: none;
}
```

## Shift glyphs

`<shift:N>` values are clamped to `-128..128` pixels and rendered as controlled spacing nodes. Raw private-use space characters declared by Nexo's bundled `shift.json` are converted to the same nodes, including Nexo's fixed `-512..512` pixel advances:

```html
<span
  aria-hidden="true"
  class="galithin-shift"
  style="display:inline-block;width:-4px"></span>
```

Shift markers are removed from `plainText` so screen readers and search indexes do not encounter private-use characters.

## Adventure JSON

The API uses Adventure Gson JSON as the canonical representation. A custom website renderer can parse it to support richer behavior. The supplied HTML currently renders component text/translate values and style inheritance. Client-side code that relies on click/hover events should read `adventureJson`; those interactions are not emitted as active arbitrary HTML attributes.

Malformed saved Adventure JSON falls back to plain text rather than failing the complete profile projection.

## Item models and textures

`AssetReference` is:

```json
{
  "catalogVersion": "b1a1e8941d6dcc1f017e48b9",
  "modelUrl": "/assets/b1a1e8941d6dcc1f017e48b9/assets/minecraft/models/custom/gear/swords/aether_sword.json",
  "equipmentUrl": null,
  "textures": {
    "layer0": "/assets/b1a1e8941d6dcc1f017e48b9/assets/minecraft/textures/custom/gear/magnets/magnet.png"
  }
}
```

`modelUrl` may refer to a legacy model JSON or modern item-definition JSON depending on the Nexo item. `equipmentUrl` points to the modern Minecraft equipment definition used by wearable armor; it is separate because chestplates, leggings, and boots commonly have no standalone item model. The equipment definition selects the `humanoid` or `humanoid_leggings` texture layers included in the same bundle. Helmets with custom head geometry and hand-held swords, axes, or shields continue to use `modelUrl`.

The website is responsible for rendering supplied Minecraft models/textures. The API does not return pre-rendered thumbnails.

Recommended client behavior:

1. treat URLs as opaque;
2. cache resources by `catalogVersion`;
3. resolve `#texture` indirections and model parents when rendering JSON models;
4. resolve `equipmentUrl` layer texture resource locations under the same versioned `/assets/{catalogVersion}/assets/{namespace}/textures/entity/equipment/` path;
5. respect `.png.mcmeta` if animated textures are supported;
6. use a catalog texture directly for an inventory/icon fallback when `modelUrl` is absent;
7. refresh catalog JSON and resolve the current entry if an old-version asset returns `404`.

Profiles are materialized JSON and may briefly retain the previous asset version after a catalog hot swap. If `item.assets.catalogVersion` differs from response `meta.catalogVersion`, resolve the current `nexoId`/progression ID from the matching catalog and use that catalog entry's assets. Full reconciliation updates stored profiles within the configured reconciliation interval.

## Fonts

The bundle exposes Nexo default and shift font provider JSON. The HTML renderer names Adventure fonts in `font-family`, but Minecraft bitmap fonts are not automatically web fonts. Glyph bitmap providers are rendered as images. For normal Minecraft text, the website should load its chosen Minecraft-compatible web font through CSS.

Example:

```css
.minecraft-text {
  font-family: Minecraft, monospace;
  font-synthesis: none;
}
```

## Accessibility

- Use `plainText` for labels when the visual HTML is decorative.
- Keep useful glyph `alt` text or replace it with a localized human-readable label.
- Shift nodes are already `aria-hidden`.
- Do not communicate rarity or state only through color/glyph shape.
- Preserve sufficient website contrast even when source colors were designed for Minecraft's dark UI.

## Licensing

The HTML renderer adapts the render-hook approach from PaperMC Adventure WebUI under the MIT License. The notice is retained in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and included in the executable JAR.
