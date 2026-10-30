package com.galithin.web.sources.luckperms

import com.galithin.web.domain.RankPresentation
import com.galithin.web.sources.core.WebIdentity
import com.galithin.web.sources.database.connection
import com.galithin.web.sources.text.RichTextRenderer
import javax.sql.DataSource

/** Read-only resolver for LuckPerms' existing SQL tables. No plugin-side schema or hook is required. */
class LuckPermsIdentityReader(
    private val dataSource: DataSource,
    private val richText: RichTextRenderer,
    private val tablePrefix: String = "luckperms_",
    private val serverContext: String = "global",
) {
    fun refresh(): Map<String, WebIdentity> {
        require(tablePrefix.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid LuckPerms table prefix" }
        val groupNodes = readNodes("${tablePrefix}group_permissions", "name")
        val userNodes = readNodes("${tablePrefix}user_permissions", "uuid")
        return dataSource.connection { connection ->
            connection.prepareStatement(
                "SELECT uuid, username, primary_group FROM ${tablePrefix}players"
            ).use { statement ->
                statement.executeQuery().use { rows -> buildMap {
                    while (rows.next()) {
                        val uuid = rows.getString("uuid").lowercase()
                        val username = rows.getString("username")
                        val primaryGroup = rows.getString("primary_group")?.ifBlank { "default" } ?: "default"
                        val nodes = inheritedNodes(primaryGroup, groupNodes) + userNodes[uuid].orEmpty()
                        val prefix = highestPrefix(nodes)
                        val icon = lastMeta(nodes, "meta.icon.")
                        put(
                            uuid,
                            WebIdentity(
                                uuid = uuid,
                                username = username,
                                firstJoinedAt = null,
                                lastJoinedAt = null,
                                rank = RankPresentation(
                                    id = primaryGroup,
                                    label = richText.fromPlain(primaryGroup.replace('-', ' ').replaceFirstChar(Char::uppercase)),
                                    prefix = prefix?.let(richText::fromMiniMessage),
                                    icon = icon?.let(richText::fromMiniMessage),
                                ),
                                banned = false,
                            )
                        )
                    }
                } }
            }
        }
    }

    private fun readNodes(table: String, ownerColumn: String): Map<String, List<String>> =
        dataSource.connection { connection ->
            connection.prepareStatement(
                """
                SELECT $ownerColumn AS owner, permission
                FROM $table
                WHERE value = TRUE AND (expiry = 0 OR expiry > UNIX_TIMESTAMP())
                  AND (server = 'global' OR server = ?)
                  AND world = 'global'
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, serverContext)
                statement.executeQuery().use { rows -> buildMap<String, MutableList<String>> {
                    while (rows.next()) {
                        getOrPut(rows.getString("owner").lowercase()) { mutableListOf() }
                            .add(rows.getString("permission"))
                    }
                } }
            }
        }

    private fun inheritedNodes(group: String, groups: Map<String, List<String>>): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(name: String) {
            if (!visited.add(name.lowercase())) return
            val nodes = groups[name.lowercase()].orEmpty()
            result += nodes
            nodes.asSequence().filter { it.startsWith("group.") }
                .map { it.removePrefix("group.") }
                .forEach(::visit)
        }
        visit(group)
        return result
    }

    private fun highestPrefix(nodes: List<String>): String? = nodes.asSequence()
        .filter { it.startsWith("prefix.") }
        .mapNotNull { node ->
            val value = node.removePrefix("prefix.")
            val separator = value.indexOf('.')
            if (separator < 1) null else value.substring(0, separator).toIntOrNull() to value.substring(separator + 1)
        }
        .filter { it.first != null }
        .maxByOrNull { it.first!! }
        ?.second

    private fun lastMeta(nodes: List<String>, prefix: String): String? =
        nodes.lastOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
}
