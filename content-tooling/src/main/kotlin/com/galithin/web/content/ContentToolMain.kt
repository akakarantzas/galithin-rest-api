package com.galithin.web.content

import com.galithin.web.domain.AssetReference
import com.galithin.web.domain.CatalogEntry
import com.galithin.web.domain.CatalogKind
import com.galithin.web.domain.CatalogManifest
import com.galithin.web.domain.GuildCatalog
import com.galithin.web.domain.GuildLevelReward
import com.galithin.web.sources.text.GlyphDefinition
import com.galithin.web.sources.text.GlyphRegistry
import com.galithin.web.sources.text.RichTextRenderer
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.Yaml

fun main(args: Array<String>) {
    val options = Arguments.parse(args)
    ContentBundleBuilder(options.galithinRoot).build(options.output)
}

private data class Arguments(val galithinRoot: Path, val output: Path) {
    companion object {
        fun parse(args: Array<String>): Arguments {
            fun value(name: String): String? {
                val index = args.indexOf(name)
                return if (index >= 0) args.getOrNull(index + 1) else null
            }
            val root = value("--galithin-root")?.let(Path::of)
                ?: error("Usage: --galithin-root <path> [--output <zip>]")
            val output = value("--output")?.let(Path::of)
                ?: Path.of("build/content/galithin-web-content.zip")
            return Arguments(root.toAbsolutePath().normalize(), output.toAbsolutePath().normalize())
        }
    }
}

private class ContentBundleBuilder(private val root: Path) {
    private val plugins = root.resolve("plugins")
    private val coreConfig = plugins.resolve("galithin-core")
    private val coreSource = root.resolve("galithin-core")
    private val nexo = plugins.resolve("Nexo")
    private val yaml = Yaml()
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun build(output: Path) {
        require(coreConfig.isDirectory()) { "Missing Core configuration: $coreConfig" }
        require(nexo.isDirectory()) { "Missing Nexo configuration: $nexo" }
        val selection = selectNexoContent()
        val stage = Files.createTempDirectory("galithin-content-build-")
        try {
            extractPack(stage, selection.definitions.values)
            val version = sourceHash(stage, selection.sourceFiles)
            val glyphDefinitions = readGlyphs(stage)
            val renderer = RichTextRenderer({ version }) { GlyphRegistry(glyphDefinitions) }
            val nexoItems = readNexoItems(
                stage,
                renderer,
                version,
                selection.definitions,
                selection.armoryGroups,
                selection.equipmentSlots,
                readArmoryModifierDefinitions(),
                readArmoryLevelBands(),
            )
            val groups = readAbilityGroups()
            val abilities = readProgression(
                CatalogKind.ABILITIES,
                coreConfig.resolve("abilities"),
                coreConfig.resolve("menu/abilities/abilities-select-menu-new.yml"),
                (1..3).map { count ->
                    coreConfig.resolve("menu/abilities/ability-specialize-unlocked-$count.yml")
                },
                nexoItems,
                groups,
                renderer,
                version,
            )
            val talents = readProgression(
                CatalogKind.TALENTS,
                coreConfig.resolve("talents"),
                coreConfig.resolve("menu/talents/talents-select-menu.yml"),
                emptyList(),
                nexoItems,
                groups,
                renderer,
                version,
            )
            val badges = readBadges(nexoItems, renderer)
            val ranks = readRanks(renderer)

            val catalogDir = stage.resolve("catalog")
            Files.createDirectories(catalogDir)
            writeEntries(
                catalogDir.resolve("items.json"),
                selection.publicGearIds.map { id ->
                    requireNotNull(nexoItems[id]) { "Selected web gear '$id' has no Nexo definition" }
                },
            )
            writeEntries(catalogDir.resolve("abilities.json"), abilities)
            writeEntries(catalogDir.resolve("talents.json"), talents)
            writeEntries(catalogDir.resolve("badges.json"), badges)
            writeEntries(catalogDir.resolve("ranks.json"), ranks)
            Files.writeString(
                catalogDir.resolve("glyphs.json"),
                json.encodeToString(ListSerializer(GlyphDefinition.serializer()), glyphDefinitions),
            )
            Files.writeString(
                catalogDir.resolve("guilds.json"),
                json.encodeToString(GuildCatalog.serializer(), readGuildCatalog()),
            )

            val files = checksumFiles(stage)
            val manifest = CatalogManifest(1, version, Instant.now().toString(), files)
            Files.writeString(stage.resolve("manifest.json"), json.encodeToString(manifest))
            Files.createDirectories(output.parent)
            writeZip(stage, output)
            println("Built ${output.fileName} (${files.size} files, catalog $version)")
        } finally {
            deleteRecursively(stage)
        }
    }

