package com.odb2llm.app

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

class InferenceModel private constructor(context: Context) {
    private var llmInference: LlmInference
    private val modelPath: String = context.filesDir.absolutePath + "/llm/model.bin"

    private val modelExists: Boolean
        get() = File(modelPath).exists()

    private val _partialResults = MutableSharedFlow<Pair<String, Boolean>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        if (!modelExists) {
            throw IllegalArgumentException("Model not found at path: $modelPath")
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(1024)
            .setResultListener { partialResult, done ->
                _partialResults.tryEmit(partialResult to done)
            }
            .build()

            llmInference = LlmInference.createFromOptions(context, options)
    }

    fun generateResponse(prompt: String): String {
        val gemmaPrompt = "$prompt<start_of_turn>model\n"
        return llmInference.generateResponse(gemmaPrompt)
    }

    companion object {
        private var instance: InferenceModel? = null

        fun getInstance(context: Context): InferenceModel {
            return if (instance != null) {
                instance!!
            } else {
                InferenceModel(context).also { instance = it }
            }
        }
    }
}
