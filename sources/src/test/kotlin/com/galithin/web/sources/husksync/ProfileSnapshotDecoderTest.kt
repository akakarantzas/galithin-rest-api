package com.galithin.web.sources.husksync

import com.galithin.web.domain.CatalogEntry
import com.galithin.web.domain.CatalogKind
import com.galithin.web.domain.CatalogRepository
import com.galithin.web.domain.GuildCatalog
import com.galithin.web.domain.EquipmentSlot
import com.galithin.web.domain.RankPresentation
import com.galithin.web.sources.core.CoreDataSnapshot
import com.galithin.web.sources.core.WebIdentity
import com.galithin.web.sources.text.RichTextRenderer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray

class ProfileSnapshotDecoderTest {
    private val renderer = RichTextRenderer({ "test" })
    private val items = listOf(
        CatalogEntry("armory-helmet", CatalogKind.ITEMS, renderer.fromPlain("Test Helmet")),
        CatalogEntry(
            "armory-sword",
            CatalogKind.ITEMS,
            renderer.fromPlain("Test Sword"),
            metadata = armoryMetadata(
                "armory-sword",
                "attack_damage_t1" to modifier("attack_damage", "sum", 40.0, 50.0),
                "health_t1" to modifier("health", "sum", 10.0, 10.0),
                "resistance_t1" to modifier("resistance", "sum", 0.1, 0.2),
                "mana_t1" to modifier("mana", "sum", 20.0, 20.0),
                "mana_percentage_t1" to modifier("mana", "add_percentage", 0.25, 0.25),
            ),
        ),
        customSlotEntry("backpack", "Backpack", EquipmentSlot.BACKPACK),
        customSlotEntry("soul_mark", "Soul Mark", EquipmentSlot.NECKLACE),
        customSlotEntry("life_core", "Life Core", EquipmentSlot.NECKLACE),
        customSlotEntry("mana_heart", "Mana Heart", EquipmentSlot.NECKLACE),
        customSlotEntry("sky_ring", "Sky Ring", EquipmentSlot.RING),
        customSlotEntry("magnet", "Magnet", EquipmentSlot.MAGNET),
        CatalogEntry("samurai_chestplate", CatalogKind.ITEMS, renderer.fromPlain("Samurai Chestplate")),
    )
    private val abilities = listOf(
        CatalogEntry(
            "dash",
            CatalogKind.ABILITIES,
            renderer.fromPlain("Dash"),
            group = "dash",
            maximumLevel = 10,
            levels = mapOf(
                3 to mapOf(
                    "ui-distance" to JsonPrimitive(6),
                    "ui-cooldown" to JsonPrimitive("12s"),
                    "mana-cost" to JsonPrimitive(11),
                ),
            ),
            metadata = mapOf(
                "loreTemplates" to buildJsonArray {
                    add(JsonPrimitive("Level: %level%/%max_level%"))
                    add(JsonPrimitive("Distance: %ui-distance%"))
                    add(JsonPrimitive("Cooldown: %ui-cooldown%"))
                    add(JsonPrimitive("Mana Cost: %mana-cost%"))
                },
            ),
        ),
        CatalogEntry(
            "warp",
            CatalogKind.ABILITIES,
            renderer.fromPlain("Warp"),
            group = "dash",
            maximumLevel = 10,
            levels = mapOf(3 to mapOf("distance" to JsonPrimitive(6))),
        ),
        CatalogEntry(
            "bullet",
            CatalogKind.ABILITIES,
            renderer.fromPlain("Bullet"),
            group = "dash",
            maximumLevel = 10,
            levels = mapOf(3 to mapOf("distance" to JsonPrimitive(6))),
        ),
    )
    private val talents = listOf(
        CatalogEntry(
            "adrenaline",
            CatalogKind.TALENTS,
            renderer.fromPlain("Adrenaline"),
            group = "talent-adrenaline",
            maximumLevel = 4,
            levels = mapOf(2 to mapOf("display-max-stacks" to JsonPrimitive("6"))),
            metadata = mapOf(
                "loreTemplates" to buildJsonArray {
                    add(JsonPrimitive("Level: %level%/%max_level%"))
                    add(JsonPrimitive("Max Stacks: %display-max-stacks%"))
                },
            ),
        ),
        CatalogEntry(
            "death-wish",
            CatalogKind.TALENTS,
            renderer.fromPlain("Death Wish"),
            group = "talent-death-wish",
            maximumLevel = 4,
            levels = mapOf(
                1 to mapOf(
                    "value" to JsonPrimitive(0.2),
                    "health-amount" to JsonPrimitive(0.2),
                    "mode" to JsonPrimitive("LESS_THAN_PERCENT"),
                ),
            ),
        ),
        CatalogEntry(
            "endurance",
            CatalogKind.TALENTS,
            renderer.fromPlain("Endurance"),
            group = "talent-endurance",
            maximumLevel = 4,
            levels = mapOf(
                1 to mapOf(
                    "value" to JsonPrimitive(0.1),
                    "health-amount" to JsonPrimitive(0.2),
                    "mode" to JsonPrimitive("LESS_THAN_PERCENT"),
                ),
            ),
        ),
        CatalogEntry(
            "life-core",
            CatalogKind.TALENTS,
            renderer.fromPlain("Life Core"),
            maximumLevel = 1,
            levels = mapOf(1 to mapOf("value" to JsonPrimitive("25%"))),
        ),
        CatalogEntry(
            "mana-heart",
            CatalogKind.TALENTS,
            renderer.fromPlain("Mana Heart"),
            maximumLevel = 1,
            levels = mapOf(1 to mapOf("value" to JsonPrimitive("100%"))),
        ),
    )
    private val catalog = object : CatalogRepository {
        override fun version() = "test"
        override fun entries(kind: CatalogKind) = when (kind) {
            CatalogKind.ITEMS -> items
            CatalogKind.ABILITIES -> abilities
            CatalogKind.TALENTS -> talents
            else -> emptyList()
        }
        override fun entry(kind: CatalogKind, id: String) = entries(kind).firstOrNull { it.id == id }
        override fun guilds() = GuildCatalog(1_000, 1.15, 100)
        override fun resolveAsset(version: String, relativePath: String) =
            if (version == "test" && relativePath.startsWith("assets/minecraft/textures/gui/sprites/tooltip/")) {
                Path.of(relativePath)
            } else {
                null
            }
        override fun ready() = true
    }

