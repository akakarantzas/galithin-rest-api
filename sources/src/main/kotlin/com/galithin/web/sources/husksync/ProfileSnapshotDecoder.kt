package com.galithin.web.sources.husksync

import com.fasterxml.jackson.databind.JsonNode
import com.galithin.web.domain.AssetReference
import com.galithin.web.domain.BadgePresentation
import com.galithin.web.domain.CatalogEntry
import com.galithin.web.domain.CatalogKind
import com.galithin.web.domain.CatalogRepository
import com.galithin.web.domain.DungeonStatistics
import com.galithin.web.domain.EquipmentItem
import com.galithin.web.domain.EquipmentSlot
import com.galithin.web.domain.PlayerProfile
import com.galithin.web.domain.PlayerStatistics
import com.galithin.web.domain.ProgressionEntry
import com.galithin.web.domain.RankPresentation
import com.galithin.web.domain.TooltipStyleReference
import com.galithin.web.sources.core.CoreDataSnapshot
import com.galithin.web.sources.husksync.Snbt.compound
import com.galithin.web.sources.husksync.Snbt.findCompound
import com.galithin.web.sources.husksync.Snbt.int
import com.galithin.web.sources.husksync.Snbt.list
import com.galithin.web.sources.husksync.Snbt.local
import com.galithin.web.sources.husksync.Snbt.long
import com.galithin.web.sources.husksync.Snbt.number
import com.galithin.web.sources.husksync.Snbt.string
import com.galithin.web.sources.husksync.Snbt.toJsonElement
import com.galithin.web.sources.objectMapper
import com.galithin.web.sources.text.RichTextRenderer
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.kyori.adventure.nbt.BinaryTag
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.kyori.adventure.nbt.StringBinaryTag

data class EligibilityRules(
    val excludedGroups: Set<String> = emptySet(),
    val excludedUuids: Set<String> = emptySet(),
    val excludedNames: Set<String> = emptySet(),
)

data class DecodedPlayer(
    val profile: PlayerProfile,
    val eligible: Boolean,
)

private data class ProfileMenuStatistics(
    val damage: Double,
    val health: Double,
    val defense: Double,
    val mana: Double,
)

private data class ArmoryModifierDefinition(
    val attribute: String,
    val policy: String,
    val minimum: Double,
    val maximum: Double,
)

private data class RolledArmoryModifier(
    val definition: ArmoryModifierDefinition,
    val value: Double,
    val priority: Int,
)

