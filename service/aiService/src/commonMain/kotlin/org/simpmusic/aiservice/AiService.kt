package org.simpmusic.aiservice

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.aallam.openai.client.OpenAIHost.Companion.Gemini
import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.data.model.taste.TasteException
import com.maxrave.domain.data.model.taste.TasteInput
import com.maxrave.domain.data.model.taste.TasteReading
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

class AiService(
    private val aiHost: AIHost = AIHost.GEMINI,
    private val apiKey: String,
    private val customModelId: String? = null,
    private val customBaseUrl: String? = null,
    private val customHeaders: Map<String, String>? = null,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    private val openAI: OpenAI by lazy {
        when (aiHost) {
            AIHost.GEMINI -> {
                OpenAI(host = Gemini, token = apiKey, timeout = REQUEST_TIMEOUT)
            }

            AIHost.OPENAI -> {
                OpenAI(token = apiKey, timeout = REQUEST_TIMEOUT)
            }

            AIHost.DEEPSEEK -> {
                OpenAI(
                    config =
                        OpenAIConfig(
                            token = apiKey,
                            host = OpenAIHost(baseUrl = AIHost.DEEPSEEK_BASE_URL),
                            // 2026-10-29 实测:漏传落在库默认 socket 30s——推理模型整首歌
                            // 歌词思考超 30s 即 Socket timeout,Zhipu 线每次必炸
                            timeout = REQUEST_TIMEOUT,
                        ),
                )
            }

            AIHost.ZHIPU -> {
                OpenAI(
                    config =
                        OpenAIConfig(
                            token = apiKey,
                            host = OpenAIHost(baseUrl = AIHost.ZHIPU_BASE_URL),
                            timeout = REQUEST_TIMEOUT,
                        ),
                )
            }

            AIHost.CUSTOM_OPENAI -> {
                val baseUrl = customBaseUrl ?: "https://api.openai.com/v1/"
                val config =
                    OpenAIConfig(
                        token = apiKey,
                        timeout = REQUEST_TIMEOUT,
                        host = OpenAIHost(baseUrl = baseUrl),
                        headers = customHeaders ?: emptyMap(),
                    )
                OpenAI(config)
            }
        }
    }

    private val model by lazy {
        if (!customModelId.isNullOrEmpty()) {
            ModelId(customModelId)
        } else {
            ModelId(aiHost.defaultModelId)
        }
    }

    /** Lists model IDs exposed by the current host's OpenAI-compatible `GET /models` endpoint. */
    suspend fun listModels(): List<String> = openAI.models().map { it.id.id }.sorted()

    /**
     * Plain-text completion — the generic door for callers that are not lyrics translation
     * (e.g. download tag enrichment: genre/language guesses from song metadata). Returns the
     * message text as-is; the caller parses what it asked for.
     */
    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val request =
            chatCompletionRequest {
                this.model = this@AiService.model
                messages {
                    system { content = systemPrompt }
                    user { content = userPrompt }
                }
            }
        val completion: ChatCompletion = openAI.chatCompletion(request)
        return completion.choices
            .firstOrNull()
            ?.message
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("No response from AI")
    }

    /**
     * One reading of the listener's taste from [input].
     *
     * Sent without a `responseFormat`, unlike [translateLyrics]: providers reached through the
     * custom OpenAI-compatible path do not all accept `json_schema` (issue #2297 traced DeepSeek's
     * chat endpoint to `json_object` only), and a refused format fails the whole request. The
     * prompt asks for a bare JSON object instead, and [parseTasteReading] reads it leniently.
     */
    suspend fun describeTaste(input: TasteInput): TasteReading {
        val request =
            chatCompletionRequest {
                this.model = this@AiService.model
                messages {
                    system {
                        content = TASTE_SYSTEM_PROMPT
                    }
                    user {
                        content {
                            text(input.toPromptData())
                        }
                    }
                }
            }
        val raw =
            openAI
                .chatCompletion(request)
                .choices
                .firstOrNull()
                ?.message
                ?.content
                ?: throw TasteException(TasteException.Kind.TEMPORARY, message = "Empty response from AI")
        return parseTasteReading(raw)
            ?: throw TasteException(TasteException.Kind.TEMPORARY, message = "Unreadable response from AI")
    }

    suspend fun translateLyrics(
        inputLyrics: Lyrics,
        targetLanguage: String,
    ): Lyrics {
        val lines = inputLyrics.lines ?: throw IllegalStateException("No lyrics lines to translate")

        // Build key-value map: index -> words (only non-empty lines)
        val indexToWords = mutableMapOf<String, String>()
        lines.forEachIndexed { index, line ->
            val words = line.words.trim()
            if (words.isNotEmpty() && words != "♫") {
                indexToWords[index.toString()] = words
            }
        }

        if (indexToWords.isEmpty()) {
            throw IllegalStateException("No translatable lyrics lines found")
        }

        val inputJson = json.encodeToString(MapSerializer(String.serializer(), String.serializer()), indexToWords)

        val request =
            chatCompletionRequest {
                this.model = this@AiService.model
                // 裸发,不带 responseFormat——与 describeTaste 同款(2026-10-28 定案):
                // 自定义 OpenAI 兼容端点普遍不收 json_schema(DeepSeek 实测回
                // "This response_format type is unavailable now",用户日志连续 6 天
                // 每首歌词都撞),格式约束由 system prompt 承担,下方解析本就按
                // fence/裸 JSON 两形容错,不需要 schema 兜底
                messages {
                    system {
                        content =
                            "You are a song lyrics translation assistant.\n" +
                            "\n" +
                            "TASK:\n" +
                            "- You will receive a JSON object where keys are line indices and values are lyrics text.\n" +
                            "- FIRST, detect the dominant language of the input lyrics.\n" +
                            "- If the detected language is the SAME as the target language code, return an EMPTY \"translations\" object. Do NOT translate. Do NOT paraphrase.\n" +
                            "- Otherwise, translate ONLY the values to the target language.\n" +
                            "- When translating: keep ALL keys exactly the same, output MUST have the EXACT same number of entries as the input, do NOT merge/split/add/remove any entries, and preserve the song's meaning, tone, and emotion.\n" +
                            "\n" +
                            "OUTPUT:\n" +
                            "- A JSON object with the \"translations\" field containing the same keys mapped to translated values (or an empty object when the input is already in the target language)."
                    }
                    user {
                        content {
                            text("Target language: $targetLanguage")
                        }
                        content {
                            text("Input lyrics: $inputJson")
                        }
                    }
                }
            }
        val completion: ChatCompletion = openAI.chatCompletion(request)
        val jsonContent =
            completion.choices
                .firstOrNull()
                ?.message
                ?.content ?: throw IllegalStateException("No response from AI")
        val jsonData =
            Regex(
                "```json\\s*([\\s\\S]*?)```",
            ).find(jsonContent)
                ?.groups
                ?.firstOrNull()
                ?.value ?: jsonContent
        val cleanedJson = jsonData.replace("```json", "").replace("```", "")
        val translationResponse = json.decodeFromString<TranslationResponse>(cleanedJson)
        val translatedMap = translationResponse.translations
        if (translatedMap.isEmpty()) {
            throw IllegalStateException(
                "Input lyrics are already in the target language ($targetLanguage). Translation aborted.",
            )
        }

        // Map translated text back to original lines, preserving all timestamps
        val translatedLines = lines.mapIndexed { index, originalLine ->
            val translatedWords = translatedMap[index.toString()]
            if (translatedWords != null) {
                Line(
                    startTimeMs = originalLine.startTimeMs,
                    endTimeMs = originalLine.endTimeMs,
                    words = translatedWords,
                    syllables = null,
                )
            } else {
                // Non-translatable line (empty or ♫): keep original
                Line(
                    startTimeMs = originalLine.startTimeMs,
                    endTimeMs = originalLine.endTimeMs,
                    words = originalLine.words,
                    syllables = originalLine.syllables,
                )
            }
        }

        return Lyrics(
            error = false,
            lines = translatedLines,
            syncType = inputLyrics.syncType,
        )
    }

    companion object {
        /**
         * How long a request may go without a byte coming back. The library's default is 30 s,
         * which a model that thinks before answering — or a router in front of one — exceeds on an
         * open-ended prompt like the taste reading, and the request then dies with
         * "Socket timeout has expired" while the provider is still working on it.
         */
        private val REQUEST_TIMEOUT = Timeout(socket = 2.minutes)
    }
}

