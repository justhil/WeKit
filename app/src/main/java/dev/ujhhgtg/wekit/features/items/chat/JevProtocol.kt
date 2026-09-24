package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

data class JevLine(val from: String, val text: String, val gap: String? = null, val quote: JevLine? = null)

sealed interface JevResult {
    val target: JevLine
    val model: String
    val tokens: Int
    val afterCount: Int
    val afterIds: List<Long>
}

data class JevIncoming(
    override val target: JevLine,
    override val model: String,
    override val tokens: Int,
    override val afterCount: Int,
    override val afterIds: List<Long> = emptyList(),
    val emotions: List<Pair<String, Double>>,
    val sarcasm: Double,
    val teasing: Double,
    val slang: Double,
    val perfunctory: Double,
) : JevResult {
    val irony: String?
        get() = when {
            sarcasm >= 0.5 && sarcasm > teasing -> "阴阳怪气"
            teasing >= 0.5 -> "调侃"
            else -> null
        }
}

data class JevOutgoing(
    override val target: JevLine,
    override val model: String,
    override val tokens: Int,
    override val afterCount: Int,
    override val afterIds: List<Long> = emptyList(),
    val respond: Double,
    val warmth: Double,
    val tone: Double,
    val cold: Double,
    val sarcastic: Double,
) : JevResult {
    val grade: String
        get() {
            val overall = (respond + warmth + tone) / 3
            return when {
                overall >= 0.9 -> "SSS"
                overall >= 0.7 -> "A"
                overall >= 0.45 -> "B"
                else -> "C"
            }
        }
}

val JevResult.warning: Boolean
    get() = when (this) {
        is JevIncoming -> irony == "阴阳怪气"
        is JevOutgoing -> cold >= 0.5 || sarcastic >= 0.5
    }

val JevResult.label: String
    get() = when (this) {
        is JevIncoming -> listOfNotNull(
            emotions.first().first,
            when (irony) {
                "阴阳怪气" -> if (sarcasm >= 0.8) "阴阳怪气 ${percent(sarcasm)}" else "可能在说反话"
                "调侃" -> "调侃"
                else -> null
            },
            "敷衍".takeIf { perfunctory >= 0.5 && sarcasm < 0.5 },
            "含网络黑话".takeIf { slang >= 0.5 },
        )
        is JevOutgoing -> listOfNotNull(
            "回复 $grade",
            listOf("回应偏少" to respond, "温度偏低" to warmth, "语气欠妥" to tone)
                .minBy { it.second }.takeIf { it.second < 0.5 }?.first,
            "可能显得冷淡".takeIf { cold >= 0.5 },
            "可能像阴阳怪气".takeIf { sarcastic >= 0.5 },
        )
    }.joinToString(" · ")

fun percent(value: Double) = "${(value * 100).roundToInt()}%"

object JevProtocol {
    const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"

    private class Question(val type: String, val instructions: String, val criteria: JsonElement? = null)

    // 题目措辞与 jev-eval/ 里评测过的版本逐字一致，改动后请重跑评测
    private val sincere = listOf("你今天气色真好", "多亏你提醒，不然我就忘了")
    private val teases = listOf("哈哈你这手气也太背了吧", "行啊你，深藏不露嘛")
    private val sarcasms = listOf("哟，稀客啊，还知道回来", "您老人家终于想起我了", "是是是，你永远是对的", "那可真是谢谢你了，帮了个大倒忙")

    private val incoming = mapOf(
        "sarcasm" to noul(
            "`target.text` 是不是在阴阳怪气：用反话、嘲讽或假客气挖苦对方、表达不满？结合 `conversation` 判断。",
            "用反话、嘲讽或假客气挖苦对方、表达不满", sarcasms,
            "字面意思就是真实意图，或朋友间没有敌意的玩笑", sincere + "哈哈你这手气也太背了吧（朋友间开玩笑）",
        ),
        "teasing" to noul(
            "`target.text` 是不是朋友间的善意调侃：用反话或夸张逗对方，但没有真实敌意？结合 `conversation` 判断。",
            "开玩笑地逗对方，没有敌意", teases,
            "真心话，或带敌意的挖苦", listOf(sincere[0], sarcasms[2]),
        ),
        "slang" to Question("noul", "`target.text` 是否用了网络流行语、黑话、拼音或数字缩写、网络梗（例如 awsl、夺笋、破防、社死、芭比Q了）？"),
        "perfunctory" to noul(
            "`target.text` 是不是在敷衍：只用很少的字应付，没有接住 `conversation` 里对方的话？",
            "字很少、没接话、没投入，本身不带嘲讽或敌意", listOf("噢", "知道了", "随你"),
            "认真回应；或虽然短但切题；带嘲讽或不满的反话不算敷衍", listOf("好，明天见", "这个想法不错，细说说"),
        ),
        "emotion" to Question("choice", "结合 `conversation`，`target.text` 的发送者真实的情绪是什么？看真实意图，不看字面。", buildJsonObject {
            put("平静", "语气中性、平静")
            put("开心", "高兴、兴奋")
            put("感激", "感谢对方")
            put("难过", "悲伤、失落")
            put("不满", "生气、不满、埋怨")
            put("焦虑", "担忧、紧张")
            put("其他", "以上都不是")
        }),
    )

