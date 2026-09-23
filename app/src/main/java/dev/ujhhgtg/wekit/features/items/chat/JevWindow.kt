package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId

data class JevJob(val id: Long, val body: String, val outgoing: Boolean)

object JevWindow {
    fun plan(rows: List<WeMessage>, latestCount: Int, contextCount: Int, prompt: String): List<JevJob> =
        planMatching(rows, contextCount, prompt) { index, _ -> index < latestCount.coerceIn(1, 200) }

    fun planVisible(rows: List<WeMessage>, visibleIds: Set<Long>, contextCount: Int, prompt: String): List<JevJob> =
        planMatching(rows, contextCount, prompt) { _, message -> message.msgId in visibleIds }

    private fun planMatching(rows: List<WeMessage>, contextCount: Int, prompt: String,
                             selected: (Int, WeMessage) -> Boolean): List<JevJob> {
        val members = rows.asReversed().asSequence()
            .filter { it.talker.isGroupChatWxId && it.isSend == 0 }
            .mapNotNull { it.content.substringBefore(":\n", "").takeIf(String::isNotEmpty) }
            .distinct().withIndex().associate { (index, id) -> id to index + 1 }
        return rows.mapIndexedNotNull { index, message ->
            if (!selected(index, message) || message.msgId <= 0 || message.typeCode != MessageType.TEXT.code) return@mapIndexedNotNull null
            val text = message.text().trim().take(4000)
            if (text.isEmpty()) return@mapIndexedNotNull null
            val earlier = rows.asSequence().drop(index + 1)
                .filter { it.typeCode == MessageType.TEXT.code && it.msgId > 0 }
                .take(contextCount.coerceIn(0, 200))
                .map { it.speaker(members) + "：" + it.text().trim().take(80) }
                .toList().asReversed()
            JevJob(
                message.msgId,
                JevProtocol.request(text, message.isSend != 0,
                    JevProtocol.context(earlier, contextCount), prompt, message.speaker(members)),
                message.isSend != 0,
            )
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