    @Test
    fun `keeps validated Galithin equipment and omits vanilla slots`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:39,id:"minecraft:diamond_helmet",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-helmet","galithin-core:uuid":"00000000-0000-0000-0000-000000000001","galithin-core:armory-item-attributes":{"galithin-core:group":"armory-helmet"}}}}},
              {Slot:0,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-sword","galithin-core:uuid":"00000000-0000-0000-0000-000000000002","galithin-core:armory-item-attributes":{"galithin-core:group":"armory-sword"}}}}},
              {Slot:40,id:"minecraft:shield",count:1},
              {Slot:38,id:"minecraft:diamond_chestplate",count:1}
            ]},held_item_slot:0}
            """.trimIndent()
        val snapshot = DecodedSnapshot(
            5,
            "1.21.11",
            mapOf(
                "inventory" to inventory,
                "persistent_data" to "{}",
                "statistics" to """{"genericStatistics":{"minecraft:player_kills":7,"minecraft:deaths":2}}""",
            ),
        )
        val row = PackedSnapshotRow(
            "00000000-0000-0000-0000-000000000099",
            "00000000-0000-0000-0000-000000000010",
            "Tester",
            "2026-01-01T00:00:00Z",
            "2026-01-02T00:00:00Z",
            byteArrayOf(),
        )

        val profile = ProfileSnapshotDecoder(catalog, renderer, EligibilityRules())
            .decode(row, snapshot, CoreDataSnapshot.empty()).profile

        assertEquals(setOf(EquipmentSlot.HEAD, EquipmentSlot.MAIN_HAND), profile.equipment.map { it.slot }.toSet())
        assertTrue(profile.equipment.all { it.itemUuid != null })
        assertEquals(7, profile.statistics.kills)
        assertEquals(2, profile.statistics.deaths)
        assertEquals("2026-01-01T00:00:00Z", profile.statistics.firstJoinedAt)
    }

    @Test
    fun `only returns rolled gear from its matching equipment slot`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:39,id:"minecraft:diamond_boots",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-boots","galithin-core:armory-item-attributes":{}}}}},
              {Slot:38,id:"minecraft:diamond_helmet",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-helmet","galithin-core:armory-item-attributes":{}}}}},
              {Slot:37,id:"minecraft:diamond_chestplate",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-chestplate","galithin-core:armory-item-attributes":{}}}}},
              {Slot:36,id:"minecraft:diamond_leggings",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-leggings","galithin-core:armory-item-attributes":{}}}}},
              {Slot:0,id:"minecraft:shield",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-shield","galithin-core:armory-item-attributes":{}}}}},
              {Slot:40,id:"minecraft:diamond_axe",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-axe","galithin-core:armory-item-attributes":{}}}}}
            ]},held_item_slot:0}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("inventory" to inventory, "persistent_data" to "{}")),
            CoreDataSnapshot.empty(),
        ).profile

        assertTrue(profile.equipment.isEmpty())
    }

    @Test
    fun `allows swords and axes in main hand and shields in off hand`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:0,id:"minecraft:diamond_axe",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-axe","galithin-core:armory-item-attributes":{}}}}},
              {Slot:40,id:"minecraft:shield",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:group":"armory-shield","galithin-core:armory-item-attributes":{}}}}}
            ]},held_item_slot:0}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("inventory" to inventory, "persistent_data" to "{}")),
            CoreDataSnapshot.empty(),
        ).profile

        assertEquals(
            setOf(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND),
            profile.equipment.map { it.slot }.toSet(),
        )
    }

    @Test
    fun `uses the Nexo id to reject legacy rolled gear in the wrong slot`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:39,id:"minecraft:chainmail_chestplate",count:1,components:{
                "minecraft:custom_data":{PublicBukkitValues:{
                  "nexo:id":"samurai_chestplate",
                  "galithin-core:armory-item-attributes":{}
                }}
              }}
            ]},held_item_slot:0}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("inventory" to inventory, "persistent_data" to "{}")),
            CoreDataSnapshot.empty(),
        ).profile

        assertTrue(profile.equipment.isEmpty())
    }

    @Test
    fun `projects catalogued custom slots without rolled armory markers`() {
        val backpack =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:backpack-data":{"galithin-core:template-id":"backpack"},"galithin-core:uuid":"1c65ec52-8f86-4dbc-b5da-47981a89bb08","nexo:id":"backpack"}},"minecraft:item_name":"Backpack"},count:1,id:"minecraft:leather_horse_armor"}"""
        val necklace =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:trinket-data":{"galithin-core:trinket-name":"soul-mark","galithin-core:uses":17},"nexo:id":"soul_mark"}},"minecraft:item_name":"Soul Mark","minecraft:max_damage":17,"minecraft:damage":0},count:1,id:"minecraft:paper"}"""
        val ring =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:trinket-data":{"galithin-core:trinket-name":"sky-ring","galithin-core:uses":9},"nexo:id":"sky_ring"}},"minecraft:item_name":"Sky Ring","minecraft:max_damage":9,"minecraft:damage":0},count:1,id:"minecraft:paper"}"""
        val persistentData =
            """
            {"galithin-core:sync":{"galithin-core:custom_armory_items":{
              "galithin-core:backpack":'$backpack',
              "galithin-core:necklace":'$necklace',
              "galithin-core:ring":'$ring'
            }}}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("persistent_data" to persistentData)),
            CoreDataSnapshot.empty(),
        ).profile

        assertEquals(
            setOf(EquipmentSlot.BACKPACK, EquipmentSlot.NECKLACE, EquipmentSlot.RING),
            profile.equipment.map { it.slot }.toSet(),
        )
        assertEquals(
            setOf("nexo:backpack", "nexo:soul_mark", "nexo:sky_ring"),
            profile.equipment.map { it.itemId }.toSet(),
        )
        assertEquals("1c65ec52-8f86-4dbc-b5da-47981a89bb08", profile.equipment.first().itemUuid)
        assertTrue(profile.equipment.filter { it.slot != EquipmentSlot.BACKPACK }.all { it.itemUuid == null })
    }

    @Test
    fun `rejects catalogued custom gear from the wrong dedicated slots`() {
        val backpack =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"nexo:id":"backpack"}}},count:1,id:"minecraft:leather_horse_armor"}"""
        val necklace =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"nexo:id":"soul_mark"}}},count:1,id:"minecraft:paper"}"""
        val ring =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"nexo:id":"sky_ring"}}},count:1,id:"minecraft:paper"}"""
        val magnet =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"nexo:id":"magnet"}}},count:1,id:"minecraft:stick"}"""
        val persistentData =
            """
            {"galithin-core:sync":{"galithin-core:custom_armory_items":{
              "galithin-core:backpack":'$magnet',
              "galithin-core:necklace":'$ring',
              "galithin-core:ring":'$backpack',
              "galithin-core:magnet":'$necklace'
            }}}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("persistent_data" to persistentData)),
            CoreDataSnapshot.empty(),
        ).profile

        assertTrue(profile.equipment.isEmpty())
    }

    @Test
    fun `projects legacy rolled gear when group and UUID are absent`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:38,id:"minecraft:chainmail_chestplate",count:1,components:{
                "minecraft:custom_data":{PublicBukkitValues:{
                  "nexo:id":"samurai_chestplate",
                  "galithin-core:armory-item-attributes":{
                    "galithin-core:item_template_data":{"galithin-core:template":"samurai_chestplate","galithin-core:rarity":"mythic"}
                  }
                }},
                "minecraft:item_name":"Samurai Chestplate"
              }}
            ]},held_item_slot:0}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("inventory" to inventory, "persistent_data" to "{}")),
            CoreDataSnapshot.empty(),
        ).profile

        assertEquals(listOf(EquipmentSlot.CHEST), profile.equipment.map { it.slot })
        assertEquals("nexo:samurai_chestplate", profile.equipment.single().itemId)
        assertEquals("samurai_chestplate", profile.equipment.single().nexoId)
        assertEquals(null, profile.equipment.single().itemUuid)
    }

    @Test
    fun `returns saved tooltip style assets for rolled gear`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:38,id:"minecraft:chainmail_chestplate",count:1,components:{
                "minecraft:tooltip_style":"minecraft:mythic",
                "minecraft:custom_data":{PublicBukkitValues:{
                  "nexo:id":"samurai_chestplate",
                  "galithin-core:armory-item-attributes":{
                    "galithin-core:item_template_data":{"galithin-core:template":"samurai_chestplate","galithin-core:rarity":"mythic"}
                  }
                }}
              }}
            ]},held_item_slot:0}
            """.trimIndent()

        val item = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("inventory" to inventory, "persistent_data" to "{}")),
            CoreDataSnapshot.empty(),
        ).profile.equipment.single()

        assertEquals("minecraft:mythic", item.tooltipStyle?.id)
        assertEquals(
            "/assets/test/assets/minecraft/textures/gui/sprites/tooltip/mythic_background.png",
            item.tooltipStyle?.backgroundUrl,
        )
        assertEquals(
            "/assets/test/assets/minecraft/textures/gui/sprites/tooltip/mythic_frame.png",
            item.tooltipStyle?.frameUrl,
        )
    }

    @Test
    fun `rejects uncatalogued Nexo values in dedicated custom slots`() {
        val unknown =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"nexo:id":"not_public_gear"}}},count:1,id:"minecraft:paper"}"""
        val persistentData =
            """{"galithin-core:sync":{"galithin-core:custom_armory_items":{"galithin-core:ring":'$unknown'}}}"""

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("persistent_data" to persistentData)),
            CoreDataSnapshot.empty(),
        ).profile

        assertTrue(profile.equipment.isEmpty())
    }

    @Test
    fun `recovers namespaced Core sync data when another PDC value is not valid typed SNBT`() {
        val customItem =
            """{id:"minecraft:paper",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{"galithin-core:trinket-data":{"galithin-core:trinket-name":"sky-ring"},"nexo:id":"sky_ring"}}}}"""
        val persistentData =
            """
            {"other-plugin:mixed":[1,{text:"invalid typed list"}],"galithin-core:sync":{
              "galithin-core:player_level_data":{"galithin-core:level":42,"galithin-core:experience":1337L},
              "galithin-core:custom_armory_items":{"galithin-core:ring":'$customItem'},
              "galithin-core:player_statistic_data":{"galithin-core:statistics":{
                "galithin-core:dungeons_played":{"galithin-core:statistic":"dungeons_played","galithin-core:value":7}
              }}
            }}
            """.trimIndent()
        val snapshot = DecodedSnapshot(
            5,
            "1.21.11",
            mapOf("husksync:persistent_data" to persistentData),
        )

        val profile = decoder().decode(row(), snapshot, CoreDataSnapshot.empty()).profile

        assertEquals(42, profile.level)
        assertEquals(1337L, profile.experience)
        assertEquals(setOf(EquipmentSlot.RING), profile.equipment.map { it.slot }.toSet())
        assertEquals(JsonPrimitive(7), profile.dungeonStatistics.values["dungeons_played"])
    }

    @Test
    fun `sanitizes mixed lore components and decodes HuskSync statistics shapes`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:0,id:"minecraft:diamond_sword",count:1,components:{
                "minecraft:lore":[{extra:["1516",{extra:["1516"],text:"/"}],text:"Durability: "}],
                "minecraft:custom_data":{PublicBukkitValues:{
                  "galithin-core:group":"armory-sword",
                  "galithin-core:uuid":"00000000-0000-0000-0000-000000000002",
                  "galithin-core:armory-item-attributes":{"galithin-core:group":"armory-sword"}
                }}
              }}
            ]},held_item_slot:0}
            """.trimIndent()
        val snapshot = DecodedSnapshot(
            5,
            "1.21.11",
            mapOf(
                "inventory" to inventory,
                "persistent_data" to "{}",
                "statistics" to """{"generic":{"player_kills":7,"deaths":2,"play_one_minute":400}}""",
                "advancements" to """[
                    {"key":"minecraft:story/mine_stone","completedCriteria":{"stone":1}},
                    {"key":"minecraft:recipes/tools/stone_pickaxe","completedCriteria":{"has_stone":1}}
                ]""",
            ),
        )

        val profile = decoder().decode(row(), snapshot, CoreDataSnapshot.empty()).profile

        assertEquals(setOf(EquipmentSlot.MAIN_HAND), profile.equipment.map { it.slot }.toSet())
        assertEquals(7L, profile.statistics.kills)
        assertEquals(2L, profile.statistics.deaths)
        assertEquals(20L, profile.statistics.playTimeSeconds)
        assertEquals(1, profile.statistics.advancementCount)
    }

    @Test
    fun `projects the derived stats shown by the Core profile menu`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:0,id:"minecraft:diamond_sword",count:1,components:{
                "minecraft:custom_data":{PublicBukkitValues:{
                  "galithin-core:group":"armory-sword",
                  "galithin-core:armory-item-attributes":{
                    "galithin-core:group":"armory-sword",
                    "galithin-core:armory_modifiers":{
                      "galithin-core:implicit":{
                        "galithin-core:attack_damage_t1":0.5f,
                        "galithin-core:order":"attack_damage_t1",
                        "galithin-core:slots":1
                      },
                      "galithin-core:prefix":{
                        "galithin-core:health_t1":0.9f,
                        "galithin-core:resistance_t1":0.5f,
                        "galithin-core:custom_damage":0.1f,
                        "galithin-core:order":"health_t1,resistance_t1,custom_damage",
                        "galithin-core:slots":3,
                        "galithin-core:custom_definitions":{
                          "galithin-core:custom_damage":{
                            "galithin-core:attribute":"attack_damage",
                            "galithin-core:policy":"add_percentage",
                            "galithin-core:value":"20%"
                          }
                        }
                      },
                      "galithin-core:suffix":{
                        "galithin-core:mana_t1":0.4f,
                        "galithin-core:mana_percentage_t1":0.7f,
                        "galithin-core:order":"mana_t1,mana_percentage_t1",
                        "galithin-core:slots":2
                      }
                    }
                  }
                }}
              }}
            ]},held_item_slot:0}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("inventory" to inventory, "persistent_data" to "{}")),
            CoreDataSnapshot.empty(),
        ).profile

        assertEquals(55.2, profile.statistics.damage, 0.000001)
        assertEquals(30.0, profile.statistics.health)
        assertEquals(0.15, profile.statistics.defense, 0.000001)
        assertEquals(150.0, profile.statistics.mana, 0.000001)
    }

    @Test
    fun `uses stable profile stat defaults when the snapshot has no armory values`() {
        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("persistent_data" to "{}")),
            CoreDataSnapshot.empty(),
        ).profile

        assertEquals(1.0, profile.statistics.damage)
        assertEquals(20.0, profile.statistics.health)
        assertEquals(0.0, profile.statistics.defense)
        assertEquals(100.0, profile.statistics.mana)
    }

    @Test
    fun `applies equipped stat trinkets and active conditional talents from existing snapshot data`() {
        val lifeCore =
            """{components:{"minecraft:custom_data":{PublicBukkitValues:{"nexo:id":"life_core"}},"minecraft:max_damage":10,"minecraft:damage":0},count:1,id:"minecraft:paper"}"""
        val persistentData =
            """
            {"galithin-core:sync":{
              "galithin-core:armory_player":{"galithin-core:resistance":0.1f},
              "galithin-core:custom_armory_items":{"galithin-core:necklace":'$lifeCore'},
              "galithin-core:talent_metadata":{"galithin-core:talent-metadata":{
                "galithin-core:death-wish":{"galithin-core:level":{"galithin-core:value":1}},
                "galithin-core:endurance":{"galithin-core:level":{"galithin-core:value":1}}
              }}
            }}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(
                5,
                "1.21.11",
                mapOf(
                    "persistent_data" to persistentData,
                    "health" to """{"health":3.0,"health_scale":20.0,"is_health_scaled":false}""",
                ),
            ),
            CoreDataSnapshot.empty(),
        ).profile

        assertEquals(1.2, profile.statistics.damage, 0.000001)
        assertEquals(25.0, profile.statistics.health, 0.000001)
        assertEquals(0.11, profile.statistics.defense, 0.000001)
        assertEquals(100.0, profile.statistics.mana)
    }

    @Test
    fun `does not apply modifiers from gear outside the player level band`() {
        val inventory =
            """
            {items:{size:41,items:[
              {Slot:0,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_data":{PublicBukkitValues:{
                "nexo:id":"armory-sword",
                "galithin-core:group":"armory-sword",
                "galithin-core:armory-item-attributes":{
                  "galithin-core:group":"armory-sword",
                  "galithin-core:level":50,
                  "galithin-core:armory_modifiers":{"galithin-core:implicit":{
                    "galithin-core:attack_damage_t1":0.5f,
                    "galithin-core:order":"attack_damage_t1",
                    "galithin-core:slots":1
                  }}
                }
              }}}}
            ]},held_item_slot:0}
            """.trimIndent()

        val profile = decoder().decode(
            row(),
            DecodedSnapshot(5, "1.21.11", mapOf("inventory" to inventory, "persistent_data" to "{}")),
            CoreDataSnapshot.empty(),
        ).profile

        assertEquals(1.0, profile.statistics.damage)
    }

    @Test
    fun `backfills Core sync data from an older snapshot`() {
        val current = DecodedSnapshot(5, "1.21.11", mapOf("persistent_data" to "{}"))
        val older = DecodedSnapshot(
            5,
            "1.21.11",
            mapOf(
                "persistent_data" to
                    """{"galithin-core:sync":{"galithin-core:player_level_data":{"galithin-core:level":36,"galithin-core:experience":900L}}}""",
            ),
        )

        assertTrue(decoder().needsCoreFallback(current))
        val profile = decoder().decode(row(), current, CoreDataSnapshot.empty(), listOf(older)).profile

        assertEquals(36, profile.level)
        assertEquals(900L, profile.experience)
    }

    @Test
    fun `uses canonical HuskSync player name casing instead of normalized LuckPerms casing`() {
        val row = row().copy(username = "Gnarly")
        val identity = WebIdentity(
            uuid = row.playerUuid,
            username = "gnarly",
            firstJoinedAt = null,
            lastJoinedAt = null,
            rank = RankPresentation("default", renderer.fromPlain("Player")),
            banned = false,
        )

        val profile = decoder().decode(
            row,
            DecodedSnapshot(5, "1.21.11", mapOf("persistent_data" to "{}")),
            CoreDataSnapshot(mapOf(row.playerUuid to identity), emptyMap(), emptyList()),
        ).profile

        assertEquals("Gnarly", profile.name)
    }

    @Test
    fun `projects ability and talent metadata from their current Core containers`() {
        val persistentData =
            """
            {"galithin-core:sync":{
              "galithin-core:ability_metadata":{
                "galithin-core:ability-metadata":{
                  "galithin-core:group-dash":{"galithin-core:specialization":{"galithin-core:expiry":0L,"galithin-core:value":"dash"}},
                  "galithin-core:dash":{"galithin-core:level":{"galithin-core:expiry":0L,"galithin-core:value":3}},
                  "galithin-core:warp":{"galithin-core:level":{"galithin-core:expiry":0L,"galithin-core:value":3}},
                  "galithin-core:bullet":{"galithin-core:level":{"galithin-core:expiry":0L,"galithin-core:value":3}}
                },
                "galithin-core:quick-abilities":["dash"],
                "galithin-core:quick-ability-index":0,
                "galithin-core:skill-points":2
              },
              "galithin-core:talent_metadata":{
                "galithin-core:talent-metadata":{
                  "galithin-core:group-talent-adrenaline":{"galithin-core:specialization":{"galithin-core:expiry":0L,"galithin-core:value":"adrenaline"}},
                  "galithin-core:adrenaline":{"galithin-core:level":{"galithin-core:expiry":0L,"galithin-core:value":2}}
                },
                "galithin-core:unlocked-talents":["talent-adrenaline"],
                "galithin-core:migrated-from-ability-metadata":1b
              }
            }}
            """.trimIndent()
        val snapshot = DecodedSnapshot(5, "1.21.11", mapOf("persistent_data" to persistentData))

        val profile = decoder().decode(row(), snapshot, CoreDataSnapshot.empty()).profile

        assertEquals(listOf("dash"), profile.abilities.map { it.id })
        assertEquals(3, profile.abilities.single().baseLevel)
        assertEquals("dash", profile.abilities.single().specialization)
        assertTrue(profile.abilities.single().selected)
        assertEquals(JsonPrimitive(6), profile.abilities.single().currentValues["ui-distance"])
        assertEquals(
            listOf("Level: 3/10", "Distance: 6", "Cooldown: 12s", "Mana Cost: 11"),
            profile.abilities.single().lore.map { it.plainText },
        )
        assertEquals(listOf("adrenaline"), profile.talents.map { it.id })
        assertEquals(2, profile.talents.single().baseLevel)
        assertEquals("adrenaline", profile.talents.single().specialization)
        assertTrue(profile.talents.single().selected)
        assertEquals(JsonPrimitive("6"), profile.talents.single().currentValues["display-max-stacks"])
        assertEquals(listOf("Level: 2/4", "Max Stacks: 6"), profile.talents.single().lore.map { it.plainText })
    }

    private fun decoder() = ProfileSnapshotDecoder(catalog, renderer, EligibilityRules())

    private fun customSlotEntry(id: String, name: String, slot: EquipmentSlot) = CatalogEntry(
        id,
        CatalogKind.ITEMS,
        renderer.fromPlain(name),
        metadata = mapOf("equipmentSlot" to JsonPrimitive(slot.name)),
    )

    private fun modifier(
        attribute: String,
        policy: String,
        minimum: Double,
        maximum: Double,
    ) = JsonObject(
        mapOf(
            "attribute" to JsonPrimitive(attribute),
            "policy" to JsonPrimitive(policy),
            "minimum" to JsonPrimitive(minimum),
            "maximum" to JsonPrimitive(maximum),
        ),
    )

    private fun armoryMetadata(
        group: String,
        vararg modifiers: Pair<String, JsonObject>,
    ) = mapOf(
        "armoryGroup" to JsonPrimitive(group),
        "armoryLevelBands" to buildJsonArray {
            add(
                JsonObject(
                    mapOf(
                        "minimum-player-level" to JsonPrimitive(1),
                        "maximum-player-level" to JsonPrimitive(14),
                        "maximum-item-level" to JsonPrimitive(14),
                    ),
                ),
            )
        },
        "armoryModifiers" to JsonObject(modifiers.toMap()),
    )

    private fun row() = PackedSnapshotRow(
        "00000000-0000-0000-0000-000000000099",
        "00000000-0000-0000-0000-000000000010",
        "Tester",
        "2026-01-01T00:00:00Z",
        "2026-01-02T00:00:00Z",
        byteArrayOf(),
    )
}
