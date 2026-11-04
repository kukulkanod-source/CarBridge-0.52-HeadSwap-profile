package com.example.carbridge

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.headswap.desk.component.api.ProfileContract
import com.headswap.desk.component.api.ProfileNowPlaying

internal data class ProfileTrack(val title: String, val artist: String, val playing: Boolean, val artwork: Bitmap?) {
    companion object {
        fun fromHost(track: ProfileNowPlaying?) = track?.let { ProfileTrack(it.title, it.artist, it.isPlaying, it.artwork) }
    }
}

/** Only usable after the user explicitly grants this APK notification-listener access in Android. */
internal class ProfileMediaSource(context: Context) {
    private val app = context.applicationContext
    private val listener = ComponentName(app, ProfileNotifications::class.java)
    private var retained: MediaSession.Token? = null
    fun allowed(): Boolean = runCatching {
        app.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
    }.getOrDefault(false)

    private fun player(): MediaController? {
        if (!allowed()) { retained = null; return null }
        val sessions = runCatching { app.getSystemService(MediaSessionManager::class.java).getActiveSessions(listener) }
            .getOrDefault(emptyList()).filter { it.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL }
        val index = ProfileMediaPolicy.choose(sessions.map { it.playbackState?.state ?: PlaybackState.STATE_NONE },
            sessions.indexOfFirst { it.sessionToken == retained })
        return sessions.getOrNull(index).also { retained = it?.sessionToken }
    }

    fun snapshot(): ProfileTrack? = runCatching {
        val player = player() ?: return null
        val metadata = player.metadata ?: return null
        val state = player.playbackState ?: return null
        val title = sequenceOf(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            .mapNotNull { metadata.getText(it)?.toString()?.take(256)?.takeIf(String::isNotBlank) }.firstOrNull()
            ?: return null
        val artist = sequenceOf(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).mapNotNull { metadata.getText(it)?.toString()?.take(256) }.firstOrNull().orEmpty()
        val image = sequenceOf(MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART)
            .mapNotNull { metadata.getBitmap(it) }.firstOrNull()?.let { original ->
                if (original.isRecycled || original.width <= 0 || original.height <= 0) null else {
                    val scale = minOf(1f, 192f / maxOf(original.width, original.height))
                    runCatching { Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1),
                        (original.height * scale).toInt().coerceAtLeast(1), true) }.getOrNull()
                }
            }
        ProfileTrack(title, artist, state.state == PlaybackState.STATE_PLAYING, image)
    }.getOrNull()

    fun command(code: Int): Boolean = runCatching {
        val player = player() ?: return false
        val state = player.playbackState ?: return false
        val action = ProfileMediaPolicy.action(code, state.state, state.actions) ?: return false
        if (!allowed()) return false
        when (action) {
            PlaybackState.ACTION_SKIP_TO_PREVIOUS -> player.transportControls.skipToPrevious()
            PlaybackState.ACTION_SKIP_TO_NEXT -> player.transportControls.skipToNext()
            PlaybackState.ACTION_PLAY -> player.transportControls.play()
            PlaybackState.ACTION_PAUSE -> player.transportControls.pause()
            else -> return false
        }
        true
    }.getOrDefault(false)

    fun clear() { retained = null }
}

internal object ProfileMediaPolicy {
    fun choose(states: List<Int>, retained: Int): Int {
        val playing = states.indexOfFirst { it == PlaybackState.STATE_PLAYING }
        if (playing >= 0) return playing
        if (retained in states.indices && states[retained] == PlaybackState.STATE_PAUSED) return retained
        return states.indexOfFirst { it == PlaybackState.STATE_PAUSED }
    }
    fun action(code: Int, state: Int, supported: Long): Long? {
        val action = when (code) {
            ProfileContract.MEDIA_PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            ProfileContract.MEDIA_NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
            ProfileContract.MEDIA_PLAY_PAUSE -> when (state) {
                PlaybackState.STATE_PLAYING -> PlaybackState.ACTION_PAUSE
                PlaybackState.STATE_PAUSED -> PlaybackState.ACTION_PLAY
                else -> return null
            }
            else -> return null
        }
        return action.takeIf { supported and action != 0L ||
            code == ProfileContract.MEDIA_PLAY_PAUSE && supported and PlaybackState.ACTION_PLAY_PAUSE != 0L }
    }
}
