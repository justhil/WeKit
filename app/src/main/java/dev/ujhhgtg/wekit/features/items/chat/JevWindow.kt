package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId

data class JevJob(val id: Long, val body: String, val target: JevLine, val outgoing: Boolean,
                  val afterCount: Int, val afterIds: List<Long> = emptyList(), val visibleOnly: Boolean = false)

/** 解析好的引用回复：[title] 是回复本身，[text] 是被引用的那句话（非文字时为占位符）。 */
data class JevQuote(val title: String, val fromSelf: Boolean, val sender: String, val text: String)

object JevWindow {
    private const val SESSION_GAP = 6 * 60 * 60 * 1000L
    private const val GAP_NOTE = 30 * 60 * 1000L
    private const val NEAR = 5

    fun plan(rows: List<WeMessage>, latestCount: Int, contextCap: Int, quotes: Map<Long, JevQuote>,
             relation: String, note: String): List<JevJob> =
        planMatching(rows, contextCap, latestCount, latestCount, quotes, relation, note) { index, _ -> index < latestCount.coerceIn(1, 200) }

    fun planVisible(rows: List<WeMessage>, visibleIds: Set<Long>, contextCap: Int, selfWindow: Int, otherWindow: Int,
                    quotes: Map<Long, JevQuote>, relation: String, note: String): List<JevJob> =
        planMatching(rows, contextCap, selfWindow, otherWindow, quotes, relation, note) { _, message -> message.msgId in visibleIds }
            .map { it.copy(visibleOnly = true) }

    fun planRefresh(rows: List<WeMessage>, selfCount: Int, otherCount: Int, contextCap: Int,
                    quotes: Map<Long, JevQuote>, relation: String, note: String, newCount: Int = 1): List<JevJob> =
        planMatching(rows, contextCap, maxOf(selfCount, newCount), maxOf(otherCount, newCount), quotes, relation, note) { index, message ->
            index < newCount || index < if (message.isSend != 0) selfCount.coerceIn(0, 200) else otherCount.coerceIn(0, 200)
        }

    fun placeholder(code: Int, content: String = ""): String {
        val type = MessageType.fromCode(code) ?: return "[其他消息]"
        return when {
            type == MessageType.IMAGE -> "[图片]"
            type == MessageType.VOICE -> "[语音]"
            type == MessageType.VIDEO || type == MessageType.MICRO_VIDEO -> "[视频]"
            type.isSticker -> "[表情包]"
            type.isLocation -> "[位置]"
            type == MessageType.CARD -> "[名片]"
            type.isLink -> "[链接]"
            type == MessageType.FILE -> "[文件]"
            type == MessageType.TRANSFER -> "[转账]"
            type.isRedPacket -> "[红包]"
            type.isVoip -> "[通话]"
            type == MessageType.PAT || "拍了拍" in content -> "[拍了拍]"
            type == MessageType.RECALL || "撤回了一条消息" in content -> "[撤回了一条消息]"
            type.isSystem -> "[系统消息]"
            type == MessageType.QUOTE -> "[引用消息]"
            type == MessageType.APP -> "[卡片消息]"
            else -> "[其他消息]"
        }
    }