@kotlinx.serialization.Serializable
data class TranslationResponse(
    val translations: Map<String, String> = emptyMap(),
)

enum class AIHost(
    /** Fallback model when the user has not set a custom model ID. */
    val defaultModelId: String,
) {
    // 各家默认=当前最新线里最便宜的一档(2026-10-29 用户定案,对齐各家 /models 列表):
    // DeepSeek API 实际 advertise deepseek-v4-pro/deepseek-flash(一键获取实证),
    // flash 档最便宜(~$0.15/$0.60 每百万 tokens off-peak);GPT-6 Luna $0.10/$0.50
    // 为 OpenAI 当下最低价;Gemini 最新代 lite 档;智谱 glm-4.7-flash 免费(并发 1)
    GEMINI("gemini-3.5-flash-lite"),
    OPENAI("gpt-6-luna"),
    DEEPSEEK("deepseek-flash"),
    ZHIPU("glm-4.7-flash"),
    CUSTOM_OPENAI("gpt-6-luna"),
    ;

    companion object {
        /** OpenAI-compatible endpoint of DeepSeek. */
        const val DEEPSEEK_BASE_URL: String = "https://api.deepseek.com/v1/"

        /** OpenAI-compatible endpoint of Zhipu (智谱) BigModel. */
        const val ZHIPU_BASE_URL: String = "https://open.bigmodel.cn/api/paas/v4/"
    }
}