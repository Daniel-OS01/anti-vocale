package com.antivocale.app.audio

import android.content.Context
import javax.inject.Inject

class BlankDecodeSpeechProbe @Inject constructor() {
    fun detectsSpeech(
        context: Context,
        samples: FloatArray,
        threadCount: Int,
        onnxProvider: String,
    ): Boolean =
        VadProcessor.detectSpeech(context, samples, threadCount, onnxProvider).mergedRanges.isNotEmpty()
}