    private data class NexoSelection(
        val definitions: Map<String, Map<String, Any?>>,
        val publicGearIds: List<String>,
        val sourceFiles: Set<Path>,
        val armoryGroups: Map<String, String>,
        val equipmentSlots: Map<String, String>,
    )

    private fun selectNexoContent(): NexoSelection {
        val definitions = linkedMapOf<String, Map<String, Any?>>()
        val publicGearIds = linkedSetOf<String>()
        val sourceFiles = linkedSetOf<Path>()
        val armoryGroups = linkedMapOf<String, String>()
        val equipmentSlots = linkedMapOf<String, String>()

        fun include(
            file: Path,
            publicIds: Set<String>? = null,
            publicUnlessHidden: Boolean = false,
            armoryGroup: (String) -> String? = { null },
            equipmentSlot: (String) -> String? = { null },
        ) {
            require(file.isRegularFile()) { "Missing selected Nexo content file: $file" }
            sourceFiles.add(file)
            loadMap(file).forEach { (id, raw) ->
                val value = raw.asMap()
                if (publicIds != null && id !in publicIds) return@forEach
                require(definitions.put(id, value) == null) { "Duplicate selected Nexo item ID '$id'" }
                armoryGroup(id)?.let { armoryGroups[id] = it }
                equipmentSlot(id)?.let { equipmentSlots[id] = it }
                if (publicIds != null || (publicUnlessHidden && value["excludeFromInventory"] != true)) {
                    publicGearIds += id
                }
            }
        }

        val gear = nexo.resolve("items/gear")
        include(
            gear.resolve("armors/armors.yml"),
            publicUnlessHidden = true,
            armoryGroup = ::armorGroup,
            equipmentSlot = { id -> armorGroup(id)?.let(::slotForArmoryGroup) },
        )
        mapOf(
            "axes.yml" to ("armory-axe" to "MAIN_HAND"),
            "magnets.yml" to ("armory-magnets" to "MAGNET"),
            "shields.yml" to ("armory-shield" to "OFF_HAND"),
            "swords.yml" to ("armory-sword" to "MAIN_HAND"),
        ).forEach { (name, classification) ->
            val (group, slot) = classification
            include(
                gear.resolve(name),
                publicUnlessHidden = true,
                armoryGroup = { group },
                equipmentSlot = { slot },
            )
        }

        val backpacksFile = coreConfig.resolve("backpacks.yml")
        sourceFiles.add(backpacksFile)
        val backpackIds = loadMap(backpacksFile).values.mapNotNull {
            it.asMap()["nexo-id"]?.toString()
        }.toSet()
        include(
            nexo.resolve("items/items/backpacks.yml"),
            backpackIds,
            equipmentSlot = { "BACKPACK" },
        )

        val trinketsFile = coreConfig.resolve("trinkets/trinkets.yml")
        sourceFiles.add(trinketsFile)
        val trinketSlots = loadMap(trinketsFile).values.mapNotNull {
            val config = it.asMap()
            val id = config.map("item")["type"]?.toString() ?: return@mapNotNull null
            val slot = config["target-slot"]?.toString()?.uppercase(Locale.ROOT) ?: return@mapNotNull null
            id to slot
        }.toMap()
        include(
            nexo.resolve("items/items/trinkets.yml"),
            trinketSlots.keys,
            equipmentSlot = trinketSlots::get,
        )

        // Progression and badge icons are dependencies of their own public
        // catalogs. They are deliberately not duplicated in catalog/items.
        listOf("abilities.yml", "talents.yml", "badges.yml").forEach { name ->
            include(nexo.resolve("items/items/$name"))
        }

        return NexoSelection(
            definitions = definitions,
            publicGearIds = publicGearIds.sorted(),
            sourceFiles = sourceFiles,
            armoryGroups = armoryGroups,
            equipmentSlots = equipmentSlots,
        )
    }

