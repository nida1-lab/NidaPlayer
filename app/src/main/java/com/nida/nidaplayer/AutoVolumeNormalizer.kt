package com.nida.nidaplayer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight PCM auto-gain control. It smooths loudness across short windows,
 * changes gain gradually, and limits peaks before they can overflow PCM16.
 * It reuses BaseAudioProcessor's output buffer and keeps only a few scalar values.
 */
@UnstableApi
internal class AutoVolumeNormalizer : BaseAudioProcessor() {
    companion object {
        private const val TARGET_RMS = 0.16
        private const val MIN_GAIN = 0.32
        private const val MAX_GAIN = 3.0
        private const val PEAK_LIMIT = 0.97
        private const val RMS_TIME_SECONDS = 0.65
        private const val GAIN_DOWN_SECONDS = 0.16
        private const val GAIN_UP_SECONDS = 0.85
    }

    private var smoothedPower = TARGET_RMS * TARGET_RMS
    private var currentGain = 1.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (
            inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate > 0 &&
            inputAudioFormat.channelCount > 0
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onQueueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        val sampleCount = byteCount / 2
        if (sampleCount <= 0) return

        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        output.put(inputBuffer)
        output.flip()

        var squareSum = 0.0
        var peak = 0.0
        for (sampleIndex in 0 until sampleCount) {
            val sample = output.getShort(sampleIndex * 2).toDouble() / 32768.0
            squareSum += sample * sample
            peak = max(peak, kotlin.math.abs(sample))
        }

        val format = inputAudioFormat
        val frameCount = sampleCount.toDouble() / format.channelCount.coerceAtLeast(1)
        val blockSeconds = (frameCount / format.sampleRate.coerceAtLeast(1)).coerceAtLeast(0.001)
        val blockPower = squareSum / sampleCount
        val rmsAlpha = 1.0 - exp(-blockSeconds / RMS_TIME_SECONDS)
        smoothedPower += (blockPower - smoothedPower) * rmsAlpha

        // Avoid runaway gain in near-silence while still lifting quiet recordings.
        val measuredRms = max(sqrt(smoothedPower), 0.035)
        val desiredGain = (TARGET_RMS / measuredRms).coerceIn(MIN_GAIN, MAX_GAIN)
        val gainTime = if (desiredGain < currentGain) GAIN_DOWN_SECONDS else GAIN_UP_SECONDS
        val gainAlpha = 1.0 - exp(-blockSeconds / gainTime)
        currentGain += (desiredGain - currentGain) * gainAlpha

        // Lower the gain for this block if needed so peaks stay below full scale.
        val outputGain = if (peak * currentGain > PEAK_LIMIT && peak > 0.0) {
            PEAK_LIMIT / peak
        } else {
            currentGain
        }

        for (sampleIndex in 0 until sampleCount) {
            val source = output.getShort(sampleIndex * 2).toDouble()
            val adjusted = (source * outputGain).toInt().coerceIn(-32768, 32767)
            output.putShort(sampleIndex * 2, adjusted.toShort())
        }
    }

    // Keep the smoothed gain between tracks for a less abrupt volume transition.
    override fun onFlush() = Unit

    override fun onReset() {
        smoothedPower = TARGET_RMS * TARGET_RMS
        currentGain = 1.0
    }
}
