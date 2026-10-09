package com.nida.nidaplayer

import androidx.media3.common.AudioAttributes
import android.media.audiofx.Equalizer
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var equalizer: Equalizer? = null

    companion object {
        @Volatile private var activeService: PlaybackService? = null

        fun equalizerBandCount(): Int = activeService?.safeEqualizer()?.numberOfBands?.toInt() ?: 0
        fun equalizerBandLevelRange(): ShortArray? = activeService?.safeEqualizer()?.bandLevelRange
        fun equalizerCenterFrequency(band: Int): Int = try {
            activeService?.safeEqualizer()?.getCenterFreq(band.toShort()) ?: 0
        } catch (_: Exception) { 0 }

        fun setEqualizerEnabled(enabled: Boolean) {
            activeService?.setEqEnabled(enabled)
        }

        fun setEqualizerBandLevel(band: Int, level: Short) {
            activeService?.setEqBandLevel(band, level)
        }
    }

    private fun safeEqualizer(): Equalizer? {
        ensureEqualizer()
        return equalizer
    }

    private fun ensureEqualizer() {
        if (equalizer != null) return
        try {
            val sessionId = player.audioSessionId
            if (sessionId <= 0) return
            equalizer = Equalizer(0, sessionId).apply {
                val prefs = getSharedPreferences("nida_player", MODE_PRIVATE)
                for (band in 0 until numberOfBands.toInt()) {
                    val minMax = bandLevelRange
                    val saved = prefs.getInt("equalizer_band_$band", 0)
                    setBandLevel(band.toShort(), saved.coerceIn(minMax[0].toInt(), minMax[1].toInt()).toShort())
                }
                enabled = prefs.getBoolean("equalizer_enabled", true)
            }
        } catch (_: Exception) {
            try { equalizer?.release() } catch (_: Exception) {}
            equalizer = null
        }
    }

    private fun setEqEnabled(enabled: Boolean) {
        ensureEqualizer()
        try { equalizer?.enabled = enabled } catch (_: Exception) {}
    }

    private fun setEqBandLevel(band: Int, level: Short) {
        ensureEqualizer()
        try {
            val eq = equalizer ?: return
            if (band in 0 until eq.numberOfBands.toInt()) {
                val range = eq.bandLevelRange
                eq.setBandLevel(band.toShort(), level.coerceIn(range[0], range[1]))
            }
        } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            // Keep normal media routing enabled: Android sends audio to connected
            // headphones, or to the built-in phone speaker when none are connected.
            // Do not apply a headphone-detection mute here.
        }
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) ensureEqualizer()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                ensureEqualizer()
            }
        })
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        activeService = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
