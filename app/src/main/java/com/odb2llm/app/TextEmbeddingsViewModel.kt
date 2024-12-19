package ai.wordbox.dogsembeddings

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

sealed class State {
    object Loading : State()
    object Success : State()
    object Error : State()
    object Empty : State()
}

data class SentenceWithCode(val code: String, val sentence: String)

data class TextEmbeddingsUiState(
    val sentencesWithCodes: List<SentenceWithCode> = emptyList(),  // Define the list here
    val similaritySentences: List<SentenceSimilarity> = emptyList(),
    val state: State = State.Empty,
    val errorMessage: String = String()
)

class TextEmbeddingsViewModel : ViewModel() {

    private lateinit var mediaPipeEmbeddings: MediaPipeEmbeddings

    var uiStateTextEmbeddings by mutableStateOf(TextEmbeddingsUiState(state = State.Empty))
        private set

    fun setUpMLModel(context: Context) {
        mediaPipeEmbeddings = MediaPipeEmbeddings()
        uiStateTextEmbeddings = uiStateTextEmbeddings.copy(
            state = State.Loading,
            similaritySentences = emptyList()
        )
        viewModelScope.launch {
            mediaPipeEmbeddings.setUpMLModel(context)
            uiStateTextEmbeddings = uiStateTextEmbeddings.copy(
                sentencesWithCodes = listOf(
                    SentenceWithCode("0101", "What is the monitor status since DTCs cleared?"),
                    SentenceWithCode("0102", "What DTC caused the freeze frame to be stored?"),
                    SentenceWithCode("0103", "What is the fuel system status?"),
                    SentenceWithCode("0104", "What is the engine load?"),
                    SentenceWithCode("0105", "What is the engine coolant temperature?"),
                    SentenceWithCode("0106", "What is the short term fuel trim for Bank 1?"),
                    SentenceWithCode("0107", "What is the long term fuel trim for Bank 1?"),
                    SentenceWithCode("0108", "What is the short term fuel trim for Bank 2?"),
                    SentenceWithCode("0109", "What is the long term fuel trim for Bank 2?"),
                    SentenceWithCode("010A", "What is the fuel pressure?"),
                    SentenceWithCode("010B", "What is the intake manifold pressure?"),
                    SentenceWithCode("010C", "What is the engine rpm or speed or revolutions?"),
                    SentenceWithCode("010D", "What is the vehicle speed?"),
                    SentenceWithCode("010E", "What is the timing advance?"),
                    SentenceWithCode("010F", "What is the intake air temperature?"),
                    SentenceWithCode("0110", "What is the MAF air flow rate?"),
                    SentenceWithCode("0111", "What is the throttle position?"),
                    SentenceWithCode("0112", "What is the commanded secondary air status?"),
                    SentenceWithCode("0113", "How many oxygen sensors are present in the 2 banks?"),
                    SentenceWithCode("0114", "What is the status of Oxygen Sensor 1?"),
                    SentenceWithCode("0115", "What is the status of Oxygen Sensor 2?")
                ),
                state = State.Empty
            )
        }

    }

    fun calculateSimilarity(mainSentence: String): String {
        val deferred = CompletableDeferred<String>()
        val similarityThreshold = 0.95  // Fixed threshold value

        uiStateTextEmbeddings = uiStateTextEmbeddings.copy(
            state = State.Loading,
            similaritySentences = emptyList()
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sentences = uiStateTextEmbeddings.sentencesWithCodes.map { it.sentence }
                val similarities = mediaPipeEmbeddings.getSimilarities(mainSentence, sentences)

                // Map similarities and filter by threshold
                val sentencesWithSimilarity = similarities.mapNotNull { similarity ->
                    val sentenceWithCode = uiStateTextEmbeddings.sentencesWithCodes.find { it.sentence == similarity.sentence }
                    if (similarity.resultSimilarity >= similarityThreshold) {
                        similarity.copy(
                            mainSentenceEmbeddings = similarity.mainSentenceEmbeddings,
                            sentenceEmbeddings = similarity.sentenceEmbeddings,
                            resultSimilarity = similarity.resultSimilarity
                        )
                    } else {
                        null // Ignore if below the threshold
                    }
                }

                val mostSimilarSentence = sentencesWithSimilarity.maxByOrNull { it.resultSimilarity }

                val codeOfMostSimilarSentence = mostSimilarSentence?.let {
                    uiStateTextEmbeddings.sentencesWithCodes.find { code -> code.sentence == it.sentence }?.code
                } ?: "No match found"

                Log.d("TextEmbeddingsViewModel", "Most Similar Sentence: ${mostSimilarSentence?.sentence}")
                Log.d("TextEmbeddingsViewModel", "Code of Most Similar Sentence: $codeOfMostSimilarSentence")

                uiStateTextEmbeddings = uiStateTextEmbeddings.copy(
                    state = State.Success,
                    similaritySentences = sentencesWithSimilarity,
                    errorMessage = codeOfMostSimilarSentence
                )

                deferred.complete(codeOfMostSimilarSentence)
            } catch (e: Exception) {
                uiStateTextEmbeddings = uiStateTextEmbeddings.copy(
                    state = State.Error,
                    errorMessage = e.message ?: "Error getting similarities"
                )
                deferred.complete("Error: ${e.message}")
            }
        }

        return runBlocking { deferred.await() }
    }


}
