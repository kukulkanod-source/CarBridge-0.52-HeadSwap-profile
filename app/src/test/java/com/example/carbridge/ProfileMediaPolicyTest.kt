package com.example.carbridge

import android.media.session.PlaybackState
import com.headswap.desk.component.api.ProfileContract
import org.junit.Assert.*
import org.junit.Test

class ProfileMediaPolicyTest {
    @Test fun playingSessionWinsOverRetainedPausedOrStoppedPlayer() {
        assertEquals(2, ProfileMediaPolicy.choose(listOf(PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING), 1))
        assertEquals(1, ProfileMediaPolicy.choose(listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PAUSED), 1))
    }
    @Test fun noActivePlayerDoesNotFallBackToAnUnrelatedStoppedSession() {
        assertEquals(-1, ProfileMediaPolicy.choose(emptyList(), -1))
        assertEquals(-1, ProfileMediaPolicy.choose(listOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED), 1))
    }
    @Test fun transportSendsOnlySupportedCommandsAndHandlesPausedPlayer() {
        assertNull(ProfileMediaPolicy.action(ProfileContract.MEDIA_NEXT, PlaybackState.STATE_PLAYING, 0))
        assertEquals(PlaybackState.ACTION_PAUSE, ProfileMediaPolicy.action(ProfileContract.MEDIA_PLAY_PAUSE,
            PlaybackState.STATE_PLAYING, PlaybackState.ACTION_PLAY_PAUSE))
        assertEquals(PlaybackState.ACTION_PLAY, ProfileMediaPolicy.action(ProfileContract.MEDIA_PLAY_PAUSE,
            PlaybackState.STATE_PAUSED, PlaybackState.ACTION_PLAY))
        assertNull(ProfileMediaPolicy.action(ProfileContract.MEDIA_PLAY_PAUSE, PlaybackState.STATE_STOPPED, PlaybackState.ACTION_PLAY))
    }
}
