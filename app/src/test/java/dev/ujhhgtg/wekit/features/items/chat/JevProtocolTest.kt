package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JevProtocolTest {
    @Test
    fun incomingRequestHasIndependentChoiceQuestions() {
        val body = Json.parseToJsonElement(JevProtocol.request("你好吗？", outgoing = false)).jsonObject
        assertEquals("jev-latest", body.getValue("model").jsonPrimitive.content)
        val state = body.getValue("state").jsonPrimitive.content
        assertTrue(state.contains("待评价消息（发送者：对方）：\n你好吗？"))
        assertTrue(body.getValue("questions").jsonObject.getValue("emotion").jsonObject
            .getValue("instructions").jsonPrimitive.content.contains("只评价"))
        val questions = body.getValue("questions").jsonObject
        assertEquals(setOf("emotion", "intent"), questions.keys)
        assertEquals("choice", questions.getValue("emotion").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun outgoingRequestKeepsLimitedContextAndCustomPrompt() {
        val context = JevProtocol.context(listOf("第一条", "第二条", "第三条"), 2)
        assertEquals("第二条\n第三条", context)
        assertEquals("", JevProtocol.context(listOf("第一条"), 0))
        val body = Json.parseToJsonElement(
            JevProtocol.request("好的，我帮你", true, context, "只看语气"),
        ).jsonObject
        val state = body.getValue("state").jsonPrimitive.content
        assertTrue(state.contains("第二条"))
        assertFalse(state.contains("第一条"))
        val rating = body.getValue("questions").jsonObject.getValue("rating").jsonObject
        assertTrue(rating.getValue("instructions").jsonPrimitive.content.contains("只看语气"))
        assertTrue(rating.getValue("instructions").jsonPrimitive.content.contains("只评价"))
        assertEquals(4, rating.getValue("criteria").jsonArray.size)
    }

    @Test
    fun parsesIncomingChoicesAndOutgoingScore() {
        val incoming = """{"answers":{"emotion":{"choice":"愉快","probabilities":{"愉快":0.84}},"intent":{"choice":"分享","probabilities":{"分享":0.62}}}}"""
        assertEquals("情绪：愉快 84%\n意图：分享 62%", JevProtocol.label(incoming, false))
        val outgoing = """{"answers":{"rating":{"score":2.6}}}"""
        assertEquals("回复评级：SSS", JevProtocol.label(outgoing, true))
    }

    @Test
    fun invalidResponseDoesNotManufactureLabels() {
        assertThrows(Exception::class.java) { JevProtocol.label("{}", false) }
        assertThrows(Exception::class.java) { JevProtocol.label("{}", true) }
        assertThrows(IllegalArgumentException::class.java) {
            JevProtocol.label("""{"answers":{"rating":{"score":12.0}}}""", true)
        }
        assertFalse(JevProtocol.request("text", false).contains("rating"))
    }
}
