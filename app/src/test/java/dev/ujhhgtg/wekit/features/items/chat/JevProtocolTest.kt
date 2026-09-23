package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JevProtocolTest {
    private fun questions(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("questions").jsonObject

    private fun instructions(body: String, id: String) =
        questions(body).getValue(id).jsonObject.getValue("instructions").jsonPrimitive.content

    @Test
    fun incomingRequestSendsStructuredStateAndAllQuestions() {
        val body = JevProtocol.request(JevLine("对方", "呵呵"), listOf(JevLine("我", "我考得挺好")), emptyList(), "", "", false)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("jev-latest", json.getValue("model").jsonPrimitive.content)
        val state = json.getValue("state").jsonObject
        assertEquals(setOf("conversation", "target"), state.keys)
        assertEquals("呵呵", state.getValue("target").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(setOf("sarcasm", "teasing", "slang", "perfunctory", "emotion"), questions(body).keys)
        assertTrue(instructions(body, "sarcasm").endsWith("结合 `conversation` 判断。"))
    }

    @Test
    fun optionalContextIsSentAndNamedInQuestions() {
        val target = JevLine("对方", "真厉害", quote = JevLine("我", "我把项目搞砸了"))
        val body = JevProtocol.request(target, emptyList(), listOf(JevLine("我", "你什么意思？")), "对方是我同事", "", false)
        val state = Json.parseToJsonElement(body).jsonObject.getValue("state").jsonObject
        assertEquals(setOf("conversation", "target", "relationship", "after"), state.keys)
        assertEquals(
            "结合 `conversation`、`target.quote`（被引用的消息）、`relationship`（两人关系）、`after`（这条消息之后的回复） 判断。",
            instructions(body, "sarcasm").substringAfter("？"),
        )
        val mine = JevProtocol.request(JevLine("我", "辛苦了🙂"), emptyList(),
            listOf(JevLine("对方", "你这个表情什么意思？")), "", "我说话比较直", true)
        assertEquals(setOf("respond", "warmth", "tone", "cold", "sarcastic"), questions(mine).keys)
        assertEquals(
            "对方读到 `target.text` 会不会觉得冷淡、敷衍？结合 `conversation`、`note`（补充说明）、`after`（这条消息之后的回复） 判断。",
            instructions(mine, "cold"),
        )
    }

    @Test
    fun parsesAnswersAndSummarizesTheOtherSide() {
        val job = JevJob(1, "", JevLine("对方", "你可真行"), outgoing = false, afterCount = 2)
        val result = JevProtocol.parse("""{"model":"jev-1.13.0","usage":{"input_tokens":812},"answers":{
            "sarcasm":{"noul":0.91},"teasing":{"noul":0.2},"slang":{"noul":0.1},"perfunctory":{"noul":0.7},
            "emotion":{"choice":"不满","probabilities":{"平静":0.1,"不满":0.85,"难过":0.05}}}}""", job) as JevIncoming
        assertEquals(listOf("不满", "平静", "难过"), result.emotions.map { it.first })
        assertEquals(812, result.tokens)
        assertEquals(2, result.afterCount)
        assertEquals("不满 · 阴阳怪气 91%", result.label)
        assertTrue(result.warning)
    }

    @Test
    fun summarizesMyReplyFromNormalizedScores() {
        val job = JevJob(2, "", JevLine("我", "哦，恭喜"), outgoing = true, afterCount = 0)
        fun score(value: Double) = """{"score":$value,"legend":{"0":"低","1":"中","2":"高"}}"""
        val result = JevProtocol.parse("""{"model":"jev-1.13.0","usage":{"input_tokens":500},"answers":{
            "respond":${score(2.0)},"warmth":${score(0.5)},"tone":${score(1.0)},
            "cold":{"noul":0.7},"sarcastic":{"noul":0.1}}}""", job) as JevOutgoing
        assertEquals(0.25, result.warmth, 1e-9)
        assertEquals("回复 B · 温度偏低 · 可能显得冷淡", result.label)
    }

    @Test
    fun ironyTiersSeparateSarcasmFromTeasing() {
        fun incoming(sarcasm: Double, teasing: Double) =
            JevIncoming(JevLine("对方", "x"), "m", 0, 0, listOf("平静" to 1.0), sarcasm, teasing, 0.0, 0.0)
        assertEquals("平静 · 可能在说反话", incoming(0.6, 0.3).label)
        assertEquals("平静 · 调侃", incoming(0.79, 0.84).label)
        assertEquals("平静", incoming(0.2, 0.1).label)
    }

    @Test
    fun invalidResponsesDoNotProduceResults() {
        val job = JevJob(1, "", JevLine("对方", "x"), outgoing = false, afterCount = 0)
        assertThrows(Exception::class.java) { JevProtocol.parse("{}", job) }
        assertThrows(IllegalArgumentException::class.java) {
            JevProtocol.parse("""{"model":"m","usage":{"input_tokens":1},"answers":{"sarcasm":{"noul":1.5},
                "teasing":{"noul":0},"slang":{"noul":0},"perfunctory":{"noul":0},
                "emotion":{"probabilities":{"平静":1}}}}""", job)
        }
    }
}
