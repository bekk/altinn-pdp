package no.kartverket.altinnpdp.restserver

import java.io.File

/**
 * Configuration lookup: real process environment variables first (how this runs in production),
 * falling back to a `.env` file in the working directory (gitignored, for local dev) so the same
 * [get] call works in both. A real env var always wins, so deployments are never surprised by a
 * stray local `.env`.
 */
object Dotenv {
    private val fileValues: Map<String, String> by lazy { parse(File(".env")) }

    fun get(name: String): String? = System.getenv(name) ?: fileValues[name]

    internal fun parse(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return file.readLines()
            .mapNotNull { line -> parseLine(line) }
            .toMap()
    }

    private fun parseLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val separatorIndex = trimmed.indexOf('=')
        if (separatorIndex < 0) return null
        val key = trimmed.substring(0, separatorIndex).trim()
        val value = unquote(trimmed.substring(separatorIndex + 1).trim())
        return key to value
    }

    private fun unquote(value: String): String {
        if (value.length < 2) return value
        val quote = value.first()
        return if ((quote == '"' || quote == '\'') && value.last() == quote) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}
