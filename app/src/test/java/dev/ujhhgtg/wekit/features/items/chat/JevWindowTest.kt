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
        assertEquals(listOf(4L, 5L), jobs[1].afterIds)
    }

    @Test fun allLaterMessagesAreIncludedForRecentTargets() {
        val rows = (10L downTo 1L).map { message(it, "消息$it") }
        val jobs = JevWindow.plan(rows, 10, 0, emptyMap(), "", "")
        val newest = jobs.first { it.id == 10L }
        assertEquals(0, newest.afterCount)
        assertFalse(state(newest).containsKey("after"))
        val oldest = jobs.last { it.id == 1L }
        assertEquals((2L..10L).toList(), oldest.afterIds)
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
        val jobs = JevWindow.planVisible(rows, setOf(8L, 7L, 5L), 2, 8, 8, emptyMap(), "", "")
        assertEquals(listOf(8L, 5L), jobs.map { it.id })
        assertEquals(listOf("刚才", "[链接]"), texts(state(jobs[0]), "conversation"))
        assertEquals(listOf("更早"), texts(state(jobs[1]), "conversation"))
        assertEquals(listOf("刚才", "[链接]", "现在"), texts(state(jobs[1]), "after"))
    }

    @Test fun should_include_only_same_sender_after_when_evaluating_group_member() {
        val rows = listOf(
            WeMessage(5, 5, "team@chatroom", "wxid_bob:\n嗯", MessageType.TEXT.code, 5 * minute, 0),
            WeMessage(4, 4, "team@chatroom", "wxid_anna:\n好", MessageType.TEXT.code, 4 * minute, 0),
            WeMessage(3, 3, "team@chatroom", "收到", MessageType.TEXT.code, 3 * minute, 1),
            WeMessage(2, 2, "team@chatroom", "wxid_bob:\n我来", MessageType.TEXT.code, 2 * minute, 0),
            WeMessage(1, 1, "team@chatroom", "wxid_anna:\n谁来？", MessageType.TEXT.code, minute, 0),
        )
        val job = JevWindow.plan(rows, 5, 2, emptyMap(), "", "").first { it.id == 2L }
        assertEquals(listOf(5L), job.afterIds)
        assertEquals(listOf("嗯"), texts(state(job), "after"))
        assertEquals(listOf("谁来？"), texts(state(job), "conversation"))
    }

    @Test fun should_include_own_followups_and_adjacent_group_replies_after_outgoing() {
        val rows = listOf(
            WeMessage(6, 6, "team@chatroom", "wxid_anna:\n行", MessageType.TEXT.code, 6 * minute, 0),
            WeMessage(5, 5, "team@chatroom", "再确认一下", MessageType.TEXT.code, 5 * minute, 1),
            WeMessage(4, 4, "team@chatroom", "wxid_carl:\n别的事", MessageType.TEXT.code, 4 * minute, 0),
            WeMessage(3, 3, "team@chatroom", "wxid_bob:\n可以", MessageType.TEXT.code, 3 * minute, 0),
            WeMessage(2, 2, "team@chatroom", "要一起吗", MessageType.TEXT.code, 2 * minute, 1),
            WeMessage(1, 1, "team@chatroom", "wxid_anna:\n下午", MessageType.TEXT.code, minute, 0),
        )
        val job = JevWindow.plan(rows, 6, 2, emptyMap(), "", "").first { it.id == 2L }
        assertEquals(listOf(3L, 5L, 6L), job.afterIds)
        assertEquals(listOf("可以", "再确认一下", "行"), texts(state(job), "after"))
    }

    @Test fun should_include_quoted_group_reply_even_after_adjacent_message() {
        val rows = listOf(
            WeMessage(4, 4, "team@chatroom", "wxid_anna:\n<msg/>", MessageType.QUOTE.code, 4 * minute, 0),
            WeMessage(3, 3, "team@chatroom", "wxid_carl:\n换个话题", MessageType.TEXT.code, 3 * minute, 0),
            WeMessage(2, 2, "team@chatroom", "一起吗", MessageType.TEXT.code, 2 * minute, 1),
            WeMessage(1, 1, "team@chatroom", "wxid_bob:\n明天", MessageType.TEXT.code, minute, 0),
        )
        val quotes = mapOf(4L to JevQuote("可以", fromSelf = true, sender = "self", text = "一起吗"))
        val job = JevWindow.plan(rows, 4, 0, quotes, "", "").first { it.id == 2L }
        assertEquals(listOf(3L, 4L), job.afterIds)
        assertEquals(listOf("换个话题", "可以"), texts(state(job), "after"))
    }

    @Test fun should_count_both_sides_together_in_before_window() {
        val rows = listOf(message(4, "目标"), message(3, "我先说"), message(2, "对方回"), message(1, "很早"))
        val state = state(JevWindow.plan(rows, 1, 2, emptyMap(), "", "").single())
        assertEquals(listOf("对方回", "我先说"), texts(state, "conversation"))
    }

    @Test fun should_select_refresh_targets_by_chat_position_not_by_sender_count() {
        val rows = (10L downTo 1L).map { message(it, "消息$it") }
        val jobs = JevWindow.planRefresh(rows, 3, 4, 2, emptyMap(), "", "")
        assertEquals(listOf(10L, 9L, 8L), jobs.map { it.id })
        assertEquals(listOf(10L), JevWindow.planRefresh(rows, 0, 0, 0, emptyMap(), "", "").map { it.id })
        assertEquals(listOf(10L, 9L, 8L),
            JevWindow.planRefresh(rows, 0, 0, 0, emptyMap(), "", "", newCount = 3).map { it.id })
    }

    @Test fun should_include_both_sides_from_the_entire_refresh_window() {
        val rows = (5L downTo 1L).map { message(it, "消息$it") }
        val job = JevWindow.planRefresh(rows, 3, 3, 0, emptyMap(), "", "").first { it.id == 3L }
        assertEquals(listOf(4L, 5L), job.afterIds)
        assertEquals(listOf("消息4", "消息5"), texts(state(job), "after"))
    }

    @Test fun should_include_all_later_messages_within_refresh_positions() {
        val rows = (8L downTo 1L).map { message(it, "消息$it") }
        val job = JevWindow.planRefresh(rows, 8, 8, 0, emptyMap(), "", "").first { it.id == 1L }
        assertEquals((2L..8L).toList(), job.afterIds)
    }

    @Test fun should_omit_unrelated_recent_tail_when_visible_target_is_outside_refresh_window() {
        val rows = listOf(10L, 9L, 8L, 7L, 1L).map { message(it, "消息$it") }
        val job = JevWindow.planVisible(rows, setOf(1L), 0, 3, 3, emptyMap(), "", "").single()
        assertTrue(job.afterIds.isEmpty())
        assertFalse(state(job).containsKey("after"))
    }

    @Test fun noTextTargetsProduceNoRequests() {
        val rows = listOf(message(3, "", MessageType.IMAGE.code), message(2, "你好"), message(1, "旧消息"))
        assertTrue(JevWindow.plan(rows, 1, 0, emptyMap(), "", "").isEmpty())
        assertTrue(texts(state(JevWindow.plan(rows, 2, 0, emptyMap(), "", "").single()), "conversation").isEmpty())
    }
}
