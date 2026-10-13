package com.ayomide.guardian.core

/** Domain block list with subdomain matching ("ads.example.com" blocks "x.ads.example.com"). */
class Blocklist(private val blocked: Set<String>, private val allowed: Set<String> = emptySet()) {

    val size get() = blocked.size

    fun isBlocked(name: String): Boolean {
        val n = name.trim().trimEnd('.').lowercase()
        if (n.isEmpty()) return false
        if (matches(n, allowed)) return false
        return matches(n, blocked)
    }

    private fun matches(name: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var n = name
        while (true) {
            if (n in set) return true
            val dot = n.indexOf('.')
            if (dot < 0 || dot == n.length - 1) return false
            n = n.substring(dot + 1)
            if (n.indexOf('.') < 0) return false // never match a bare TLD like "com"
        }
    }

    companion object {
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")

        /** Parses hosts-file lines ("0.0.0.0 ads.example.com") or plain domain lines. */
        fun parse(text: String, into: MutableSet<String> = HashSet()): MutableSet<String> {
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                val host = when {
                    parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::1" || parts[0] == "::") -> parts[1]
                    parts.size == 1 -> parts[0]
                    else -> continue
                }.lowercase().trimEnd('.')
                if (host in SKIP || !isDomain(host)) continue
                into += host
            }
            return into
        }

        fun isDomain(s: String): Boolean =
            s.length in 3..253 && '.' in s && !s.startsWith('.') &&
                s.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '.' || it == '_' }
    }
}
