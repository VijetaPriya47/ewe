package app.ewe.podcast

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.ewe.MainActivity
import java.io.File

/**
 * Plays the prepared episode in the background (screen off, headphones, lock-screen controls).
 * Each chapter is one or more audio files; a chapter that plays to its end is remembered as heard.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .build()
        player.addListener(object : Player.Listener {
            private var last: MediaItem? = null
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = last
                last = mediaItem
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != null) finished(previous, mediaItem)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) last?.let { finished(it, null) }
            }
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "revision").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(object : MediaSession.Callback {
            // A controller only sends ids; the audio files are looked up here.
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>)
                : com.google.common.util.concurrent.ListenableFuture<MutableList<MediaItem>> {
                val all = EpisodeStore.get(this@PlaybackService).episode.value?.let { items(it) }.orEmpty().associateBy { it.mediaId }
                return com.google.common.util.concurrent.Futures.immediateFuture(mediaItems.mapNotNull { all[it.mediaId] }.toMutableList())
            }
        }).build()
    }

    /** [done] played to its end; if the next file belongs to another chapter, the chapter was heard. */
    private fun finished(done: MediaItem, next: MediaItem?) {
        val id = scheduleIdOf(done) ?: return
        if (next != null && chapterOf(next) == chapterOf(done)) return
        EpisodeStore.get(this).update { it.copy(heard = it.heard + id) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        /** mediaId = "<chapter index>|<file index>|<schedule id or empty>|<episode stamp>" */
        fun items(e: Episode): List<MediaItem> = e.chapters.flatMapIndexed { c, ch ->
            ch.files.mapIndexed { f, file ->
                MediaItem.Builder()
                    .setMediaId("$c|$f|${ch.scheduleId.orEmpty()}|${e.preparedAt}")
                    .setUri(File(file.path).toURI().toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.title).setArtist("ewe · Today's Revision").build())
                    .build()
            }
        }

        fun chapterOf(item: MediaItem): Int = item.mediaId.substringBefore('|').toIntOrNull() ?: -1
        fun scheduleIdOf(item: MediaItem): String? = item.mediaId.split('|').getOrNull(2)?.takeIf { it.isNotEmpty() }
    }
}
