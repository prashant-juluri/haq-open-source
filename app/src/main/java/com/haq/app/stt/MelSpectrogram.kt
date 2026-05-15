package com.haq.app.stt

import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Computes the 80-band log-mel spectrogram Whisper's encoder expects.
 * Output: flat FloatArray of length N_MELS × N_FRAMES (80 × 3000 = 240 000).
 */
object MelSpectrogram {

    private const val TAG = "Haq/MelSpec"

    private val melFilters: Array<FloatArray> by lazy { buildMelFilters() }
    private val window: FloatArray by lazy { hanningWindow(WhisperConfig.N_FFT) }

    /**
     * Precomputed DFT trig tables for N_FFT = 400.
     *
     * The naive DFT calls cos() and sin() at runtime for every (k, t) pair:
     *   201 bins × 400 samples × 3000 frames = 241 M trig calls → 10–30 s on mobile.
     *
     * These lazy tables compute the 201 × 400 = 80 400 values once (~8 ms),
     * then powerSpectrum() is pure float multiply-add (~100–200 ms total).
     *
     * Memory: 2 × 201 × 400 × 4 bytes ≈ 643 KB — negligible.
     */
    private val dftCos: Array<FloatArray> by lazy {
        val n = WhisperConfig.N_FFT
        Array(n / 2 + 1) { k ->
            FloatArray(n) { t -> cos(2.0 * PI * k * t / n).toFloat() }
        }
    }
    private val dftSin: Array<FloatArray> by lazy {
        val n = WhisperConfig.N_FFT
        Array(n / 2 + 1) { k ->
            FloatArray(n) { t -> sin(2.0 * PI * k * t / n).toFloat() }
        }
    }

    fun compute(samples: FloatArray): FloatArray {
        val nFft    = WhisperConfig.N_FFT
        val hop     = WhisperConfig.HOP_LENGTH
        val nMels   = WhisperConfig.N_MELS
        val nFrames = WhisperConfig.N_FRAMES

        Log.d(TAG, "compute: ${samples.size} samples → $nFrames frames")

        // Force lazy table initialisation before the tight loop so the one-time
        // cost appears in logs rather than hiding inside frame 0.
        val cosTable = dftCos
        val sinTable = dftSin

        val needed = (nFrames - 1) * hop + nFft
        val padded = FloatArray(needed).also { buf ->
            samples.copyInto(buf, 0, 0, minOf(samples.size, needed))
        }

        val spectrogram = Array(nFrames) { frame ->
            val start    = frame * hop
            val windowed = FloatArray(nFft) { i -> padded[start + i] * window[i] }
            powerSpectrum(windowed, cosTable, sinTable)
        }

        // log10 (not ln) matches OpenAI's Whisper preprocessing:
        //   log_spec = torch.clamp(mel_spec, min=1e-10).log10()
        val logMel = Array(nMels) { mel ->
            FloatArray(nFrames) { frame ->
                var energy = 0f
                for (bin in melFilters[mel].indices) energy += melFilters[mel][bin] * spectrogram[frame][bin]
                log10(max(energy, 1e-10f))
            }
        }

        // OpenAI normalization (exactly matching audio.py):
        //   log_spec = torch.maximum(log_spec, log_spec.amax() - 8.0)
        //   log_spec = (log_spec + 4.0) / 4.0
        val maxVal = logMel.maxOf { it.max() }
        val result = FloatArray(nMels * nFrames) { idx ->
            val m = idx / nFrames
            val t = idx % nFrames
            (max(logMel[m][t], maxVal - 8f) + 4f) / 4f
        }
        Log.d(TAG, "compute: done")
        return result
    }

    /**
     * Power spectrum using precomputed trig tables — O(N²) multiply-add, no runtime trig.
     * Previous implementation called cos()/sin() per iteration: 241 M calls for 3000 frames.
     * This version reads from [cosTable]/[sinTable]: same arithmetic, ~50× faster.
     */
    private fun powerSpectrum(
        signal:   FloatArray,
        cosTable: Array<FloatArray>,
        sinTable: Array<FloatArray>,
    ): FloatArray {
        val n   = signal.size
        val out = FloatArray(n / 2 + 1)
        for (k in out.indices) {
            var re = 0f
            var im = 0f
            val ck = cosTable[k]
            val sk = sinTable[k]
            for (t in 0 until n) {
                re += signal[t] * ck[t]
                im -= signal[t] * sk[t]
            }
            out[k] = re * re + im * im
        }
        return out
    }

    // Periodic Hann window (PyTorch default: torch.hann_window(N, periodic=True))
    // w[i] = 0.5 * (1 - cos(2π * i / N)) — denominator is N, not N-1.
    private fun hanningWindow(size: Int) = FloatArray(size) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / size))).toFloat()
    }

    private fun buildMelFilters(): Array<FloatArray> {
        val nFft       = WhisperConfig.N_FFT
        val sampleRate = WhisperConfig.SAMPLE_RATE
        val nMels      = WhisperConfig.N_MELS
        val nBins      = nFft / 2 + 1
        val melMin     = hzToMel(0.0)
        val melMax     = hzToMel(sampleRate / 2.0)

        val melPoints = DoubleArray(nMels + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (nMels + 1))
        }
        val bins = DoubleArray(nMels + 2) { i ->
            (melPoints[i] * (nFft + 1) / sampleRate).toLong().toDouble()
        }

        // Slaney area normalisation: divide each triangle filter by its bandwidth
        // in Hz so all 80 filters have equal area. This matches librosa's default
        // norm="slaney" used when OpenAI generated the Whisper training mel filters:
        //   librosa.filters.mel(sr=16000, n_fft=400, n_mels=80)
        //   enorm = 2.0 / (mel_f[m+2] - mel_f[m])
        // Without this, wide high-frequency filters contribute disproportionate
        // energy, the encoder sees out-of-distribution features, and the decoder
        // outputs repetitive garbage tokens (e.g. "!!!!").
        return Array(nMels) { m ->
            val enorm = (2.0 / (melPoints[m + 2] - melPoints[m])).toFloat()
            FloatArray(nBins) { bin ->
                val b = bin.toDouble()
                when {
                    b < bins[m]      -> 0f
                    b <= bins[m + 1] -> ((b - bins[m]) / (bins[m + 1] - bins[m]) * enorm).toFloat()
                    b <= bins[m + 2] -> ((bins[m + 2] - b) / (bins[m + 2] - bins[m + 1]) * enorm).toFloat()
                    else             -> 0f
                }
            }
        }
    }

    private fun hzToMel(hz: Double) = 2595.0 * Math.log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
}