    private fun sourceHash(stage: Path, selectedSources: Set<Path>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CONTENT_TRANSFORM_VERSION.toByteArray())
        val configuredSources = sequenceOf(
            coreConfig.resolve("abilities"),
            coreConfig.resolve("talents"),
            coreConfig.resolve("badges.yml"),
            coreConfig.resolve("item/item-templates.yml"),
            coreConfig.resolve("armory/modifiers"),
            coreConfig.resolve("armory/level-requirements.yml"),
            coreConfig.resolve("web-content.yml"),
            coreConfig.resolve("guild.yml"),
            coreConfig.resolve("menu/abilities/abilities-select-menu-new.yml"),
            coreConfig.resolve("menu/abilities/ability-specialize-unlocked-1.yml"),
            coreConfig.resolve("menu/abilities/ability-specialize-unlocked-2.yml"),
            coreConfig.resolve("menu/abilities/ability-specialize-unlocked-3.yml"),
            coreConfig.resolve("menu/talents/talents-select-menu.yml"),
            nexo.resolve("glyphs"),
        ) + selectedSources.asSequence() + sequenceOf(stage.resolve("assets"))
        configuredSources.distinct().forEach { path ->
            if (Files.isDirectory(path)) {
                Files.walk(path).use { files ->
                    files.filter(Files::isRegularFile).sorted().forEach { file ->
                        digest.update(path.relativize(file).toString().toByteArray())
                        Files.newInputStream(file).use { it.transferTo(DigestOutputStream(digest)) }
                    }
                }
            } else if (path.isRegularFile()) {
                Files.newInputStream(path).use { it.transferTo(DigestOutputStream(digest)) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun extractPack(stage: Path, definitions: Collection<Map<String, Any?>>) {
        val pack = nexo.resolve("pack/pack.zip")
        require(pack.isRegularFile()) { "Missing Nexo pack.zip" }
        ZipFile(pack.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).associateBy { it.name }
            val copied = mutableSetOf<String>()

            fun copy(path: String, required: Boolean = true): Boolean {
                if (path in copied) return true
                val entry = entries[path]
                if (entry == null) {
                    require(!required) { "Resource pack is missing '$path'" }
                    return false
                }
                val output = stage.resolve(path).normalize()
                require(output.startsWith(stage)) { "Unsafe resource pack entry $path" }
                Files.createDirectories(output.parent)
                zip.getInputStream(entry).use { input -> Files.copy(input, output) }
                copied += path
                return true
            }

            fun location(value: String, defaultNamespace: String = "minecraft"): Pair<String, String> {
                val clean = value.removeSuffix(".json").removeSuffix(".png")
                val parts = clean.split(':', limit = 2)
                return if (parts.size == 2) parts[0] to parts[1] else defaultNamespace to parts[0]
            }

            fun copyTexture(
                value: String,
                defaultNamespace: String = "minecraft",
                required: Boolean = true,
            ) {
                if (value.startsWith('#')) return
                val (namespace, path) = location(value, defaultNamespace)
                val asset = "assets/$namespace/textures/$path.png"
                if (copy(asset, required)) copy("$asset.mcmeta", required = false)
            }

            val modelQueue = ArrayDeque<Pair<String, String>>()
            val itemQueue = ArrayDeque<Pair<String, String>>()
            val equipmentQueue = ArrayDeque<Pair<String, String>>()
            val seenModels = mutableSetOf<String>()
            val seenItems = mutableSetOf<String>()
            val seenEquipment = mutableSetOf<String>()

            fun queueModel(value: String, defaultNamespace: String = "minecraft") {
                if (!value.startsWith('#')) modelQueue += location(value, defaultNamespace)
            }
            fun queueItem(value: String, defaultNamespace: String = "minecraft") {
                itemQueue += location(value, defaultNamespace)
            }
            fun queueEquipment(value: String, defaultNamespace: String = "minecraft") {
                equipmentQueue += location(value, defaultNamespace)
            }

            definitions.forEach { value ->
                val itemPack = value.map("Pack")
                itemPack["model"]?.toString()?.let(::queueModel)
                when (val textures = itemPack["textures"] ?: itemPack["texture"]) {
                    is Map<*, *> -> textures.values.filterNotNull().forEach { copyTexture(it.toString()) }
                    is Collection<*> -> textures.filterNotNull().forEach { copyTexture(it.toString()) }
                    null -> Unit
                    else -> copyTexture(textures.toString())
                }
                val components = value.map("Components")
                components["item_model"]?.toString()?.let(::queueItem)
                components.map("equippable")["asset_id"]?.toString()?.let(::queueEquipment)
            }

            while (itemQueue.isNotEmpty()) {
                val (namespace, resource) = itemQueue.removeFirst()
                val path = "assets/$namespace/items/$resource.json"
                if (!seenItems.add(path)) continue
                if (!copy(path, required = false)) {
                    require(namespace == "minecraft" && '/' !in resource) {
                        "Missing custom item model '$namespace:$resource'"
                    }
                    continue
                }
                val element = json.parseToJsonElement(zip.getInputStream(entries.getValue(path)).reader().readText())
                collectJsonStrings(element, "model").forEach(::queueModel)
            }

            while (modelQueue.isNotEmpty()) {
                val (namespace, resource) = modelQueue.removeFirst()
                val path = "assets/$namespace/models/$resource.json"
                if (!seenModels.add(path)) continue
                if (!copy(path, required = false)) {
                    require(namespace == "minecraft" && resource.isVanillaModel()) {
                        "Missing custom model '$namespace:$resource'"
                    }
                    continue
                }
                val model = json.parseToJsonElement(zip.getInputStream(entries.getValue(path)).reader().readText())
                    .asMapJson()
                model["parent"]?.primitiveTextOrNull()?.let(::queueModel)
                (model["textures"] as? JsonObject)?.values?.mapNotNull { it.primitiveTextOrNull() }
                    ?.forEach { copyTexture(it) }
                model["overrides"]?.let { collectJsonStrings(it, "model") }
                    ?.forEach(::queueModel)
            }

            while (equipmentQueue.isNotEmpty()) {
                val (namespace, resource) = equipmentQueue.removeFirst()
                val path = "assets/$namespace/equipment/$resource.json"
                if (!seenEquipment.add(path)) continue
                copy(path)
                val equipment = json.parseToJsonElement(zip.getInputStream(entries.getValue(path)).reader().readText())
                val layers = equipment.asMapJson()["layers"] as? JsonObject ?: continue
                layers.forEach { (layer, definitions) ->
                    if (layer !in PLAYER_EQUIPMENT_LAYERS) return@forEach
                    collectJsonStrings(definitions, "texture").forEach { texture ->
                        val (textureNamespace, texturePath) = location(texture, namespace)
                        copyTexture("$textureNamespace:entity/equipment/$layer/$texturePath")
                    }
                }
            }

            loadMap(coreConfig.resolve("item/item-templates.yml"))
                .map("armory")
                .map("tooltip_style")
                .values
                .mapNotNull { it?.toString() }
                .distinct()
                .forEach { styleKey ->
                    val (namespace, style) = location(styleKey)
                    listOf("background", "frame").forEach { layer ->
                        val sprite = "assets/$namespace/textures/gui/sprites/tooltip/${style}_$layer.png"
                        copy(sprite)
                        copy("$sprite.mcmeta")
                    }
                }

            // Nexo's font files and every configured Nexo glyph are the web
            // rich-text surface. BetterHUD and other plugin-generated fonts are
            // intentionally outside this standalone catalog.
            entries.keys.filter { it.startsWith("assets/nexo/font/") && it.endsWith(".json") }
                .sorted().forEach { fontPath ->
                    copy(fontPath)
                    val font = json.parseToJsonElement(
                        zip.getInputStream(entries.getValue(fontPath)).reader().readText()
                    )
                    collectJsonStrings(font, "file").forEach { copyTexture(it) }
                }
            Files.walk(nexo.resolve("glyphs")).use { glyphFiles ->
                glyphFiles.filter { it.isRegularFile() && it.extension in setOf("yml", "yaml") }
                    .forEach { file ->
                        loadMap(file).values.forEach { raw ->
                            raw.asMap()["texture"]?.toString()?.let { copyTexture(it, required = false) }
                        }
                    }
            }
        }
    }

    private fun readGlyphs(stage: Path): List<GlyphDefinition> {
        val definitions = mutableListOf<GlyphDefinition>()
        Files.walk(nexo.resolve("glyphs")).use { files ->
            files.filter { it.isRegularFile() && it.extension in setOf("yml", "yaml") }.forEach { file ->
                loadMap(file).forEach { (id, raw) ->
                    val value = raw.asMap()
                    val character = value["char"]?.toString()?.takeIf(String::isNotEmpty) ?: return@forEach
                    val texture = value["texture"]?.toString() ?: return@forEach
                    val asset = findAsset(stage, "/textures/$texture.png")
                    if (asset == null) {
                        // Nexo configuration can retain orphan mappings after a pack
                        // migration. Do not publish that mapping; any catalog lore that
                        // actually references it still fails through RichTextRenderer.
                        System.err.println("Skipping orphan Nexo glyph '$id' (missing '$texture')")
                        return@forEach
                    }
                    val image = requireNotNull(ImageIO.read(asset.toFile())) { "Malformed glyph texture for '$id'" }
                    val height = value.int("height", image.height).coerceAtLeast(1)
                    val width = if (image.height == 0) height
                        else ((image.width.toDouble() / image.height) * height).toInt().coerceAtLeast(1)
                    definitions += GlyphDefinition(
                        id = id,
                        codePoint = character.codePointAt(0),
                        assetPath = stage.relativize(asset).toString().replace('\\', '/'),
                        width = width,
                        height = height,
                        ascent = value.int("ascent", height),
                    )
                }
            }
        }
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Duplicate Nexo glyph IDs" }
        return definitions
    }

    private fun readNexoItems(
        stage: Path,
        renderer: RichTextRenderer,
        version: String,
        definitions: Map<String, Map<String, Any?>>,
        armoryGroups: Map<String, String>,
        equipmentSlots: Map<String, String>,
        modifierDefinitions: Map<String, JsonObject>,
        levelBands: JsonArray,
    ): Map<String, CatalogEntry> {
        val result = linkedMapOf<String, CatalogEntry>()
        definitions.forEach { (id, value) ->
            val pack = value.map("Pack")
            val componentItemModel = value.map("Components")["item_model"]?.toString()
            val equipmentAsset = value.map("Components").map("equippable")["asset_id"]?.toString()
            val declaredModel = pack["model"]?.toString()
            val model = declaredModel?.let { modelValue ->
                requireNotNull(modelPath(stage, modelValue)) {
                    "Item '$id' references missing model '$modelValue'"
                }
            } ?: componentItemModel?.let { itemModelValue ->
                requireNotNull(itemDefinitionPath(stage, itemModelValue)) {
                    "Item '$id' references missing item model '$itemModelValue'"
                }
            }
            val equipment = equipmentAsset?.let { equipmentValue ->
                requireNotNull(equipmentDefinitionPath(stage, equipmentValue)) {
                    "Item '$id' references missing equipment definition '$equipmentValue'"
                }
            }
            val textureValues = when (val textures = pack["textures"] ?: pack["texture"]) {
                is Map<*, *> -> textures.entries.mapNotNull { (key, texture) ->
                    texture?.toString()?.let { key.toString() to it }
                }
                is Collection<*> -> textures.mapIndexedNotNull { index, texture ->
                    texture?.toString()?.let { "layer$index" to it }
                }
                null -> emptyList()
                else -> listOf("layer0" to textures.toString())
            }
            val textures = textureValues.associate { (key, texture) ->
                val asset = requireNotNull(findAsset(stage, "/textures/${texture.substringAfter(':')}.png")) {
                    "Item '$id' references missing texture '$texture'"
                }
                key to "/assets/$version/${stage.relativize(asset).toString().replace('\\', '/')}"
            }
            val lore = value.stringList("lore").map(renderer::fromMiniMessage)
            result[id] = CatalogEntry(
                id = id,
                kind = CatalogKind.ITEMS,
                name = renderer.fromMiniMessage(value["itemname"]?.toString() ?: id.humanize()),
                lore = lore,
                assets = if (model != null || equipment != null || textures.isNotEmpty()) AssetReference(
                    catalogVersion = version,
                    modelUrl = model?.let { "/assets/$version/$it" },
                    equipmentUrl = equipment?.let { "/assets/$version/$it" },
                    textures = textures,
                ) else null,
                metadata = buildMap {
                    put("material", JsonPrimitive(value["material"]?.toString() ?: "AIR"))
                    equipmentSlots[id]?.let { put("equipmentSlot", JsonPrimitive(it)) }
                    armoryGroups[id]?.let { group ->
                        put("armoryGroup", JsonPrimitive(group))
                        put("armoryLevelBands", levelBands)
                        put(
                            "armoryModifiers",
                            requireNotNull(modifierDefinitions[group]) {
                                "Nexo item '$id' references missing armory modifier group '$group'"
                            },
                        )
                    }
                },
            )
        }
        return result
    }

    private fun readArmoryModifierDefinitions(): Map<String, JsonObject> {
        val directory = coreConfig.resolve("armory/modifiers")
        require(directory.isDirectory()) { "Missing Core armory modifiers: $directory" }
        return Files.walk(directory).use { files ->
            files.filter { it.isRegularFile() && it.extension in setOf("yml", "yaml") }
                .sorted()
                .iterator()
                .asSequence()
                .associate { file ->
                    val group = file.fileName.toString().substringBeforeLast('.')
                    val definitions = buildMap<String, JsonElement> {
                        loadMap(file).map("groups").values.forEach { rawGroup ->
                            val modifierGroup = rawGroup.asMap()
                            val attribute = modifierGroup["target"]?.toString() ?: return@forEach
                            if (attribute !in PROFILE_ATTRIBUTES) return@forEach
                            val policy = modifierGroup["stacking-policy"]?.toString() ?: "set_new_value"
                            modifierGroup.map("values").forEach { (name, rawRange) ->
                                val (minimum, maximum) = parseModifierRange(
                                    rawRange,
                                    attribute == "resistance" || policy.normalizePolicy() == "add_percentage",
                                ) ?: return@forEach
                                put(
                                    name,
                                    JsonObject(
                                        mapOf(
                                            "attribute" to JsonPrimitive(attribute),
                                            "policy" to JsonPrimitive(policy.normalizePolicy()),
                                            "minimum" to JsonPrimitive(minimum),
                                            "maximum" to JsonPrimitive(maximum),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                    group to JsonObject(definitions)
                }
        }
    }

    private fun readArmoryLevelBands(): JsonArray =
        loadMap(coreConfig.resolve("armory/level-requirements.yml"))["level-bands"]
            ?.toJsonElement() as? JsonArray
            ?: JsonArray(emptyList())

    private fun parseModifierRange(value: Any?, percentage: Boolean): Pair<Double, Double>? {
        val match = MODIFIER_RANGE.matchEntire(value?.toString()?.trim().orEmpty()) ?: return null
        fun number(index: Int, percentIndex: Int): Double? {
            val raw = match.groupValues[index].takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: return null
            return if (percentage && match.groupValues[percentIndex] == "%") raw / 100.0 else raw
        }
        val minimum = number(1, 2) ?: return null
        val maximum = number(3, 4) ?: minimum
        return minimum to maximum
    }

    private fun armorGroup(id: String): String? = when {
        "chestplate" in id -> "armor-chestplate"
        "leggings" in id -> "armory-leggings"
        "boots" in id -> "armory-boots"
        "helmet" in id -> "armory-helmet"
        else -> null
    }

    private fun slotForArmoryGroup(group: String): String = when {
        "helmet" in group -> "HEAD"
        "chest" in group -> "CHEST"
        "leg" in group -> "LEGS"
        "boot" in group -> "FEET"
        else -> error("Unsupported armor group '$group'")
    }

    private fun readProgression(
        kind: CatalogKind,
        configDirectory: Path,
        menuFile: Path,
        detailedMenuFiles: List<Path>,
        nexoItems: Map<String, CatalogEntry>,
        groups: Map<String, String>,
        renderer: RichTextRenderer,
        version: String,
    ): List<CatalogEntry> {
        val menus = (detailedMenuFiles + listOf(menuFile)).map { path ->
            val items = loadMap(path).map("items")
            val byType = items.entries.associateBy(
                keySelector = { (_, value) -> value.asMap()["type"]?.toString()?.normalizeId().orEmpty() },
                valueTransform = { it.value.asMap() },
            )
            items to byType
        }
        val result = mutableMapOf<String, Pair<Int, CatalogEntry>>()
        Files.walk(configDirectory).use { files ->
            files.filter { it.isRegularFile() && it.extension in setOf("yml", "yaml") }.forEach { file ->
                val fileContent = loadMap(file)
                fileContent.forEach { (id, raw) ->
                    val config = raw.asMap()
                    val menu = menus.asSequence().map { (items, byType) ->
                        items[id].asMap()
                            .ifEmpty { items["talent-$id"].asMap() }
                            .ifEmpty { items["ability-$id"].asMap() }
                            .ifEmpty { byType[id.normalizeId()].orEmpty() }
                    }.firstOrNull { it.isNotEmpty() }.orEmpty()
                    val iconId = menu["type"]?.toString() ?: id.replace('-', '_')
                    val levels = config.map("settings").mapNotNull { (level, values) ->
                        level.toIntOrNull()?.let { it to values.toJsonObject() }
                    }.toMap()
                    val nameSource = menu["name"]?.toString() ?: id.humanize()
                    val loreSource = menu.stringList("side-description").ifEmpty { menu.stringList("lore") }
                    val maxLevel = levels.keys.maxOrNull()
                    val substitutions = mapOf(
                        "%name%" to renderer.fromMiniMessage(nameSource).plainText,
                        "%level%" to "1",
                        "%max_level%" to (maxLevel ?: 1).toString(),
                    ) + levels[1].orEmpty().mapValues { (_, value) -> value.primitiveText() }
                        .mapKeys { (key, _) -> "%$key%" }
                    fun substitute(value: String): String = substitutions.entries.fold(value) { text, entry ->
                        text.replace(entry.key, entry.value)
                    }
                    val resolvedIconId = listOf(iconId, id, id.replace('-', '_'))
                        .firstOrNull(nexoItems::containsKey)
                        ?: iconId
                    val icon = nexoItems[resolvedIconId]
                    val entry = CatalogEntry(
                        id = id,
                        kind = kind,
                        name = renderer.fromMiniMessage(substitute(nameSource)),
                        lore = loreSource.map { renderer.fromMiniMessage(substitute(it)) },
                        group = groups[id],
                        maximumLevel = maxLevel,
                        levels = levels,
                        assets = icon?.assets,
                        metadata = mapOf(
                            "iconItemId" to JsonPrimitive(resolvedIconId),
                            "loreTemplates" to JsonArray(loreSource.map(::JsonPrimitive)),
                        ),
                    )
                    // Core's migration can leave a legacy multi-talent group file beside
                    // the current one-file-per-group configuration. Mirror Core's current
                    // selection by preferring the dedicated file, while still rejecting
                    // ambiguous duplicates at the same precedence.
                    val stem = file.fileName.toString().substringBeforeLast('.')
                    val precedence = when {
                        stem == "talent-$id" || stem == "ability-$id" -> 2
                        fileContent.size == 1 -> 1
                        else -> 0
                    }
                    val previous = result[id]
                    require(previous == null || previous.first != precedence) {
                        "Duplicate ${kind.name.lowercase()} ID '$id' in equally authoritative files"
                    }
                    if (previous == null || precedence > previous.first) result[id] = precedence to entry
                }
            }
        }
        return result.values.map { it.second }.sortedBy { it.id }
    }

    private fun readBadges(
        nexoItems: Map<String, CatalogEntry>,
        renderer: RichTextRenderer,
    ): List<CatalogEntry> = loadMap(coreConfig.resolve("badges.yml")).map("badges").map { (id, raw) ->
        val value = raw.asMap()
        val type = value["type"]?.toString()
        CatalogEntry(
            id = id,
            kind = CatalogKind.BADGES,
            name = renderer.fromMiniMessage(value["name"]?.toString() ?: id.humanize()),
            lore = value.stringList("other-profile-lore").ifEmpty { value.stringList("lore") }
                .map(renderer::fromMiniMessage),
            assets = type?.let(nexoItems::get)?.assets,
            metadata = type?.let { mapOf("iconItemId" to JsonPrimitive(it)) }.orEmpty(),
        )
    }

    private fun readRanks(renderer: RichTextRenderer): List<CatalogEntry> {
        val override = coreConfig.resolve("web-content.yml")
        if (!override.isRegularFile()) return listOf(
            CatalogEntry("default", CatalogKind.RANKS, renderer.fromPlain("Player"))
        )
        return loadMap(override).map("ranks").map { (id, raw) ->
            val value = raw.asMap()
            CatalogEntry(
                id = id,
                kind = CatalogKind.RANKS,
                name = renderer.fromMiniMessage(value["name"]?.toString() ?: id.humanize()),
                lore = value.stringList("lore").map(renderer::fromMiniMessage),
            )
        }
    }

    private fun readAbilityGroups(): Map<String, String> {
        val directory = coreSource.resolve(
            "spigot/src/main/java/com/galithin/core/spigot/feature/ability/data/registry/group/impl"
        )
        if (!directory.isDirectory()) return emptyMap()
        val result = mutableMapOf<String, String>()
        Files.walk(directory).use { files ->
            files.filter { it.isRegularFile() && it.extension == "java" }.forEach { file ->
                val source = Files.readString(file)
                val group = GROUP_NAME.find(source)?.groupValues?.get(1) ?: return@forEach
                ABILITY_NAME.findAll(source).forEach { match -> result[match.groupValues[1]] = group }
            }
        }
        return result
    }

    private fun modelPath(stage: Path, value: String): String? {
        val namespaced = value.split(':', limit = 2)
        val namespace = if (namespaced.size == 2) namespaced[0] else "minecraft"
        val model = (if (namespaced.size == 2) namespaced[1] else namespaced[0]).removeSuffix(".json")
        val relative = "assets/$namespace/models/$model.json"
        return relative.takeIf { stage.resolve(it).isRegularFile() }
    }

    private fun itemDefinitionPath(stage: Path, value: String): String? {
        val namespaced = value.split(':', limit = 2)
        val namespace = if (namespaced.size == 2) namespaced[0] else "minecraft"
        val item = (if (namespaced.size == 2) namespaced[1] else namespaced[0]).removeSuffix(".json")
        val relative = "assets/$namespace/items/$item.json"
        return relative.takeIf { stage.resolve(it).isRegularFile() }
    }

    private fun equipmentDefinitionPath(stage: Path, value: String): String? {
        val namespaced = value.split(':', limit = 2)
        val namespace = if (namespaced.size == 2) namespaced[0] else "minecraft"
        val equipment = (if (namespaced.size == 2) namespaced[1] else namespaced[0]).removeSuffix(".json")
        val relative = "assets/$namespace/equipment/$equipment.json"
        return relative.takeIf { stage.resolve(it).isRegularFile() }
    }

    private fun findAsset(stage: Path, suffix: String): Path? = Files.walk(stage.resolve("assets")).use { files ->
        files.filter(Files::isRegularFile)
            .filter { it.toString().replace('\\', '/').endsWith(suffix) }
            .findFirst().orElse(null)
    }

    private fun writeEntries(path: Path, entries: List<CatalogEntry>) {
        Files.writeString(path, json.encodeToString(ListSerializer(CatalogEntry.serializer()), entries))
    }

    private fun readGuildCatalog(): GuildCatalog {
        val levels = loadMap(coreConfig.resolve("guild.yml")).map("levels")
        val baseExperienceCost = levels.long("base-experience-cost", 1_000)
        val experienceIncreasePerLevel = levels.double("experience-increase-per-level", 1.15)
        val maximumLevel = levels.int("max-level", 100)
        require(baseExperienceCost > 0) { "Guild base experience cost must be positive" }
        require(experienceIncreasePerLevel.isFinite() && experienceIncreasePerLevel > 0) {
            "Guild experience increase per level must be positive"
        }
        require(maximumLevel > 0) { "Guild maximum level must be positive" }
        val rewards = levels.map("rewards").mapNotNull { (rawLevel, rawReward) ->
            val level = rawLevel.toIntOrNull()
                ?: error("Invalid guild reward level '$rawLevel'")
            val reward = rawReward.asMap()
            level to GuildLevelReward(
                maximumMembers = reward.int("max-members", -1).takeIf { it >= 0 },
                maximumClaims = reward.int("max-claims", -1).takeIf { it >= 0 },
            )
        }.toMap()
        return GuildCatalog(
            baseExperienceCost = baseExperienceCost,
            experienceIncreasePerLevel = experienceIncreasePerLevel,
            maximumLevel = maximumLevel,
            rewards = rewards,
        )
    }

    private fun checksumFiles(stage: Path): Map<String, String> = Files.walk(stage).use { files ->
        files.filter(Files::isRegularFile).sorted().iterator().asSequence().associate { file ->
            stage.relativize(file).toString().replace('\\', '/') to
                Files.newInputStream(file).use(::sha256)
        }
    }

    private fun writeZip(stage: Path, output: Path) {
        val temporary = Files.createTempFile(output.parent, "${output.fileName}.", ".tmp")
        try {
            Files.newOutputStream(temporary).use { raw ->
                ZipOutputStream(raw).use { zip ->
                    Files.walk(stage).use { files ->
                        files.filter(Files::isRegularFile).sorted().forEach { file ->
                            val entry = ZipEntry(stage.relativize(file).toString().replace('\\', '/'))
                            entry.time = 0
                            zip.putNextEntry(entry)
                            Files.copy(file, zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
            try {
                Files.move(
                    temporary,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun loadMap(path: Path): Map<String, Any?> =
        Files.newBufferedReader(path).use { yaml.load<Any?>(it).asMap() }

    private fun Any?.asMap(): Map<String, Any?> =
        (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key].asMap()

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (this[key] as? Collection<*>)?.mapNotNull { it?.toString() }.orEmpty()

    private fun Map<String, Any?>.int(key: String, default: Int): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

    private fun Map<String, Any?>.long(key: String, default: Long): Long =
        (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: default

    private fun Map<String, Any?>.double(key: String, default: Double): Double =
        (this[key] as? Number)?.toDouble() ?: this[key]?.toString()?.toDoubleOrNull() ?: default

    private fun Any?.toJsonObject(): JsonObject =
        (toJsonElement() as? JsonObject) ?: JsonObject(emptyMap())

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.toJsonElement() })
        is Collection<*> -> JsonArray(map { it.toJsonElement() })
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    private fun JsonElement.primitiveText(): String = (this as? JsonPrimitive)?.content ?: toString()

    private fun JsonElement.primitiveTextOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.asMapJson(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

    private fun collectJsonStrings(element: JsonElement, key: String): List<String> = buildList {
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> node.forEach { (name, value) ->
                    if (name == key) value.primitiveTextOrNull()?.let(::add)
                    visit(value)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(element)
    }

    private fun String.isVanillaModel(): Boolean =
        startsWith("item/") || startsWith("block/") || startsWith("builtin/")

    private fun String.humanize(): String = split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun String.normalizeId(): String = lowercase().replace('-', '_')

    companion object {
        // Bump whenever generator logic changes the bytes or meaning of catalog output.
        // The catalog version is used as an immutable cache/asset namespace.
        private const val CONTENT_TRANSFORM_VERSION = "7"
        private val PROFILE_ATTRIBUTES = setOf("attack_damage", "health", "resistance", "mana")
        private val MODIFIER_RANGE = Regex(
            """^(-?\d+(?:\.\d+)?)\s*(%)?(?:\s*-\s*(-?\d+(?:\.\d+)?)\s*(%)?)?$""",
        )
        private val PLAYER_EQUIPMENT_LAYERS = setOf("humanoid", "humanoid_leggings")
        private val GROUP_NAME = Regex("String getName\\(\\) \\{\\s*return \\\"([^\\\"]+)\\\"")
        private val ABILITY_NAME = Regex("(?:create|builder)\\(\\\"([^\\\"]+)\\\"\\)")

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            input.transferTo(DigestOutputStream(digest))
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun deleteRecursively(path: Path) {
            Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

private fun String.normalizePolicy(): String = lowercase().replace('-', '_')

private class DigestOutputStream(private val digest: MessageDigest) : java.io.OutputStream() {
    override fun write(value: Int) = digest.update(value.toByte())

    override fun write(buffer: ByteArray, offset: Int, length: Int) = digest.update(buffer, offset, length)
}
