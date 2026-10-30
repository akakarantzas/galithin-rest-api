package com.galithin.web.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.math.max
import kotlin.math.pow

@Serializable
data class RichText(
    val adventureJson: String,
    val plainText: String,
    val sanitizedHtml: String,
    val sourceMiniMessage: String? = null,
)

@Serializable
data class AssetReference(
    val catalogVersion: String,
    val modelUrl: String? = null,
    val equipmentUrl: String? = null,
    val textures: Map<String, String> = emptyMap(),
)

@Serializable
data class TooltipStyleReference(
    val id: String,
    val catalogVersion: String,
    val backgroundUrl: String,
    val frameUrl: String,
)

@Serializable
data class RankPresentation(
    val id: String,
    val label: RichText,
    val prefix: RichText? = null,
    val icon: RichText? = null,
)

@Serializable
data class BadgePresentation(
    val id: String,
    val slot: Int,
    val name: RichText,
    val lore: List<RichText> = emptyList(),
    val assets: AssetReference? = null,
)

@Serializable
data class GuildSummary(
    val id: String,
    val name: String,
    val tag: String?,
    val level: Int,
)

@Serializable
enum class EquipmentSlot {
    HEAD,
    CHEST,
    LEGS,
    FEET,
    BACKPACK,
    NECKLACE,
    RING,
    MAGNET,
    MAIN_HAND,
    OFF_HAND,
}

@Serializable
data class EquipmentItem(
    val slot: EquipmentSlot,
    val itemId: String,
    val nexoId: String?,
    val material: String,
    val amount: Int,
    val itemUuid: String?,
    val rarity: String?,
    val tier: String?,
    val requiredLevel: Int?,
    val durability: Int?,
    val maximumDurability: Int?,
    val repairs: Int?,
    val name: RichText,
    val lore: List<RichText>,
    val modifiers: Map<String, Double> = emptyMap(),
    val attributes: Map<String, JsonElement> = emptyMap(),
    val assets: AssetReference? = null,
    val tooltipStyle: TooltipStyleReference? = null,
)

@Serializable
data class PlayerStatistics(
    val advancementCount: Int,
    val kills: Long,
    val deaths: Long,
    val firstJoinedAt: String?,
    val lastJoinedAt: String?,
    val playTimeSeconds: Long,
    val damage: Double = 1.0,
    val health: Double = 20.0,
    val defense: Double = 0.0,
    val mana: Double = 100.0,
)

@Serializable
data class DungeonStatistics(
    val values: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class ProgressionEntry(
    val id: String,
    val group: String?,
    val specialization: String?,
    val unlocked: Boolean,
    val selected: Boolean,
    val baseLevel: Int,
    val equipmentBonus: Int,
    val effectiveLevel: Int,
    val maximumLevel: Int,
    val name: RichText,
    val lore: List<RichText>,
    val currentValues: Map<String, JsonElement> = emptyMap(),
    val nextValues: Map<String, JsonElement>? = null,
    val assets: AssetReference? = null,
)

@Serializable
data class PlayerProfile(
    val uuid: String,
    val name: String,
    val level: Int,
    val experience: Long,
    val rank: RankPresentation,
    val badges: List<BadgePresentation>,
    val guild: GuildSummary?,
    val equipment: List<EquipmentItem>,
    val statistics: PlayerStatistics,
    val dungeonStatistics: DungeonStatistics,
    val abilities: List<ProgressionEntry>,
    val talents: List<ProgressionEntry>,
)

@Serializable
data class PlayerLeaderboardEntry(
    val position: Long,
    val uuid: String,
    val name: String,
    val level: Int,
    val experience: Long,
    val rank: RankPresentation,
    val badges: List<BadgePresentation>,
    val guild: GuildSummary?,
    val projectedAt: String,
)

@Serializable
data class GuildLeaderboardEntry(
    val position: Long,
    val id: String,
    val name: String,
    val tag: String?,
    val level: Int,
    val totalExperience: Long,
    val ownerUuid: String,
    val memberCount: Int,
    val projectedAt: String,
)

@Serializable
data class GuildProgression(
    val level: Int,
    val currentExperience: Long,
    val requiredExperience: Long,
    val experienceToNextLevel: Long,
    val totalExperience: Long,
    val maximumLevel: Int,
)

@Serializable
data class GuildCapacity(
    val used: Int,
    /** A null limit means the current server configuration does not impose a cap. */
    val limit: Int?,
)

@Serializable
data class GuildMemberSummary(
    val uuid: String,
    val name: String?,
    val role: String,
    val level: Int?,
    val rank: RankPresentation?,
    val badges: List<BadgePresentation> = emptyList(),
    val lastSeenAt: String?,
    val totalContribution: Long,
    val profileAvailable: Boolean,
)

@Serializable
data class GuildProfile(
    val id: String,
    val name: String,
    val tag: String?,
    val ownerUuid: String,
    val progression: GuildProgression,
    val memberCapacity: GuildCapacity,
    val claimCapacity: GuildCapacity,
    val members: List<GuildMemberSummary>,
    val projectedAt: String,
)

@Serializable
data class GuildLevelReward(
    val maximumMembers: Int? = null,
    val maximumClaims: Int? = null,
)

@Serializable
data class GuildCatalog(
    val baseExperienceCost: Long,
    val experienceIncreasePerLevel: Double,
    val maximumLevel: Int,
    val rewards: Map<Int, GuildLevelReward> = emptyMap(),
) {
    fun requiredExperience(level: Int): Long {
        if (level >= maximumLevel) return 0
        val nextLevel = max(2, level + 1)
        return max(1, (baseExperienceCost * experienceIncreasePerLevel.pow(nextLevel - 2)).toLong())
    }

    fun memberLimit(level: Int, bonus: Int): Int? =
        limit(level, bonus, GuildLevelReward::maximumMembers)

    fun claimLimit(level: Int, bonus: Int): Int? =
        limit(level, bonus, GuildLevelReward::maximumClaims)

    private fun limit(level: Int, bonus: Int, select: (GuildLevelReward) -> Int?): Int? {
        val base = rewards.asSequence()
            .filter { (rewardLevel) -> rewardLevel <= level }
            .mapNotNull { (_, reward) -> select(reward) }
            .maxOrNull()
            ?: return null
        return if (bonus > 0) (base.toLong() + bonus).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else base
    }
}

@Serializable
data class CursorPage<T>(
    val entries: List<T>,
    val nextCursor: String?,
)

@Serializable
data class ResponseMeta(
    val apiVersion: String = "v1",
    val catalogVersion: String,
    val sourceSavedAt: String?,
    val projectedAt: String,
    val stale: Boolean,
)

@Serializable
data class ApiResponse<T>(
    val data: T,
    val meta: ResponseMeta,
)

@Serializable
data class ProblemDetail(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
)

@Serializable
data class CatalogEntry(
    val id: String,
    val kind: CatalogKind,
    val name: RichText,
    val lore: List<RichText> = emptyList(),
    val group: String? = null,
    val maximumLevel: Int? = null,
    val levels: Map<Int, Map<String, JsonElement>> = emptyMap(),
    val assets: AssetReference? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
enum class CatalogKind {
    ITEMS,
    ABILITIES,
    TALENTS,
    BADGES,
    RANKS,
}

@Serializable
data class CatalogManifest(
    val schemaVersion: Int,
    val catalogVersion: String,
    val createdAt: String,
    val files: Map<String, String>,
)

data class ProjectionStatus(
    val ready: Boolean,
    val stale: Boolean,
    val catalogVersion: String,
    val sourceSavedAt: String?,
    val projectedAt: String,
    val detail: String? = null,
)
