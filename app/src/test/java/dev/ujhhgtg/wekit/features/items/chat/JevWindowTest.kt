package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JevWindowTest {
    private fun message(id: Long, text: String, type: Int = MessageType.TEXT.code) =
        WeMessage(id, id, "friend", text, type, id, (id % 2).toInt())

    @Test fun onlyLatestMessagesAreAnalyzedAndOlderOnesSupplyContext() {
        val rows = listOf(message(5, "最新"), message(4, "图片", MessageType.IMAGE.code),
            message(3, "上一条"), message(2, "再上一条"), message(1, "最早"))
        val jobs = JevWindow.plan(rows, 3, 2, "")
        assertEquals(listOf(5L, 3L), jobs.map { it.id })
        val state = Json.parseToJsonElement(jobs[0].body).jsonObject.getValue("state").jsonPrimitive.content
        assertTrue(state.contains("再上一条"))
        assertTrue(state.contains("上一条"))
        assertFalse(state.contains("最早"))
        assertTrue(state.indexOf("再上一条") < state.indexOf("上一条"))
    }

    @Test fun groupHistoryDistinguishesSpeakersWithoutSendingMemberIds() {
        val rows = listOf(
            WeMessage(4, 4, "team@chatroom", "wxid_anna:\n今天好", MessageType.TEXT.code, 4, 0),
            WeMessage(3, 3, "team@chatroom", "收到", MessageType.TEXT.code, 3, 1),
            WeMessage(2, 2, "team@chatroom", "wxid_bob:\n能帮忙吗", MessageType.TEXT.code, 2, 0),
            WeMessage(1, 1, "team@chatroom", "wxid_anna:\n早上好", MessageType.TEXT.code, 1, 0),
        )
        val body = JevWindow.plan(rows, 4, 3, "").first().body
        val state = Json.parseToJsonElement(body).jsonObject.getValue("state").jsonPrimitive.content
        assertTrue(state.contains("群成员1：早上好"))
        assertTrue(state.contains("群成员2：能帮忙吗"))
        assertTrue(state.contains("我：收到"))
        assertTrue(state.contains("待评价消息（发送者：群成员1）：\n今天好"))
        assertFalse(body.contains("wxid_anna"))
        assertFalse(body.contains("wxid_bob"))
    }

    @Test fun twoHundredMessageWindowStillProducesRequests() {
        val rows = (260L downTo 1L).map { message(it, "消息$it") }
        val jobs = JevWindow.plan(rows, 200, 6, "")
        assertEquals(200, jobs.size)
        assertEquals(260L, jobs.first().id)
        assertEquals(61L, jobs.last().id)
    }

    @Test fun shouldIncludeTwoHundredEarlierMessagesWhenContextIsSetToTwoHundred() {
        val rows = (250L downTo 1L).map { message(it, "历史$it：" + "内容".repeat(40)) }
        val body = JevWindow.plan(rows, 1, 200, "").single().body
        val state = Json.parseToJsonElement(body).jsonObject.getValue("state").jsonPrimitive.content
        assertTrue(state.contains("历史50："))
        assertFalse(state.contains("历史49："))
        assertEquals(200, state.lines().count { it.contains("历史") } - 1)
        assertTrue(state.length < 25000)
    }

    @Test fun renderedModeAnalyzesOnlyVisibleTextAndUsesOlderContext() {
        val rows = listOf(message(8, "现在"), message(7, "链接", MessageType.LINK.code),
            message(6, "刚才"), message(5, "再说一次"), message(4, "更早"))
        val jobs = JevWindow.planVisible(rows, setOf(8L, 5L, 7L), 2, "")
        assertEquals(listOf(8L, 5L), jobs.map { it.id })
        val newest = Json.parseToJsonElement(jobs[0].body).jsonObject.getValue("state").jsonPrimitive.content
        assertTrue(newest.contains("刚才"))
        assertTrue(newest.contains("再说一次"))
        assertFalse(newest.contains("更早"))
        val older = Json.parseToJsonElement(jobs[1].body).jsonObject.getValue("state").jsonPrimitive.content
        assertFalse(older.contains("刚才"))
        assertTrue(older.contains("更早"))
    }

    @Test fun noContextAndNoTextInWindowProduceNoExtraRequests() {
        val rows = listOf(message(3, "照片", MessageType.IMAGE.code), message(2, "你好"), message(1, "旧消息"))
        assertTrue(JevWindow.plan(rows, 1, 0, "").isEmpty())
        val state = Json.parseToJsonElement(JevWindow.plan(rows, 2, 0, "").single().body)
            .jsonObject.getValue("state").jsonPrimitive.content
        assertFalse(state.contains("旧消息"))
    }
}
