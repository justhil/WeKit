package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

object JevProtocol {
    const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"

    private val grades = listOf("C", "B", "A", "SSS")

    fun context(messages: List<String>, limit: Int): String =
        messages.takeLast(limit.coerceIn(0, 200)).joinToString("\n")

    fun request(
        text: String,
        outgoing: Boolean,
        context: String = "",
        prompt: String = "",
        speaker: String = if (outgoing) "我" else "对方",
    ): String = buildJsonObject {
        put("model", "jev-latest")
        put("state", buildString {
            val history = context.trim()
            if (history.isNotEmpty()) append("上下文（按时间顺序，仅供参考）：\n").append(history).append("\n")
            append("待评价消息（发送者：").append(speaker).append("）：\n").append(text)
        })
        put("questions", buildJsonObject {
            val focus = "只评价待评价消息的发送者；上下文只用于理解语境，不评价其他人的消息。"
            if (outgoing) {
                put("rating", buildJsonObject {
                    put("type", "score")
                    put("instructions", "$focus\n" + prompt.ifBlank {
                        "结合上下文，评价这条回复是否得体、清楚并切合对方的消息"
                    })
                    put("criteria", JsonArray(listOf(
                        "C：没有回应重点或语气明显不妥",
                        "B：回应了重点，但表达比较敷衍",
                        "A：清晰、得体地回应了对方",
                        "SSS：切合上下文，体贴且有效地解决问题",
                    ).map(::JsonPrimitive)))
                })
            } else {
                put("emotion", buildJsonObject {
                    put("type", "choice")
                    put("instructions", "$focus\n" + prompt.ifBlank { "结合上下文，判断这条消息主要表达了哪种情绪" })
                    put("criteria", buildJsonObject {
                        put("平静", "语气中性或平静")
                        put("愉快", "表达高兴或感谢")
                        put("难过", "表达悲伤或失落")
                        put("生气", "表达愤怒或不满")
                        put("焦虑", "表达担忧或紧张")
                    })
                })
                put("intent", buildJsonObject {
                    put("type", "choice")
                    put("instructions", "$focus\n" + prompt.ifBlank { "结合上下文，判断这条消息的主要沟通意图" })
                    put("criteria", buildJsonObject {
                        put("提问", "提出问题以获取信息")
                        put("求助", "希望获得帮助")
                        put("分享", "分享信息、经历或感受")
                        put("闲聊", "保持日常交流")
                        put("拒绝", "拒绝请求或表达不同意")
                    })
                })
            }
        })
    }.toString()

    fun label(response: String, outgoing: Boolean): String {
        val answers = Json.parseToJsonElement(response).jsonObject.getValue("answers").jsonObject
        if (outgoing) {
            val score = answers.getValue("rating").jsonObject.getValue("score").jsonPrimitive.doubleOrNull
                ?: error("Missing rating")
            require(score.isFinite() && score in 0.0..3.0) { "Invalid rating" }
            return "回复评级：${grades[score.roundToInt()]}"
        }
        fun choice(name: String): String {
            val answer = answers.getValue(name).jsonObject
            val selected = answer.getValue("choice").jsonPrimitive.content
            val probability = answer.getValue("probabilities").jsonObject.getValue(selected)
                .jsonPrimitive.doubleOrNull ?: error("Missing probability")
            require(probability.isFinite() && probability in 0.0..1.0) { "Invalid probability" }
            return "$selected ${(probability * 100).roundToInt()}%"
        }
        return "情绪：${choice("emotion")}\n意图：${choice("intent")}"
    }
}