    // rows 按时间从新到旧排列
    private fun planMatching(rows: List<WeMessage>, contextCap: Int, selfWindow: Int, otherWindow: Int,
                             quotes: Map<Long, JevQuote>, relation: String,
                             note: String, selected: (Int, WeMessage) -> Boolean): List<JevJob> {
        val members = rows.asReversed().asSequence()
            .filter { it.talker.isGroupChatWxId && it.isSend == 0 }
            .mapNotNull { it.content.substringBefore(":\n", "").takeIf(String::isNotEmpty) }
            .distinct().withIndex().associate { (index, id) -> id to index + 1 }
        val cap = contextCap.coerceIn(0, 200)
        return rows.mapIndexedNotNull { index, message ->
            if (!selected(index, message) || message.msgId <= 0) return@mapIndexedNotNull null
            val text = message.body(quotes)?.trim()?.take(4000)
            if (text.isNullOrEmpty()) return@mapIndexedNotNull null
            // 目标前的那条无论隔多久都保留（迟到的回复仍在回应它），再往前遇到超过 6 小时的空档就算另一段对话
            val earlier = mutableListOf<WeMessage>()
            for (row in rows.subList(index + 1, rows.size)) {
                if (earlier.size >= cap || earlier.isNotEmpty() && earlier.last().createTime - row.createTime > SESSION_GAP) break
                if (row.msgId > 0) earlier += row
            }
            earlier.reverse()
            val conversation = earlier.mapIndexed { i, row ->
                row.line(members, quotes, if (i >= earlier.size - NEAR) 300 else 80, earlier.getOrNull(i - 1)?.createTime)
            }
            // 刷新范围内的目标参考全部后续消息；群聊不把别人的话归到目标发送者
            val outgoing = message.isSend != 0
            val afterWindow = (if (outgoing) selfWindow else otherWindow).coerceIn(1, 200)
            val sender = message.memberId()
            val afterRows = (if (index < afterWindow) index - 1 downTo 0 else IntRange.EMPTY).mapNotNull { position ->
                val row = rows[position]
                when {
                    row.msgId <= 0 -> null
                    !outgoing -> row.takeIf {
                        it.isSend == 0 && (!message.talker.isGroupChatWxId ||
                            sender.isNotEmpty() && it.memberId() == sender)
                    }
                    row.isSend != 0 || !message.talker.isGroupChatWxId -> row
                    rows[position + 1].isSend != 0 ||
                        quotes[row.msgId]?.let { it.fromSelf && it.text == text } == true -> row
                    else -> null
                }
            }
            val after = afterRows.mapIndexed { i, row ->
                row.line(members, quotes, if (i >= afterRows.size - NEAR) 300 else 40, null)
            }
            val target = JevLine(message.speaker(members), text,
                earlier.lastOrNull()?.let { gap(message.createTime - it.createTime) },
                quotes[message.msgId]?.line(message.talker, members))
            JevJob(message.msgId, JevProtocol.request(target, conversation, after, relation, note, outgoing),
                target, outgoing, after.size, afterRows.map { it.msgId })
        }
    }

    private fun WeMessage.memberId(): String =
        if (isSend == 0 && talker.isGroupChatWxId) content.substringBefore(":\n", "") else ""

    private fun WeMessage.body(quotes: Map<Long, JevQuote>): String? = when (typeCode) {
        MessageType.TEXT.code -> text()
        MessageType.QUOTE.code -> quotes[msgId]?.title
        else -> null
    }

    private fun WeMessage.line(members: Map<String, Int>, quotes: Map<Long, JevQuote>, limit: Int, previousTime: Long?) =
        JevLine(speaker(members), (body(quotes) ?: placeholder(typeCode, content)).trim().take(limit),
            previousTime?.let { gap(createTime - it) }, quotes[msgId]?.line(talker, members))

    private fun JevQuote.line(talker: String, members: Map<String, Int>) = JevLine(
        when {
            fromSelf -> "我"
            !talker.isGroupChatWxId -> "对方"
            else -> members[sender]?.let { "群成员$it" } ?: "群成员"
        },
        text.trim().take(300),
    )

    private fun gap(millis: Long): String? {
        val minutes = millis / 60_000
        return when {
            millis < GAP_NOTE -> null
            minutes >= 1440 -> "隔了 ${minutes / 1440} 天"
            minutes >= 60 -> "隔了 ${minutes / 60} 小时"
            else -> "隔了 $minutes 分钟"
        }
    }

    private fun WeMessage.speaker(members: Map<String, Int>): String = when {
        isSend != 0 -> "我"
        !talker.isGroupChatWxId -> "对方"
        else -> members[content.substringBefore(":\n", "")]?.let { "群成员$it" } ?: "群成员"
    }

    private fun WeMessage.text(): String =
        if (talker.isGroupChatWxId && isSend == 0) content.substringAfter(":\n", content) else content
}
