package com.nida.nidaplayer

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class NormalizingRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink? {
        // Audio processors operate on decoded PCM, so keep the sink on the PCM16 path.
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioProcessors(arrayOf(AutoVolumeNormalizer()))
            .build()
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var equalizer: Equalizer? = null
    private lateinit var lyricsOverlay: LyricsOverlay

    companion object {
        const val TOGGLE_LYRICS = "com.nida.nidaplayer.TOGGLE_LYRICS"
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
        player = ExoPlayer.Builder(this, NormalizingRenderersFactory(this)).build().apply {
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
        lyricsOverlay = LyricsOverlay(this, player) { updateLyricsButton() }
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    if (controller.packageName == packageName || session.isMediaNotificationController(controller)) {
                        commands.add(SessionCommand(TOGGLE_LYRICS, Bundle.EMPTY))
                    }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands.build()).build()
                }

                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand.customAction != TOGGLE_LYRICS) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    }
                    val success = lyricsOverlay.toggle()
                    if (!success) Toast.makeText(this@PlaybackService,
                        "曲を再生し、アプリの歌詞ボタンで重ねて表示の許可を設定してください", Toast.LENGTH_LONG).show()
                    return Futures.immediateFuture(SessionResult(
                        if (success) SessionResult.RESULT_SUCCESS else SessionError.ERROR_PERMISSION_DENIED))
                }
            }).build()
        updateLyricsButton()
    }

    private fun updateLyricsButton() {
        if (!::lyricsOverlay.isInitialized) return
        mediaSession?.setCustomLayout(listOf(CommandButton.Builder()
            .setDisplayName(if (lyricsOverlay.visible) "歌詞 OFF" else "歌詞 ON")
            .setIconResId(android.R.drawable.ic_menu_edit)
            .setSessionCommand(SessionCommand(TOGGLE_LYRICS, Bundle.EMPTY)).build()))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (::lyricsOverlay.isInitialized) lyricsOverlay.hide()
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
