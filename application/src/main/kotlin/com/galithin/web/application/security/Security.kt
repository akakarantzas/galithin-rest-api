package com.galithin.web.application.security

import com.galithin.web.application.SecurityConfig
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class RequestIdentity(
    val key: String,
    val authenticated: Boolean,
    val invalidCredentials: Boolean,
)

class RequestAuthenticator(
    private val configuredKeyHashes: Set<String>,
    trustedProxyRanges: Collection<String>,
) {
    private val trustedProxies = trustedProxyRanges.mapNotNull(IpRange::parse)

    fun identify(call: ApplicationCall): RequestIdentity {
        val client = clientAddress(call)
        val authorization = call.request.header(HttpHeaders.Authorization)
        if (authorization == null) return RequestIdentity(client, false, false)
        val token = authorization.removePrefix("Bearer ").takeIf { authorization.startsWith("Bearer ") }
            ?: return RequestIdentity(client, false, true)
        val actual = sha256(token)
        val valid = configuredKeyHashes.any { expected ->
            MessageDigest.isEqual(expected.lowercase().toByteArray(), actual.toByteArray())
        }
        return if (valid) RequestIdentity("key:$actual", true, false)
        else RequestIdentity(client, false, true)
    }

    fun clientAddress(call: ApplicationCall): String {
        val remote = call.request.local.remoteHost
        return if (trustedProxies.any { it.contains(remote) }) {
            call.request.header("X-Forwarded-For")?.substringBefore(',')?.trim().orEmpty().ifBlank { remote }
        } else {
            remote
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class TokenBucketRateLimiter(private val config: SecurityConfig) {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun allow(identity: RequestIdentity): Boolean {
        val rate = if (identity.authenticated) config.authenticatedRequestsPerMinute else config.publicRequestsPerMinute
        val burst = if (identity.authenticated) config.authenticatedBurst else config.publicBurst
        val bucket = buckets.computeIfAbsent(identity.key) { Bucket(burst.toDouble(), System.nanoTime()) }
        return bucket.consume(rate / Duration.ofMinutes(1).toSeconds().toDouble(), burst)
    }

    private class Bucket(var tokens: Double, var lastNanos: Long) {
        @Synchronized
        fun consume(tokensPerSecond: Double, capacity: Int): Boolean {
            val now = System.nanoTime()
            val elapsed = (now - lastNanos) / 1_000_000_000.0
            tokens = minOf(capacity.toDouble(), tokens + elapsed * tokensPerSecond)
            lastNanos = now
            if (tokens < 1.0) return false
            tokens -= 1.0
            return true
        }
    }
}

private data class IpRange(val address: ByteArray, val prefixBits: Int) {
    fun contains(value: String): Boolean = try {
        val candidate = InetAddress.getByName(value).address
        if (candidate.size != address.size) return false
        val fullBytes = prefixBits / 8
        val remaining = prefixBits % 8
        if (!(0 until fullBytes).all { candidate[it] == address[it] }) return false
        if (remaining == 0) return true
        val mask = (0xff shl (8 - remaining)) and 0xff
        (candidate[fullBytes].toInt() and mask) == (address[fullBytes].toInt() and mask)
    } catch (_: Exception) {
        false
    }

    companion object {
        fun parse(value: String): IpRange? = try {
            val parts = value.split('/', limit = 2)
            val address = InetAddress.getByName(parts[0]).address
            val bits = parts.getOrNull(1)?.toInt() ?: address.size * 8
            if (bits !in 0..address.size * 8) null else IpRange(address, bits)
        } catch (_: Exception) {
            null
        }
    }
}
