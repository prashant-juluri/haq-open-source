package com.haq.app.stt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Computes the 80-band log-mel spectrogram that Whisper's encoder expects.
 * Output shape: [1, 80, 3000] — batch=1, n_mels=80, n_frames=3000.
 *
 * All arithmetic is pure Kotlin — no native libs required.
 */
object MelSpectrogram {

    private val melFilters: Array<FloatArray> by lazy { buildMelFilters() }
    private val window: FloatArray by lazy { hanningWindow(WhisperConfig.N_FFT) }

    /**
     * Returns a flat FloatArray of length 1×80×3000 = 240 000, in CHW order
     * (channel=mel-band, height ignored, width=time-frame).
     */
    fun compute(samples: FloatArray): FloatArray {
        val nFft    = WhisperConfig.N_FFT
        val hop     = WhisperConfig.HOP_LENGTH
        val nMels   = WhisperConfig.N_MELS
        val nFrames = WhisperConfig.N_FRAMES

        // Pad to guarantee exactly N_FRAMES output frames
        val needed  = (nFrames - 1) * hop + nFft
        val padded  = FloatArray(needed).also { buf ->
            samples.copyInto(buf, 0, 0, minOf(samples.size, needed))
        }

        val spectrogram = Array(nFrames) { frame ->
            val start = frame * hop
            // Apply Hanning window
            val windowed = FloatArray(nFft) { i -> padded[start + i] * window[i] }
            // Power spectrum via real-valued DFT
            powerSpectrum(windowed)
        }

        // Apply mel filter bank and log-compress
        val logMel = Array(nMels) { mel ->
            FloatArray(nFrames) { frame ->
                var energy = 0f
                for (bin in melFilters[mel].indices) {
                    energy += melFilters[mel][bin] * spectrogram[frame][bin]
                }
                // Log mel — clamp to avoid log(0)
                ln(max(energy, 1e-10f))
            }
        }

        // Whisper normalises: (log_mel - max) / 4 + 1, clamped to [-1, 1]
        val maxVal = logMel.maxOf { band -> band.max() }
        val out = FloatArray(nMels * nFrames)
        for (m in 0 until nMels) {
            for (t in 0 until nFrames) {
                val v = ((logMel[m][t] - maxVal) / 4f + 1f).coerceIn(-1f, 1f)
                out[m * nFrames + t] = v
            }
        }
        return out
    }

    // ── DFT / power spectrum ──────────────────────────────────────────────────

    private fun powerSpectrum(signal: FloatArray): FloatArray {
        val n   = signal.size
        val out = FloatArray(n / 2 + 1)
        // Naïve O(n²) DFT — adequate for n=400 at realtime (1 ms per frame)
        for (k in out.indices) {
            var re = 0.0
            var im = 0.0
            for (t in signal.indices) {
                val angle = 2.0 * PI * k * t / n
                re += signal[t] * cos(angle)
                im -= signal[t] * sin(angle)
            }
            out[k] = (re * re + im * im).toFloat()
        }
        return out
    }

    // ── Hanning window ────────────────────────────────────────────────────────

    private fun hanningWindow(size: Int) = FloatArray(size) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))).toFloat()
    }

    // ── Mel filter bank ───────────────────────────────────────────────────────

    private fun buildMelFilters(): Array<FloatArray> {
        val nFft       = WhisperConfig.N_FFT
        val sampleRate = WhisperConfig.SAMPLE_RATE
        val nMels      = WhisperConfig.N_MELS
        val nBins      = nFft / 2 + 1

        val fMin = 0.0
        val fMax = sampleRate / 2.0

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // nMels + 2 evenly-spaced mel points
        val melPoints = DoubleArray(nMels + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (nMels + 1))
        }

        // Convert to FFT bin indices
        val bins = DoubleArray(nMels + 2) { i ->
            (melPoints[i] * (nFft + 1) / sampleRate).toLong().toDouble()
        }

        return Array(nMels) { m ->
            FloatArray(nBins) { bin ->
                val b = bin.toDouble()
                when {
                    b < bins[m]     -> 0f
                    b <= bins[m + 1] ->
                        ((b - bins[m]) / (bins[m + 1] - bins[m])).toFloat()
                    b <= bins[m + 2] ->
                        ((bins[m + 2] - b) / (bins[m + 2] - bins[m + 1])).toFloat()
                    else            -> 0f
                }
            }
        }
    }

    private fun hzToMel(hz: Double) = 2595.0 * Math.log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