class ProfileSnapshotDecoder(
    private val catalog: CatalogRepository,
    private val richText: RichTextRenderer,
    private val eligibility: EligibilityRules,
) {
    fun decode(
        row: PackedSnapshotRow,
        snapshot: DecodedSnapshot,
        core: CoreDataSnapshot,
        fallbackSnapshots: List<DecodedSnapshot> = emptyList(),
    ): DecodedPlayer {
        val candidates = listOf(snapshot) + fallbackSnapshots
        val sync = candidates.mapNotNull { candidate ->
            candidate.serialized("persistent_data")?.let(::parseSyncOrNull)
        }.reduceOrNull(::mergeMissing)
        val inventory = candidates.firstNotNullOfOrNull { candidate ->
            candidate.serialized("inventory")?.let(::parseSnbtOrNull)
        }
        val levelData = sync?.findCompound("player_level_data")
        val level = levelData?.int("level")?.coerceAtLeast(1) ?: 1
        val experience = levelData?.long("experience")?.coerceAtLeast(0) ?: 0L
        val identity = core.identities[row.playerUuid.lowercase()]
        // LuckPerms normalizes usernames for lookup/storage. HuskSync records the
        // name supplied by the online player and therefore retains its canonical
        // Minecraft casing.
        val name = row.username.ifBlank { identity?.username.orEmpty() }
        val rank = identity?.rank ?: defaultRank()
        val badges = decodeBadges(sync)
        val equipment = decodeEquipment(inventory, sync)
        val profileMenuStatistics = calculateProfileMenuStatistics(
            sync,
            equipment,
            candidates.firstNotNullOfOrNull { it.serialized("health") },
            level,
        )
        val statistics = decodePlayerStatistics(
            candidates.firstNotNullOfOrNull { it.serialized("statistics") },
            candidates.firstNotNullOfOrNull { it.serialized("advancements") },
            identity?.firstJoinedAt ?: row.firstSeenAt,
            identity?.lastJoinedAt ?: row.savedAt,
            profileMenuStatistics,
        )
        val profile = PlayerProfile(
            uuid = row.playerUuid.lowercase(),
            name = name,
            level = level,
            experience = experience,
            rank = rank,
            badges = badges,
            guild = core.playerGuilds[row.playerUuid.lowercase()],
            equipment = equipment,
            statistics = statistics,
            dungeonStatistics = decodeDungeonStatistics(sync),
            abilities = decodeProgression(sync, equipment, CatalogKind.ABILITIES, "ability_metadata", "ability-metadata", "quick-abilities"),
            talents = decodeProgression(sync, equipment, CatalogKind.TALENTS, "talent_metadata", "talent-metadata", "unlocked-talents"),
        )
        val group = rank.id.lowercase()
        val eligible = identity?.banned != true &&
            group !in eligibility.excludedGroups &&
            profile.uuid !in eligibility.excludedUuids &&
            profile.name.lowercase() !in eligibility.excludedNames
        return DecodedPlayer(profile, eligible)
    }

    fun needsCoreFallback(snapshot: DecodedSnapshot): Boolean {
        val sync = snapshot.serialized("persistent_data")?.let(::parseSyncOrNull) ?: return true
        return CORE_SYNC_KEYS.none { key -> sync.local(key) != null }
    }

    private fun decodeEquipment(
        inventoryRoot: CompoundBinaryTag?,
        sync: CompoundBinaryTag?,
    ): List<EquipmentItem> {
        val items = mutableMapOf<EquipmentSlot, CompoundBinaryTag>()
        if (inventoryRoot != null) {
            val held = inventoryRoot.int("held_item_slot") ?: 0
            val inventory = inventoryRoot.compound("items")
            val list = inventory?.list("items")
            list?.forEach { tag ->
                val item = tag as? CompoundBinaryTag ?: return@forEach
                val slot = when (item.int("Slot")) {
                    36 -> EquipmentSlot.FEET
                    37 -> EquipmentSlot.LEGS
                    38 -> EquipmentSlot.CHEST
                    39 -> EquipmentSlot.HEAD
                    40 -> EquipmentSlot.OFF_HAND
                    held -> EquipmentSlot.MAIN_HAND
                    else -> null
                }
                if (slot != null) items[slot] = item
            }
        }
        val custom = sync?.findCompound("custom_armory_items")
        mapOf(
            "backpack" to EquipmentSlot.BACKPACK,
            "necklace" to EquipmentSlot.NECKLACE,
            "ring" to EquipmentSlot.RING,
            "magnet" to EquipmentSlot.MAGNET,
        ).forEach { (key, slot) ->
            custom?.string(key)?.let(::parseSnbtOrNull)?.let { items[slot] = it }
        }
        return items.mapNotNull { (slot, tag) -> decodeItem(slot, tag) }
            .sortedBy { it.slot.ordinal }
    }

    private fun decodeItem(slot: EquipmentSlot, item: CompoundBinaryTag): EquipmentItem? {
        val components = item.compound("components") ?: item
        val customData = components.compound("minecraft:custom_data")
            ?: components.compound("custom_data")
            ?: item.findCompound("PublicBukkitValues")
            ?: return null
        val publicValues = customData.compound("PublicBukkitValues") ?: customData
        val nexoId = publicValues.string("id") ?: publicValues.string("nexo:id")
        val material = (item.string("id") ?: item.string("Id") ?: "minecraft:air")
            .substringAfter(':').uppercase(Locale.ROOT)
        if (material == "AIR") return null
        val group = publicValues.string("group")
        val attributes = publicValues.local("armory-item-attributes")
        val itemUuid = publicValues.string("uuid")
        val catalogEntry = nexoId?.let { catalog.entry(CatalogKind.ITEMS, it) }
            ?: group?.let { catalog.entry(CatalogKind.ITEMS, it) }
        val customSlot = slot in CUSTOM_SLOTS

        if (customSlot) {
            // Core owns these dedicated PDC slots. Backpacks and trinkets are
            // Nexo-backed equipment, but intentionally have no rolled-armory
            // group, attributes, or item UUID. The public item catalog is the
            // allowlist that keeps unrelated Nexo items out of the API.
            if (nexoId == null || catalogEntry == null) return null
            if (catalogEntry.metadata["equipmentSlot"].primitiveContent() != slot.name) return null
        } else {
            // Rolled armor and weapons are identified by their attributes in
            // real snapshots. Older items do not consistently have the group
            // or UUID fields, so those fields enrich validation when present
            // rather than deciding whether the item exists.
            if (attributes == null) return null
            if (group != null && !group.lowercase().startsWith("armory-")) return null
            val gearType = group
                ?: catalogEntry?.metadata?.get("armoryGroup").primitiveContent()
                ?: nexoId
                ?: return null
            if (!matchesSlot(slot, gearType)) return null
        }

        val itemId = group ?: nexoId?.let { "nexo:$it" } ?: return null
        val name = componentValue(components.local("minecraft:item_name") ?: components.local("minecraft:custom_name"))
            ?.let(richText::fromAdventureJson)
            ?: catalogEntry?.name
            ?: richText.fromPlain(nexoId ?: group.orEmpty())
        val lore = componentList(components.local("minecraft:lore"))
            .map(richText::fromAdventureJson)
            .ifEmpty { catalogEntry?.lore.orEmpty() }
        val attributeMap = (attributes?.toJsonElement() as? kotlinx.serialization.json.JsonObject).orEmpty()
        val maximumDurability = components.int("minecraft:max_damage") ?: components.int("max_damage")
        val damage = components.int("minecraft:damage") ?: components.int("damage") ?: 0
        return EquipmentItem(
            slot = slot,
            itemId = itemId,
            nexoId = nexoId,
            material = material,
            amount = item.int("count") ?: item.int("Count") ?: 1,
            itemUuid = itemUuid,
            rarity = publicValues.string("rarity") ?: attributeString(attributeMap, "rarity"),
            tier = publicValues.string("tier") ?: attributeString(attributeMap, "tier"),
            requiredLevel = publicValues.int("level") ?: attributeInt(attributeMap, "level"),
            durability = maximumDurability?.let { (it - damage).coerceAtLeast(0) }
                ?: attributeInt(attributeMap, "durability"),
            maximumDurability = maximumDurability ?: attributeInt(attributeMap, "durability"),
            repairs = attributeInt(attributeMap, "repaired_amount"),
            name = name,
            lore = lore,
            modifiers = flattenModifiers(attributeMap),
            attributes = attributeMap,
            assets = catalogEntry?.assets,
            tooltipStyle = tooltipStyle(components.string("tooltip_style")),
        )
    }

    private fun matchesSlot(slot: EquipmentSlot, gearType: String): Boolean {
        val normalized = gearType.lowercase(Locale.ROOT)
        return SLOT_GEAR_TYPES[slot]?.any(normalized::contains) ?: true
    }

    private fun tooltipStyle(id: String?): TooltipStyleReference? {
        if (id == null || !TOOLTIP_STYLE_KEY.matches(id)) return null
        val parts = id.split(':', limit = 2)
        val namespace = if (parts.size == 2) parts[0] else "minecraft"
        val style = if (parts.size == 2) parts[1] else parts[0]
        val basePath = "assets/$namespace/textures/gui/sprites/tooltip/$style"
        val backgroundPath = "${basePath}_background.png"
        val framePath = "${basePath}_frame.png"
        val version = catalog.version()
        if (catalog.resolveAsset(version, backgroundPath) == null ||
            catalog.resolveAsset(version, framePath) == null
        ) return null
        return TooltipStyleReference(
            id = if (':' in id) id else "minecraft:$id",
            catalogVersion = version,
            backgroundUrl = "/assets/$version/$backgroundPath",
            frameUrl = "/assets/$version/$framePath",
        )
    }

    private fun decodeBadges(sync: CompoundBinaryTag?): List<BadgePresentation> {
        val badgeRoot = sync?.findCompound("player_badge_data")
            ?.findCompound("player-badge-data")
            ?: return emptyList()
        val selected = badgeRoot.list("selected-badge-ids").stringValues()
        return selected.take(3).mapIndexed { index, id ->
            val entry = catalog.entry(CatalogKind.BADGES, id)
            BadgePresentation(
                id = id,
                slot = index,
                name = entry?.name ?: richText.fromPlain(id),
                lore = entry?.lore.orEmpty(),
                assets = entry?.assets,
            )
        }
    }

    private fun decodePlayerStatistics(
        statisticsJson: String?,
        advancementsJson: String?,
        firstJoined: String?,
        lastJoined: String?,
        profileMenuStatistics: ProfileMenuStatistics,
    ): PlayerStatistics {
        val statistics = statisticsJson?.let(::parseJsonOrNull)
        val generic = statistics?.path("generic")
            ?.takeIf(JsonNode::isObject)
            ?: statistics?.path("genericStatistics")
            ?.takeIf(JsonNode::isObject)
            ?: statistics?.path("generic_statistics")?.takeIf(JsonNode::isObject)
        fun statistic(vararg keys: String): Long {
            val accepted = keys.map { it.lowercase() }.toSet()
            return generic?.fields()?.asSequence()?.firstNotNullOfOrNull { (key, value) ->
                value.takeIf { it.isNumber && key.substringAfter(':').lowercase() in accepted }?.asLong()
            } ?: 0L
        }
        val advancements = advancementsJson?.let(::parseJsonOrNull)
        val advancementCount = when {
            advancements == null -> 0
            advancements.isArray -> advancements.count { advancement ->
                val key = advancement.path("key").asText("")
                key.isNotBlank() && !key.startsWith("minecraft:recipes/")
            }
            advancements.isObject -> advancements.fields().asSequence().count { (_, value) ->
                value.path("completed").asBoolean(value.isObject)
            }
            else -> 0
        }
        return PlayerStatistics(
            advancementCount = advancementCount,
            kills = statistic("player_kills", "PLAYER_KILLS"),
            deaths = statistic("deaths", "DEATHS"),
            firstJoinedAt = firstJoined,
            lastJoinedAt = lastJoined,
            playTimeSeconds = statistic("play_one_minute", "PLAY_ONE_MINUTE", "play_time", "PLAY_TIME") / 20,
            damage = profileMenuStatistics.damage,
            health = profileMenuStatistics.health,
            defense = profileMenuStatistics.defense,
            mana = profileMenuStatistics.mana,
        )
    }

    private fun calculateProfileMenuStatistics(
        sync: CompoundBinaryTag?,
        equipment: List<EquipmentItem>,
        healthJson: String?,
        playerLevel: Int,
    ): ProfileMenuStatistics {
        val rawValues = mutableMapOf<String, Double>()
        val playerAttributes = sync?.findCompound("armory_player")
        PROFILE_ATTRIBUTE_DEFAULTS.keys.forEach { attribute ->
            playerAttributes?.number(attribute)?.toDouble()
                ?.takeIf(Double::isFinite)
                ?.let { rawValues[attribute] = it }
        }

        val modifiers = mutableListOf<RolledArmoryModifier>()
        equipment.filter { isEligibleArmoryItem(it, playerLevel) }.forEach { item ->
            PROFILE_ATTRIBUTE_DEFAULTS.keys.forEach { attribute ->
                directAttributeNumber(item.attributes, attribute)?.let { value ->
                    rawValues[attribute] = rawValues[attribute]?.plus(value) ?: value
                }
            }
            modifiers += rolledArmoryModifiers(item)
        }

        val values = PROFILE_ATTRIBUTE_DEFAULTS.mapValuesTo(mutableMapOf()) { (attribute, fallback) ->
            rawValues[attribute] ?: fallback
        }
        modifiers.sortedBy(RolledArmoryModifier::priority).forEach { modifier ->
            val definition = modifier.definition
            values[definition.attribute] = applyStackingPolicy(
                values.getValue(definition.attribute),
                modifier.value,
                definition.policy,
            )
        }

        applyEquippedTrinket(values, equipment, "life-core", "health")
        applyEquippedTrinket(values, equipment, "mana-heart", "mana")

        val currentHealth = healthJson?.let(::parseJsonOrNull)?.path("health")
            ?.takeIf(JsonNode::isNumber)?.asDouble()
        applyConditionalTalent(values, sync, equipment, currentHealth, "death-wish", "attack_damage")
        applyConditionalTalent(values, sync, equipment, currentHealth, "endurance", "resistance")

        return ProfileMenuStatistics(
            damage = values.getValue("attack_damage"),
            health = values.getValue("health").coerceAtLeast(0.0),
            defense = values.getValue("resistance"),
            mana = values.getValue("mana").coerceAtLeast(0.0),
        )
    }

    private fun rolledArmoryModifiers(item: EquipmentItem): List<RolledArmoryModifier> {
        val group = directAttributeString(item.attributes, "group") ?: return emptyList()
        val standardDefinitions = standardModifierDefinitions(item, group)
        val root = findAttribute(item.attributes, "armory_modifiers") as? JsonObject ?: return emptyList()
        return buildList {
            root.forEach { (rawType, rawValue) ->
                val type = rawType.substringAfter(':')
                val container = rawValue as? JsonObject ?: return@forEach
                val order = jsonLocal(container, "order").primitiveContent()
                    ?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
                val customDefinitions = jsonLocal(container, "custom_definitions") as? JsonObject
                order.forEach { name ->
                    val roll = jsonLocal(container, name).primitiveContent()?.toDoubleOrNull() ?: return@forEach
                    val definition = standardDefinitions[name]
                        ?: customDefinitions?.let { customModifierDefinition(it, name) }
                        ?: return@forEach
                    add(
                        RolledArmoryModifier(
                            definition = definition,
                            value = rollModifier(definition, roll),
                            priority = MODIFIER_PRIORITIES[type] ?: 0,
                        ),
                    )
                }
            }
        }
    }

    private fun standardModifierDefinitions(
        item: EquipmentItem,
        group: String,
    ): Map<String, ArmoryModifierDefinition> {
        val entry = item.nexoId?.let { catalog.entry(CatalogKind.ITEMS, it) }
            ?.takeIf { it.metadata["armoryGroup"].primitiveContent() == group }
            ?: catalog.entry(CatalogKind.ITEMS, group)
            ?: catalog.entries(CatalogKind.ITEMS).firstOrNull {
                it.metadata["armoryGroup"].primitiveContent() == group
            }
        val root = entry?.metadata?.get("armoryModifiers") as? JsonObject ?: return emptyMap()
        return root.mapNotNull { (name, raw) ->
            parseModifierDefinition(raw as? JsonObject)?.let { name to it }
        }.toMap()
    }

    private fun customModifierDefinition(root: JsonObject, name: String): ArmoryModifierDefinition? {
        val definition = jsonLocal(root, name) as? JsonObject ?: return null
        val attribute = jsonLocal(definition, "attribute").primitiveContent() ?: return null
        val policy = jsonLocal(definition, "policy").primitiveContent()?.normalizePolicy() ?: return null
        val serialized = jsonLocal(definition, "value").primitiveContent() ?: return null
        val (minimum, maximum) = parseModifierRange(
            serialized,
            attribute == "resistance" || policy == "add_percentage",
        ) ?: return null
        return ArmoryModifierDefinition(attribute, policy, minimum, maximum)
            .takeIf { it.attribute in PROFILE_ATTRIBUTE_DEFAULTS }
    }

    private fun parseModifierDefinition(value: JsonObject?): ArmoryModifierDefinition? {
        if (value == null) return null
        val attribute = value["attribute"].primitiveContent() ?: return null
        if (attribute !in PROFILE_ATTRIBUTE_DEFAULTS) return null
        return ArmoryModifierDefinition(
            attribute = attribute,
            policy = value["policy"].primitiveContent()?.normalizePolicy() ?: return null,
            minimum = value["minimum"].primitiveContent()?.toDoubleOrNull() ?: return null,
            maximum = value["maximum"].primitiveContent()?.toDoubleOrNull() ?: return null,
        )
    }

    private fun rollModifier(definition: ArmoryModifierDefinition, roll: Double): Double =
        if (definition.attribute == "resistance" || definition.policy == "add_percentage") {
            val minimum = definition.minimum.toFloat()
            val maximum = definition.maximum.toFloat()
            (minimum + (maximum - minimum) * roll.toFloat()).toDouble()
        } else {
            definition.minimum + (definition.maximum - definition.minimum) * roll.toFloat()
        }

    private fun applyEquippedTrinket(
        values: MutableMap<String, Double>,
        equipment: List<EquipmentItem>,
        id: String,
        attribute: String,
    ) {
        val equipped = equipment.any { item ->
            item.nexoId?.normalizeId() == id && item.durability != 0
        }
        if (!equipped) return
        val modifier = catalog.entry(CatalogKind.TALENTS, id)?.levels?.get(1)?.get("value")
            .configurationNumber() ?: return
        values[attribute] = applyStackingPolicy(values.getValue(attribute), modifier, "add_percentage")
    }

    private fun applyConditionalTalent(
        values: MutableMap<String, Double>,
        sync: CompoundBinaryTag?,
        equipment: List<EquipmentItem>,
        currentHealth: Double?,
        id: String,
        attribute: String,
    ) {
        if (currentHealth == null || !currentHealth.isFinite()) return
        val entry = catalog.entry(CatalogKind.TALENTS, id) ?: return
        val metadata = sync?.findCompound("talent_metadata")?.compound("talent-metadata") ?: return
        val baseLevel = metadata.compound(id)?.compound("level")?.int("value") ?: return
        if (baseLevel <= 0) return
        val equipmentBonus = equipment.sumOf { abilityLevelBonus(it.attributes, entry.group) }
        val level = (baseLevel + equipmentBonus).coerceAtMost(entry.maximumLevel ?: Int.MAX_VALUE)
        val settings = entry.levels[level] ?: return
        val threshold = settings["health-amount"].configurationNumber() ?: return
        val mode = settings["mode"].primitiveContent()?.uppercase(Locale.ROOT)?.replace('-', '_') ?: return
        val active = when (mode) {
            "LESS_THAN" -> currentHealth <= threshold
            "MORE_THAN" -> currentHealth >= threshold
            "LESS_THAN_PERCENT" -> currentHealth / values.getValue("health") <= threshold
            "MORE_THAN_PERCENT" -> currentHealth / values.getValue("health") >= threshold
            else -> false
        }
        if (!active) return
        val modifier = settings["value"].configurationNumber() ?: return
        values[attribute] = applyStackingPolicy(values.getValue(attribute), modifier, "add_percentage")
    }

    private fun applyStackingPolicy(current: Double, modifier: Double, policy: String): Double =
        when (policy.normalizePolicy()) {
            "sum" -> current + modifier
            "multiply" -> current * modifier
            "add_percentage" -> current * (1.0 + modifier)
            "min" -> minOf(current, modifier)
            "max" -> maxOf(current, modifier)
            "set_new_value" -> modifier
            else -> current
        }

    private fun isEligibleArmoryItem(item: EquipmentItem, playerLevel: Int): Boolean {
        val broken = directAttribute(item.attributes, "broken").primitiveContent()?.lowercase()
        if (broken == "true" || broken == "1" || broken == "1b") return false
        val itemLevel = item.requiredLevel ?: return true
        val entry = item.nexoId?.let { catalog.entry(CatalogKind.ITEMS, it) } ?: return true
        val bands = entry.metadata["armoryLevelBands"] as? JsonArray ?: return true
        val maximumItemLevel = bands.firstNotNullOfOrNull { rawBand ->
            val band = rawBand as? JsonObject ?: return@firstNotNullOfOrNull null
            val minimum = band["minimum-player-level"].primitiveContent()?.toIntOrNull()
                ?: return@firstNotNullOfOrNull null
            val maximum = band["maximum-player-level"].primitiveContent()?.toIntOrNull()
                ?: return@firstNotNullOfOrNull null
            if (playerLevel !in minimum..maximum) return@firstNotNullOfOrNull null
            band["maximum-item-level"].primitiveContent()?.toIntOrNull()
        } ?: return false
        return itemLevel <= maximumItemLevel
    }

    private fun directAttributeNumber(map: Map<String, JsonElement>, key: String): Double? =
        directAttribute(map, key).primitiveContent()?.toDoubleOrNull()?.takeIf(Double::isFinite)

    private fun directAttributeString(map: Map<String, JsonElement>, key: String): String? =
        directAttribute(map, key).primitiveContent()

    private fun directAttribute(map: Map<String, JsonElement>, key: String): JsonElement? =
        map.entries.firstOrNull { it.key.substringAfter(':') == key }?.value

    private fun jsonLocal(map: JsonObject, key: String): JsonElement? =
        map.entries.firstOrNull { it.key.substringAfter(':') == key }?.value

    private fun JsonElement?.primitiveContent(): String? = (this as? JsonPrimitive)?.content

    private fun JsonElement?.configurationNumber(): Double? {
        val content = primitiveContent()?.trim() ?: return null
        return if (content.endsWith('%')) {
            content.dropLast(1).toDoubleOrNull()?.div(100.0)
        } else {
            content.toDoubleOrNull()
        }
    }

    private fun parseModifierRange(value: String, percentage: Boolean): Pair<Double, Double>? {
        val match = MODIFIER_RANGE.matchEntire(value.trim()) ?: return null
        fun number(index: Int, percentIndex: Int): Double? {
            val raw = match.groupValues[index].takeIf(String::isNotEmpty)?.toDoubleOrNull() ?: return null
            return if (percentage && match.groupValues[percentIndex] == "%") raw / 100.0 else raw
        }
        val minimum = number(1, 2) ?: return null
        return minimum to (number(3, 4) ?: minimum)
    }

    private fun decodeDungeonStatistics(sync: CompoundBinaryTag?): DungeonStatistics {
        val root = sync?.findCompound("player_statistic_data")?.compound("statistics")
            ?: return DungeonStatistics()
        val values = buildMap<String, JsonElement> {
            root.keySet().forEach { key ->
                val holder = root.get(key) as? CompoundBinaryTag ?: return@forEach
                val statistic = holder.string("statistic") ?: key.substringAfter(':')
                put(statistic, holder.local("value")?.toJsonElement() ?: JsonNull)
            }
        }
        return DungeonStatistics(values)
    }

    private fun decodeProgression(
        sync: CompoundBinaryTag?,
        equipment: List<EquipmentItem>,
        kind: CatalogKind,
        syncKey: String,
        metadataKey: String,
        selectedKey: String,
    ): List<ProgressionEntry> {
        val root = sync?.findCompound(syncKey) ?: return emptyList()
        val metadata = root.compound(metadataKey) ?: return emptyList()
        val selected = root.list(selectedKey).stringValues().toSet()
        return catalog.entries(kind).mapNotNull { entry ->
            val ability = metadata.compound(entry.id) ?: return@mapNotNull null
            val level = ability.compound("level")?.int("value") ?: 0
            if (level <= 0 && entry.group !in selected && entry.id !in selected) return@mapNotNull null
            val specialization = entry.group?.let { group ->
                metadata.compound("group-$group")
                    ?.compound("specialization")
                    ?.string("value")
            }
            // Core keeps linked ability levels in every specialization's holder.
            // The group specialization is the authoritative unlocked ability shown
            // by the in-game profile, so omit the other linked holders from the API.
            if (kind == CatalogKind.ABILITIES && specialization != null && entry.id != specialization) {
                return@mapNotNull null
            }
            val maximum = entry.maximumLevel ?: entry.levels.keys.maxOrNull() ?: level.coerceAtLeast(1)
            val equipmentBonus = equipment.sumOf { item -> abilityLevelBonus(item.attributes, entry.group) }
            val effective = (level + equipmentBonus).coerceAtMost(maximum)
            ProgressionEntry(
                id = entry.id,
                group = entry.group,
                specialization = specialization,
                unlocked = true,
                selected = entry.group in selected || entry.id in selected,
                baseLevel = level,
                equipmentBonus = equipmentBonus,
                effectiveLevel = effective,
                maximumLevel = maximum,
                name = entry.name,
                lore = progressionLore(entry, effective, maximum),
                currentValues = entry.levels[effective].orEmpty(),
                nextValues = entry.levels[effective + 1],
                assets = entry.assets,
            )
        }
    }

    private fun progressionLore(entry: CatalogEntry, level: Int, maximumLevel: Int) =
        (entry.metadata["loreTemplates"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            ?.takeIf { it.isNotEmpty() }
            ?.map { template ->
                val substitutions = mapOf(
                    "%name%" to entry.name.plainText,
                    "%level%" to level.toString(),
                    "%max_level%" to maximumLevel.toString(),
                ) + entry.levels[level].orEmpty().mapValues { (_, value) -> progressionValue(value) }
                    .mapKeys { (key, _) -> "%$key%" }
                val rendered = substitutions.entries.fold(template) { text, (placeholder, value) ->
                    text.replace(placeholder, value)
                }
                richText.fromMiniMessage(rendered)
            }
            ?: entry.lore

    private fun progressionValue(value: JsonElement): String =
        (value as? JsonPrimitive)?.content ?: value.toString()

    private fun defaultRank(): RankPresentation = RankPresentation(
        id = "default",
        label = catalog.entry(CatalogKind.RANKS, "default")?.name ?: richText.fromPlain("Player"),
    )

    private fun componentValue(tag: BinaryTag?): String? = when (tag) {
        is StringBinaryTag -> tag.value()
        is CompoundBinaryTag -> Snbt.stringify(tag)
        else -> null
    }

    private fun componentList(tag: BinaryTag?): List<String> =
        (tag as? ListBinaryTag)?.mapNotNull(::componentValue).orEmpty()

    private fun ListBinaryTag?.stringValues(): List<String> =
        this?.mapNotNull { (it as? StringBinaryTag)?.value() }.orEmpty()

    private fun parseSnbtOrNull(value: String): CompoundBinaryTag? =
        try {
            Snbt.parse(value)
        } catch (_: Exception) {
            null
        }

    private fun parseSyncOrNull(value: String): CompoundBinaryTag? =
        parseSnbtOrNull(value)?.findCompound("sync") ?: Snbt.parseCompound(value, "sync")

    private fun mergeMissing(primary: CompoundBinaryTag, fallback: CompoundBinaryTag): CompoundBinaryTag {
        val builder = CompoundBinaryTag.builder()
        val primaryKeys = primary.keySet().associateBy { it.substringAfter(':') }
        primary.keySet().forEach { key ->
            val current = primary.get(key) ?: return@forEach
            val fallbackValue = fallback.keySet()
                .firstOrNull { it.substringAfter(':') == key.substringAfter(':') }
                ?.let(fallback::get)
            builder.put(
                key,
                if (current is CompoundBinaryTag && fallbackValue is CompoundBinaryTag) {
                    mergeMissing(current, fallbackValue)
                } else {
                    current
                },
            )
        }
        fallback.keySet().forEach { key ->
            if (key.substringAfter(':') !in primaryKeys) {
                fallback.get(key)?.let { builder.put(key, it) }
            }
        }
        return builder.build()
    }

    private fun parseJsonOrNull(value: String): JsonNode? =
        try {
            objectMapper.readTree(value)
        } catch (_: Exception) {
            null
        }

    private fun attributeString(map: Map<String, JsonElement>, key: String): String? =
        (findAttribute(map, key) as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

    private fun attributeInt(map: Map<String, JsonElement>, key: String): Int? =
        (findAttribute(map, key) as? kotlinx.serialization.json.JsonPrimitive)
            ?.content
            ?.toIntOrNull()

    private fun findAttribute(map: Map<String, JsonElement>, key: String): JsonElement? {
        map.entries.firstOrNull { it.key.substringAfter(':') == key }?.value?.let { return it }
        return map.values.asSequence()
            .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .firstNotNullOfOrNull { findAttribute(it, key) }
    }

    private fun flattenModifiers(map: Map<String, JsonElement>): Map<String, Double> {
        val root = findAttribute(map, "armory_modifiers") as? kotlinx.serialization.json.JsonObject
            ?: return emptyMap()
        val result = linkedMapOf<String, Double>()
        fun visit(path: String, value: JsonElement) {
            when (value) {
                is kotlinx.serialization.json.JsonObject -> value.forEach { (key, child) ->
                    val local = key.substringAfter(':')
                    visit(if (path.isEmpty()) local else "$path.$local", child)
                }
                is kotlinx.serialization.json.JsonPrimitive -> value.content.toDoubleOrNull()?.let { result[path] = it }
                else -> Unit
            }
        }
        visit("", root)
        return result
    }

    private fun abilityLevelBonus(attributes: Map<String, JsonElement>, group: String?): Int {
        if (group == null) return 0
        val levels = findAttribute(attributes, "ability_level") as? kotlinx.serialization.json.JsonObject ?: return 0
        val groupData = levels.entries.firstOrNull { it.key.substringAfter(':') == group }?.value
            as? kotlinx.serialization.json.JsonObject ?: return 0
        fun sum(value: JsonElement): Int = when (value) {
            is kotlinx.serialization.json.JsonObject -> value.entries.sumOf { (key, child) ->
                if (key.substringAfter(':') == "modifier-value") {
                    (child as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                } else sum(child)
            }
            else -> 0
        }
        return sum(groupData)
    }

    companion object {
        private val TOOLTIP_STYLE_KEY = Regex("^[a-z0-9_.-]+(?::[a-z0-9_./-]+)?$")
        private val PROFILE_ATTRIBUTE_DEFAULTS = mapOf(
            "attack_damage" to 1.0,
            "health" to 20.0,
            "resistance" to 0.0,
            "mana" to 100.0,
        )
        private val MODIFIER_PRIORITIES = mapOf("implicit" to 0, "prefix" to 1, "suffix" to 2)
        private val MODIFIER_RANGE = Regex(
            """^(-?\d+(?:\.\d+)?)\s*(%)?(?:\s*-\s*(-?\d+(?:\.\d+)?)\s*(%)?)?$""",
        )
        private val CORE_SYNC_KEYS = setOf(
            "player_level_data",
            "custom_armory_items",
            "ability_metadata",
            "talent_metadata",
            "armory_player",
            "player_statistic_data",
            "player_badge_data",
        )
        private val CUSTOM_SLOTS = setOf(
            EquipmentSlot.BACKPACK,
            EquipmentSlot.NECKLACE,
            EquipmentSlot.RING,
            EquipmentSlot.MAGNET,
        )
        private val SLOT_GEAR_TYPES = mapOf(
            EquipmentSlot.HEAD to setOf("helmet"),
            EquipmentSlot.CHEST to setOf("chest"),
            EquipmentSlot.LEGS to setOf("leg"),
            EquipmentSlot.FEET to setOf("boot"),
            EquipmentSlot.MAIN_HAND to setOf("sword", "axe"),
            EquipmentSlot.OFF_HAND to setOf("shield"),
        )
    }
}

private fun String.normalizePolicy(): String = lowercase().replace('-', '_')

private fun String.normalizeId(): String = lowercase().replace('_', '-')
