package app.ewe

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import app.ewe.podcast.NotesReader
import app.ewe.podcast.ReaderService
import app.ewe.podcast.SpokenLine
import app.ewe.podcast.TtsPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The lock-screen / headphone player maps onto the read-aloud: one media item per section. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPlayerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `player state and controls follow the reader`() {
        shadowOf(context as android.app.Application).declareComponentUnbindable(ComponentName(context, ReaderService::class.java))
        val reader = NotesReader(context)
        val player = TtsPlayer(reader)
        idle()
        assertEquals(Player.STATE_IDLE, player.playbackState)

        val lines = listOf(
            SpokenLine("h:a", "Basics.", 0, "Basics"), SpokenLine("a1", "One.", 0, "Basics"),
            SpokenLine("h:b", "Edge.", 2, "Edge"), SpokenLine("b1", "Two.", 2, "Edge"),
            SpokenLine("h:c", "Caching.", 3, "Caching"),
        )
        reader.play("s1", "CDN", lines)
        idle()
        assertEquals(3, player.mediaItemCount)
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue(player.playWhenReady)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertEquals("Basics", player.mediaMetadata.title.toString())
        assertEquals("CDN", player.mediaMetadata.artist.toString())

        player.seekToNext(); idle()            // headphone "next"
        assertEquals("h:b", reader.state.value!!.current!!.key)
        assertEquals(1, player.currentMediaItemIndex)

        player.pause(); idle()                 // headphone "pause"
        assertFalse(reader.state.value!!.playing)
        player.play(); idle()
        assertTrue(reader.state.value!!.playing)

        player.seekTo(2, 0); idle()            // lock-screen jump to an item
        assertEquals("h:c", reader.state.value!!.current!!.key)

        player.stop(); idle()
        assertNull(reader.state.value)
        assertEquals(Player.STATE_IDLE, player.playbackState)
        player.release(); reader.shutdown()
    }
}
