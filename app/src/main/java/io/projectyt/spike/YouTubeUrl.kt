package io.projectyt.spike

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object YouTubeUrl {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val links = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

    fun canonical(input: String): String {
        val trimmed = input.trim()
        val id = trimmed.takeIf(videoId::matches)
            ?: links.findAll(trimmed).mapNotNull { idFromLink(it.value) }.firstOrNull()
        require(id != null) { "Enter a YouTube link or an 11-character video ID." }
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun idFromLink(link: String): String? {
        val uri = runCatching { URI(link.trimEnd('.', ',', ')', ']', '!', '"', '\'')) }.getOrNull()
            ?: return null
        if (uri.userInfo != null || uri.port != -1) return null
        val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
        val id = when (uri.host?.lowercase()) {
            "youtube.com", "www.youtube.com", "m.youtube.com" -> when {
                uri.path == "/watch" -> uri.rawQuery.orEmpty().split('&')
                    .firstOrNull { it.substringBefore('=') == "v" }
                    ?.substringAfter('=', missingDelimiterValue = "")
                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                segments.size == 2 && segments[0] in listOf("shorts", "embed", "live") -> segments[1]
                else -> null
            }
            "youtu.be" -> segments.singleOrNull()
            else -> null
        }
        return id?.takeIf(videoId::matches)
    }
}
