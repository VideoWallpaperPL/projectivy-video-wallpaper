package io.projectyt.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeUrlTest {
    @Test
    fun acceptsBareIdsAndYouTubeLinkVariants() {
        listOf(
            ID,
            "  $ID  ",
            "https://youtube.com/watch?v=$ID",
            "https://m.youtube.com/watch?t=20&v=$ID&list=unused",
            "https://youtu.be/$ID?si=unused",
            "https://youtube.com/shorts/$ID",
            "https://www.youtube.com/embed/$ID",
            "https://www.youtube.com/live/$ID",
        ).forEach { input -> assertEquals(input, WATCH_URL, YouTubeUrl.canonical(input)) }
    }

    @Test
    fun handlesSharedTextAndStripsTrackingParameters() {
        assertEquals(WATCH_URL, YouTubeUrl.canonical("A wallpaper\n(https://youtu.be/$ID?si=example)."))
        assertEquals(WATCH_URL, YouTubeUrl.canonical("https://www.youtube.com/watch?v=%61${ID.drop(1)}&t=5"))
    }

    @Test
    fun rejectsNonMediaLinksAndUnexpectedOrigins() {
        listOf(
            "",
            "abc",
            "https://youtube.com/playlist?list=$ID",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?v=too-short",
            "https://youtube.com/watch?v=$ID%20",
            "https://example.com/watch?v=$ID",
            "https://youtube.com.example.com/watch?v=$ID",
            "https://example.com@youtube.com/watch?v=$ID",
            "https://youtube.com:8443/watch?v=$ID",
            "https://youtu.be/$ID/extra",
        ).forEach { input -> assertThrows(input, IllegalArgumentException::class.java) { YouTubeUrl.canonical(input) } }
    }

    private companion object {
        const val ID = "a1234_-ABCD"
        const val WATCH_URL = "https://www.youtube.com/watch?v=$ID"
    }
}