    private val outgoing = mapOf(
        "respond" to score("`target.text` 在多大程度上回应了 `conversation` 里对方最后说的话？", "没有回应，答非所问", "部分回应", "完整回应"),
        "warmth" to score("`target.text` 给了对方多少温度和情绪支持？", "冷淡、打发", "中性", "温暖、体贴"),
        "tone" to score("在这个情境下，`target.text` 的语气是否得体？", "带敌意或不得体", "还算得体", "体贴周到"),
        "cold" to Question("noul", "对方读到 `target.text` 会不会觉得冷淡、敷衍？"),
        "sarcastic" to Question("noul", "对方读到 `target.text` 会不会觉得是在阴阳怪气、说反话？"),
    )

    private fun noul(instructions: String, yes: String, yesExamples: List<String>, no: String, noExamples: List<String>) =
        Question("noul", instructions, buildJsonObject {
            put("true", buildJsonObject { put("含义", yes); put("例子", JsonArray(yesExamples.map(::JsonPrimitive))) })
            put("false", buildJsonObject { put("含义", no); put("例子", JsonArray(noExamples.map(::JsonPrimitive))) })
        })

    private fun score(instructions: String, vararg levels: String) =
        Question("score", instructions, JsonArray(levels.map(::JsonPrimitive)))

    fun request(
        target: JevLine,
        conversation: List<JevLine>,
        after: List<JevLine>,
        relation: String,
        note: String,
        outgoing: Boolean,
    ): String = buildJsonObject {
        put("model", "jev-latest")
        put("state", buildJsonObject {
            put("conversation", JsonArray(conversation.map { it.json() }))
            put("target", target.json())
            if (relation.isNotBlank()) put("relationship", relation.trim())
            if (note.isNotBlank()) put("note", note.trim())
            if (after.isNotEmpty()) put("after", JsonArray(after.map { it.json() }))
        })
        val fields = listOfNotNull(
            "`target.quote`（被引用的消息）".takeIf { target.quote != null },
            "`relationship`（两人关系）".takeIf { relation.isNotBlank() },
            "`note`（补充说明）".takeIf { note.isNotBlank() },
            "`after`（目标之后的相关消息）".takeIf { after.isNotEmpty() },
        )
        put("questions", buildJsonObject {
            for ((id, question) in if (outgoing) this@JevProtocol.outgoing else incoming) {
                put(id, buildJsonObject {
                    put("type", question.type)
                    put("instructions", question.instructions.pointAt(fields))
                    question.criteria?.let { put("criteria", it) }
                })
            }
        })
    }.toString()

    private fun String.pointAt(fields: List<String>): String {
        if (fields.isEmpty()) return this
        val mention = "结合 " + (listOf("`conversation`") + fields).joinToString("、")
        return if ("结合 `conversation`" in this) replace("结合 `conversation`", mention) else "$this$mention 判断。"
    }

    private fun JevLine.json(): JsonObject = buildJsonObject {
        put("from", from)
        put("text", text)
        gap?.let { put("gap", it) }
        quote?.let { put("quote", it.json()) }
    }

    fun parse(response: String, job: JevJob): JevResult {
        val body = Json.parseToJsonElement(response).jsonObject
        val answers = body.getValue("answers").jsonObject
        val model = body.getValue("model").jsonPrimitive.content
        val tokens = body.getValue("usage").jsonObject.getValue("input_tokens").jsonPrimitive.int
        fun answer(id: String) = answers.getValue(id).jsonObject
        fun noul(id: String) = answer(id).getValue("noul").jsonPrimitive.double
            .also { require(it in 0.0..1.0) { "Invalid $id" } }
        fun score(id: String) = answer(id).let { it.getValue("score").jsonPrimitive.double / (it.getValue("legend").jsonObject.size - 1) }
            .also { require(it in 0.0..1.0) { "Invalid $id" } }
        return if (job.outgoing) {
            JevOutgoing(job.target, model, tokens, job.afterCount, job.afterIds,
                score("respond"), score("warmth"), score("tone"), noul("cold"), noul("sarcastic"))
        } else {
            val emotions = answer("emotion").getValue("probabilities").jsonObject
                .map { (name, p) -> name to p.jsonPrimitive.double }.sortedByDescending { it.second }
            require(emotions.isNotEmpty()) { "Missing emotions" }
            JevIncoming(job.target, model, tokens, job.afterCount, job.afterIds, emotions,
                noul("sarcasm"), noul("teasing"), noul("slang"), noul("perfunctory"))
        }
    }
}
