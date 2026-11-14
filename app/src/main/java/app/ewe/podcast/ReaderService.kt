package app.ewe.podcast

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.ewe.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Presents "Listen to notes" to Android as a media player: one item per section. This gives it a
 * notification, lock-screen controls and headphone buttons, while the voice stays Android's TTS.
 */
class TtsPlayer(private val reader: NotesReader) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val commands = Player.Commands.Builder().addAll(
        COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_MEDIA_ITEM,
        COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA, COMMAND_RELEASE,
    ).build()

    // After every field above is set: the first state arrives immediately on the main thread.
    init {
        scope.launch { reader.state.collectLatest { invalidateState() } }
    }

    override fun getState(): State {
        val s = reader.state.value
        val builder = State.Builder().setAvailableCommands(commands)
        if (s == null || s.lines.isEmpty()) return builder.setPlaybackState(STATE_IDLE).setPlaylist(emptyList()).build()
        val sections = s.lines.map { it.section }.distinct()
        val playlist = sections.map { sec ->
            val title = s.lines.first { it.section == sec }.sectionTitle
            MediaItemData.Builder("section-$sec")
                .setMediaItem(MediaItem.Builder().setMediaId("section-$sec").setMediaMetadata(
                    MediaMetadata.Builder().setTitle(title).setArtist(s.sessionTitle).setAlbumTitle("ewe · Notes").build()).build())
                .build()
        }
        return builder.setPlaylist(playlist)
            .setCurrentMediaItemIndex(sections.indexOf(s.current?.section ?: sections.first()).coerceAtLeast(0))
            .setPlayWhenReady(s.playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(STATE_READY)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) reader.resume() else reader.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> reader.skipSection(true)
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> reader.skipSection(false)
            else -> reader.jumpToSectionIndex(mediaItemIndex)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        reader.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}

class ReaderService : MediaSessionService() {
    private var session: MediaSession? = null
    private val reader by lazy { NotesReader.get(this) }

    /** Unplugged headphones or Bluetooth dropped: pause instead of reading out loud. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) reader.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, TtsPlayer(reader)).setId("ewe-notes-reader").setSessionActivity(open).build()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (reader.state.value?.playing != true) stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisy) }
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
