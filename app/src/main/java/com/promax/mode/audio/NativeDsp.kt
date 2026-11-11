package com.promax.mode.audio

private object NativeAudioLibrary {
    init {
        System.loadLibrary("promax_dsp")
    }
}

object NativeDsp {
    init { NativeAudioLibrary.hashCode() }

    external fun process(
        samples: ShortArray,
        count: Int,
        effect: Int,
        pitchSemitones: Float,
        formantSemitones: Float,
        noiseGateDb: Float
    )
}

object NativeMp3 {
    init { NativeAudioLibrary.hashCode() }

    /**
     * Encode Promax's 44.1 kHz, mono, PCM-16 WAV recordings to 128 kbps MP3.
     * Returns 0 on success and a negative native error code otherwise.
     */
    external fun encodeWavToMp3(inputWavPath: String, outputMp3Path: String): Int
}
