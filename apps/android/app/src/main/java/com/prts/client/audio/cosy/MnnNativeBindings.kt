// SPDX-License-Identifier: Apache-2.0
// JNI names must match Nian27/CosyVoice3-MNN v1.2.0's precompiled libraries.
// Kept in the upstream JNI package; this is not a network client.
package io.legado.app.cosy

internal object CosyVoiceLlmNative {
    init { System.loadLibrary("cosy_llm_jni") }
    external fun run(configPath: String, promptsPath: String, maxTokens: Int,
                     promptSpeechTokensPath: String, outputDirectory: String,
                     appendPromptSpeechTokens: Boolean): Int
    external fun reset()
}

internal object CosyVoiceFlowNative {
    init { System.loadLibrary("prts_cosy_flow_jni") }
    external fun run(modelPath: String, manifestPath: String, backend: String,
                     precision: String, threads: Int, reportPath: String, cachePath: String): Int
    external fun reset()
}

internal object CosyVoiceHiFTNative {
    init { System.loadLibrary("cosy_hift_jni") }
    external fun run(f0ModelPath: String, coreModelPath: String, manifestPath: String,
                     threads: Int, reportPath: String, coreBackend: String,
                     corePrecision: String, coreCachePath: String, coreGpuMode: Int): Int
    external fun reset()
}
