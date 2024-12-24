package com.odb2llm.app;

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.GlobalScope
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class InferenceModel private constructor(context: Context) {
    private var llmInference: LlmInference

    private val modelExists: Boolean
        get() = File(MODEL_PATH).exists()

    private val _partialResults = MutableSharedFlow<Pair<String, Boolean>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val partialResults: SharedFlow<Pair<String, Boolean>> = _partialResults.asSharedFlow()

    init {
        if (!modelExists) {
            throw IllegalArgumentException("Model not found at path: $MODEL_PATH")
        }

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(MODEL_PATH)
            .setMaxTokens(1024)
           // .setTopK(20)
           // .setTemperature(0.3F)
            .setResultListener { partialResult, done ->
                _partialResults.tryEmit(partialResult to done)
            }
            .build()

            llmInference = LlmInference.createFromOptions(context, options)
    }

    fun generateResponseAsync(prompt: String, callback: InferenceCallback) {
        // Add the gemma prompt prefix to trigger the response.
        val gemmaPrompt = prompt + "<start_of_turn>model\n"
        llmInference.generateResponseAsync(gemmaPrompt)

        // Launch a coroutine to collect partial results
        GlobalScope.launch {
            partialResults.collect { pair ->
                callback.onPartialResult(pair.first, pair.second)
                if (pair.second) {
                    callback.onFinalResult(pair.first) // Once done, return the final result
                }
            }
        }
    }

    // Interface for callback
    interface InferenceCallback {
        fun onPartialResult(partialResult: String, isDone: Boolean)
        fun onFinalResult(finalResult: String)
    }

    fun generateResponse(prompt: String): String {
        // Add the gemma prompt prefix to trigger the response.
        val gemmaPrompt = prompt + "<start_of_turn>model\n"
        return llmInference.generateResponse(gemmaPrompt)
    }

    companion object {
        // NB: Make sure the filename is *unique* per model you use!
        // Weight caching is currently based on filename alone.
        private const val MODEL_PATH = "/data/local/tmp/llm/model.bin"
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
