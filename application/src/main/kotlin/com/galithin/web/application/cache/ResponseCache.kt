package com.galithin.web.application.cache

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class ResponseCache {
    private data class Entry(val expiresAt: Long, val value: Any)

    private val values = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> get(key: String, ttl: Duration, load: suspend () -> T): T {
        val now = System.nanoTime()
        values[key]?.takeIf { it.expiresAt > now }?.let { return it.value as T }
        val value = load()
        values[key] = Entry(now + ttl.toNanos(), value)
        if (values.size > 10_000) values.entries.removeIf { it.value.expiresAt <= now }
        return value
    }
}
