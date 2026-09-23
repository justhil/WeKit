package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JevWindowTest {
    private val minute = 60_000L

    private fun message(id: Long, text: String, type: Int = MessageType.TEXT.code, minutes: Long = id) =
        WeMessage(id, id, "friend", text, type, minutes * minute, (id % 2).toInt())

    private fun state(job: JevJob) = Json.parseToJsonElement(job.body).jsonObject.getValue("state").jsonObject

    private fun texts(state: JsonObject, field: String) =
        state.getValue(field).jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }

    @Test fun latestMessagesGetEarlierContextAndLaterReplies() {
        val rows = listOf(message(5, "最新"), message(4, "", MessageType.IMAGE.code),
            message(3, "上一条"), message(2, "再上一条"), message(1, "最早"))
        val jobs = JevWindow.plan(rows, 3, 2, emptyMap(), "", "")
        assertEquals(listOf(5L, 3L), jobs.map { it.id })
        assertEquals(listOf("上一条", "[图片]"), texts(state(jobs[0]), "conversation"))
        assertEquals(0, jobs[0].afterCount)
        assertFalse(state(jobs[0]).containsKey("after"))
        assertEquals(listOf("最早", "再上一条"), texts(state(jobs[1]), "conversation"))
        assertEquals(listOf("[图片]", "最新"), texts(state(jobs[1]), "after"))
        assertEquals(2, jobs[1].afterCount)
    }

    @Test fun conversationStopsAtLongGapButKeepsTheMessageBeingAnswered() {
        val rows = listOf(message(4, "晚上", minutes = 600), message(3, "下午两点", minutes = 120),
            message(2, "下午一点", minutes = 60), message(1, "昨天", minutes = -600))
        val state = state(JevWindow.plan(rows, 1, 30, emptyMap(), "", "").single())
        assertEquals(listOf("下午一点", "下午两点"), texts(state, "conversation"))
        assertEquals("隔了 1 小时", state.getValue("conversation").jsonArray[1].jsonObject.getValue("gap").jsonPrimitive.content)
        assertEquals("隔了 8 小时", state.getValue("target").jsonObject.getValue("gap").jsonPrimitive.content)
    }

    @Test fun groupSpeakersAndQuotesStayAnonymous() {
        val rows = listOf(
            WeMessage(4, 4, "team@chatroom", "wxid_bob:\n<msg/>", MessageType.QUOTE.code, 4 * minute, 0),
            WeMessage(3, 3, "team@chatroom", "收到", MessageType.TEXT.code, 3 * minute, 1),
            WeMessage(2, 2, "team@chatroom", "wxid_bob:\n能帮忙吗", MessageType.TEXT.code, 2 * minute, 0),
            WeMessage(1, 1, "team@chatroom", "wxid_anna:\n今天又加班", MessageType.TEXT.code, minute, 0),
        )
        val quotes = mapOf(4L to JevQuote("辛苦了哈", fromSelf = false, sender = "wxid_anna", text = "今天又加班"))
        val job = JevWindow.plan(rows, 1, 30, quotes, "", "").single()
        val target = state(job).getValue("target").jsonObject
        assertEquals("群成员2", target.getValue("from").jsonPrimitive.content)
        assertEquals("辛苦了哈", target.getValue("text").jsonPrimitive.content)
        assertEquals("群成员1", target.getValue("quote").jsonObject.getValue("from").jsonPrimitive.content)
        assertEquals(listOf("今天又加班", "能帮忙吗", "收到"), texts(state(job), "conversation"))
        assertFalse(job.body.contains("wxid_"))
    }

    @Test fun undecodedQuoteIsOnlyAPlaceholder() {
        val rows = listOf(message(2, "<msg/>", MessageType.QUOTE.code), message(1, "你好"))
        val job = JevWindow.plan(rows, 2, 5, emptyMap(), "", "").single()
        assertEquals(1L, job.id)
        assertEquals(listOf("[引用消息]"), texts(state(job), "after"))
    }

    @Test fun twoHundredMessageWindowStillProducesRequests() {
        val rows = (260L downTo 1L).map { message(it, "消息$it") }
        val jobs = JevWindow.plan(rows, 200, 6, emptyMap(), "", "")
        assertEquals(200, jobs.size)
        assertEquals(260L, jobs.first().id)
        assertEquals(61L, jobs.last().id)
    }

    @Test fun longContextKeepsNearbyMessagesLonger() {
        val rows = (250L downTo 1L).map { message(it, "历史$it：" + "内容".repeat(200)) }
        val conversation = texts(state(JevWindow.plan(rows, 1, 200, emptyMap(), "", "").single()), "conversation")
        assertEquals(200, conversation.size)
        assertTrue(conversation.first().startsWith("历史50："))
        assertEquals(195, conversation.count { it.length == 80 })
        assertEquals(5, conversation.count { it.length == 300 })
    }

    @Test fun renderedModeAnalyzesOnlyVisibleTextMessages() {
        val rows = listOf(message(8, "现在"), message(7, "", MessageType.LINK.code),
            message(6, "刚才"), message(5, "再说一次"), message(4, "更早"))
        val jobs = JevWindow.planVisible(rows, setOf(8L, 7L, 5L), 2, emptyMap(), "", "")
        assertEquals(listOf(8L, 5L), jobs.map { it.id })
        assertEquals(listOf("刚才", "[链接]"), texts(state(jobs[0]), "conversation"))
        assertEquals(listOf("更早"), texts(state(jobs[1]), "conversation"))
        assertEquals(listOf("刚才", "[链接]", "现在"), texts(state(jobs[1]), "after"))
    }

    @Test fun noTextTargetsProduceNoRequests() {
        val rows = listOf(message(3, "", MessageType.IMAGE.code), message(2, "你好"), message(1, "旧消息"))
        assertTrue(JevWindow.plan(rows, 1, 0, emptyMap(), "", "").isEmpty())
        assertTrue(texts(state(JevWindow.plan(rows, 2, 0, emptyMap(), "", "").single()), "conversation").isEmpty())
    }
}
