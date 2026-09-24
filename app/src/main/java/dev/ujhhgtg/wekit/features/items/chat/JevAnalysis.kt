package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import dev.ujhhgtg.wekit.utils.strings.stripWxId
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object JevAnalysis : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener,
    WeChatMessageViewApi.IMessageViewLifecycleListener, WeDatabaseListenerApi.IInsertListener {
    override val technicalId = "Jev 消息分析"
    override val nameRes = R.string.feature_jev_analysis_name
    override val descriptionRes = R.string.feature_jev_analysis_description
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)

    private const val TAG = "JevAnalysis"
    private const val VIEW_TAG = "wekit_jev_label"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowLoader = Executors.newSingleThreadExecutor()
    private val results = LinkedHashMap<Long, JevResult>()
    private val pending = mutableMapOf<Long, Int>()
    private val queue = ArrayDeque<Pair<JevJob, Int>>()
    private val renderCandidates = linkedMapOf<Long, Long>()
    private val loadingCandidates = mutableMapOf<Long, Int>()
    private val checkedRendered = hashSetOf<Long>()
    private var renderScheduled = false
    private var refreshScheduled = false
    private var refreshObserved = 0
    private var refreshNeeded = false
    private var activeTalker: String? = null
    private var running = 0
    private var loggedResultVersion = -1
    private var loggedRenderPlanVersion = -1
    private var activeChat: Any? = null
    private var windowVersion = 0
    private var apiKey by prefOption("jev_api_key", "")
    private var contextCount by prefOption("jev_context_count", 30)
    private var selfRefreshCount by prefOption("jev_self_refresh_count", 20)
    private var otherRefreshCount by prefOption("jev_other_refresh_count", 20)
    private var latestCount by prefOption("jev_latest_count", 20)
    private var renderOnScroll by prefOption("jev_render_on_scroll", false)
    private var batchLimit by prefOption("jev_batch_limit", 4)
    private var note by prefOption("jev_prompt", "")
    private var lastKey = ""

    override fun onEnable() {
        lastKey = apiKey.trim()
        WeChatMessageViewApi.addListener(this)
        WeChatMessageViewApi.addLifecycleListener(this)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeChatMessageViewApi.removeListener(this)
        WeChatMessageViewApi.removeLifecycleListener(this)
        WeChatMessageViewApi.findBoundViews { true }.forEach { (view, _) -> clearLabel(view) }
        resetWindow()
        pending.clear()
    }

    private fun resetWindow() {
        invalidate()
        activeChat = null
        activeTalker = null
        checkedRendered.clear()
        refreshNeeded = false
        refreshScheduled = false
        refreshObserved = 0
    }

    private fun invalidate() {
        val previous = windowVersion++
        (renderCandidates.keys + loadingCandidates.keys).forEach { id ->
            if (pending[id] == previous) pending.remove(id)
        }
        renderCandidates.clear()
        loadingCandidates.clear()
        renderScheduled = false
        queue.forEach { (job, version) ->
            if (pending[job.id] == version) pending.remove(job.id)
        }
        queue.clear()
    }

    private fun needsAnalysis(job: JevJob) = results[job.id]?.afterIds != job.afterIds

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val talker = values.getAsString("talker") ?: return
        mainHandler.post {
            if (!isActive || talker != activeTalker || apiKey.isBlank()) return@post
            checkedRendered.clear()
            refreshObserved = (refreshObserved + 1).coerceAtMost(200)
            if (refreshScheduled) return@post
            refreshScheduled = true
            val version = windowVersion
            mainHandler.postDelayed({
                if (version != windowVersion) return@postDelayed
                refreshScheduled = false
                val newCount = refreshObserved
                refreshObserved = 0
                loadLatest(talker, refresh = true, newCount = newCount)
            }, 120)
        }
    }

    private fun relationKey(talker: String) = "jev_relation_$talker"

    private fun relationOf(talker: String) = WePrefs.getStringOrDef(relationKey(talker), "")

    override fun onCreateView(param: HookParam, view: View) {
        val message = WeChatMessageViewApi.getMsgInfoFromParam(param)
        val key = apiKey.trim()
        if (key != lastKey) {
            lastKey = key
            results.clear()
            pending.clear()
            resetWindow()
            WeChatMessageViewApi.findBoundViews { true }.forEach { (boundView, _) -> clearLabel(boundView) }
        }
        val result = results[message.id]
        clearLabel(view)
        if (message.id > 0 && message.type?.isText == true && result != null) {
            showLabel(view, message, result)
        }
        if (key.isEmpty() || message.talker.isBlank()) return
        val chat = WeChatMessageViewApi.getChattingContextFromParam(param)
        if (chat === activeChat && message.talker == activeTalker) {
            if (renderOnScroll && view.isAttachedToWindow) scheduleRendered(message)
            return
        }
        resetWindow()
        activeChat = chat
        activeTalker = message.talker
        WeLogger.i(TAG, "chat switched mode=${if (renderOnScroll) "render" else "latest"}")
        if (renderOnScroll) {
            if (view.isAttachedToWindow) scheduleRendered(message)
            return
        }
        loadLatest(message.talker)
    }

    private fun loadLatest(talker: String, refresh: Boolean = false, newCount: Int = 1) {
        val version = windowVersion
        val key = apiKey.trim()
        val count = maxOf(latestCount, selfRefreshCount, otherRefreshCount, newCount).coerceIn(1, 200)
        val historyCount = contextCount.coerceIn(0, 200)
        val relation = relationOf(talker)
        val extra = note
        windowLoader.execute {
            val rows = runCatching {
                if (WeDatabaseApi.isReady) WeDatabaseApi.getMessages(talker.replace("'", "''"),
                    pageSize = count.coerceIn(1, 200) + historyCount)
                else emptyList()
            }.onFailure { WeLogger.w(TAG, "failed to read chat window (${it.javaClass.simpleName})") }
                .getOrDefault(emptyList()).sortedWith(
                    compareByDescending<WeMessage> { it.createTime }.thenByDescending { it.msgId })
            val jobs = runCatching {
                JevWindow.planRefresh(rows, selfRefreshCount, otherRefreshCount, historyCount,
                    decodeQuotes(rows), relation, extra, if (refresh) newCount else latestCount)
            }.onFailure { WeLogger.w(TAG, "failed to plan chat window (${it.javaClass.simpleName})") }
                .getOrDefault(emptyList())
            val initialIds = rows.take(latestCount.coerceIn(1, 200)).mapTo(hashSetOf()) { it.msgId }
            mainHandler.post {
                if (version != windowVersion || !isActive || apiKey.trim() != key) return@post
                jobs.forEach { job ->
                    if (!refresh && job.id !in initialIds && job.id !in results) return@forEach
                    if (needsAnalysis(job)) {
                        if (pending.containsKey(job.id)) refreshNeeded = true
                        else {
                            pending[job.id] = version
                            if (queue.size >= 50) {
                                val (dropped, oldVersion) = queue.removeLast()
                                if (pending[dropped.id] == oldVersion) pending.remove(dropped.id)
                            }
                            queue.addLast(job to version)
                        }
                    }
                }
                WeLogger.i(TAG, "window loaded=${rows.size}, planned=${jobs.size}, queued=${queue.size}")
                drain(key)
            }
        }
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        if (renderOnScroll && message.talker == activeTalker) scheduleRendered(message)
    }

    private fun scheduleRendered(message: MessageInfo) {
        val id = message.id
        if (!isActive || id <= 0 || message.type?.isText != true || pending.containsKey(id) || apiKey.isBlank()) return
        if (results.containsKey(id) && id in checkedRendered) return
        val version = windowVersion
        if (renderCandidates.size >= 32) {
            val dropped = renderCandidates.keys.first()
            renderCandidates.remove(dropped)
            if (pending[dropped] == version) pending.remove(dropped)
        }
        pending[id] = version
        renderCandidates[id] = message.createTime
        if (!renderScheduled) {
            renderScheduled = true
            mainHandler.postDelayed({
                if (version != windowVersion) return@postDelayed
                renderScheduled = false
                val selected = renderCandidates.toMap()
                renderCandidates.clear()
                selected.keys.forEach { loadingCandidates[it] = version }
                val talker = activeTalker ?: return@postDelayed
                val key = apiKey.trim()
                val historyCount = contextCount.coerceIn(0, 200)
                val tail = maxOf(selfRefreshCount, otherRefreshCount, 1).coerceIn(1, 200)
                val relation = relationOf(talker)
                val extra = note
                windowLoader.execute {
                    val jobs = runCatching {
                        if (!WeDatabaseApi.isReady) emptyList() else {
                            val rows = linkedMapOf<Long, WeMessage>()
                            for ((candidate, time) in selected.entries.sortedWith(
                                compareByDescending<Map.Entry<Long, Long>> { it.value }.thenByDescending { it.key }
                            )) {
                                if (candidate in rows) continue
                                loadAround(talker, time, candidate, historyCount + selected.size, tail)
                                    .forEach { rows[it.msgId] = it }
                            }
                            val sorted = rows.values.sortedWith(
                                compareByDescending<WeMessage> { it.createTime }.thenByDescending { it.msgId }
                            )
                            JevWindow.planVisible(sorted, selected.keys, historyCount, selfRefreshCount, otherRefreshCount,
                                decodeQuotes(sorted), relation, extra)
                        }
                    }.onFailure { WeLogger.w(TAG, "failed to read rendered messages (${it.javaClass.simpleName})") }
                        .getOrDefault(emptyList())
                    mainHandler.post {
                        selected.keys.forEach { if (loadingCandidates[it] == version) loadingCandidates.remove(it) }
                        val queued = hashSetOf<Long>()
                        if (version == windowVersion && isActive && apiKey.trim() == key) {
                            if (loggedRenderPlanVersion != version) {
                                loggedRenderPlanVersion = version
                                WeLogger.i(TAG, "render first batch selected=${selected.size}, planned=${jobs.size}")
                            }
                            jobs.forEach { job ->
                                if (pending[job.id] == version) {
                                    if (needsAnalysis(job)) {
                                        if (queue.size >= 50) {
                                            val (dropped, oldVersion) = queue.removeFirst()
                                            if (pending[dropped.id] == oldVersion) pending.remove(dropped.id)
                                        }
                                        queue.addLast(job to version)
                                        queued += job.id
                                    } else {
                                        if (checkedRendered.size >= 2000) checkedRendered.clear()
                                        checkedRendered += job.id
                                    }
                                }
                            }
                            drain(key)
                        }
                        selected.keys.filter { it !in queued && pending[it] == version }
                            .forEach { pending.remove(it) }
                    }
                }
            }, 120)
        }
    }

    // 最新刷新范围内的消息（事后视角）+ 目标及之前最多 before+1 条，按时间从新到旧
    private fun loadAround(talker: String, time: Long, msgId: Long, before: Int, tail: Int): List<WeMessage> {
        fun query(where: String?, order: String, limit: Int): List<WeMessage> {
            val args = if (where == null) arrayOf<Any>(talker, limit) else arrayOf<Any>(talker, time, time, msgId, limit)
            val condition = where?.let { "AND ($it)" } ?: ""
            return WeDatabaseApi.executeQuery("""
                SELECT msgId, msgSvrId, talker, content, type, createTime, isSend
                FROM message
                WHERE talker = ? $condition
                ORDER BY createTime $order, msgId $order
                LIMIT ?
            """.trimIndent(), args).map { row ->
                fun long(key: String) = row[key] as? Long ?: 0L
                WeMessage(
                    msgId = long("msgId"),
                    msgSvrId = long("msgSvrId"),
                    talker = row["talker"].toString(),
                    content = row["content"].toString(),
                    typeCode = long("type").toInt(),
                    createTime = long("createTime"),
                    isSend = long("isSend").toInt(),
                )
            }
        }
        val tailRows = if (tail > 0) query(null, "DESC", tail + 1) else emptyList()
        return tailRows + query("createTime < ? OR (createTime = ? AND msgId <= ?)", "DESC", before + 1)
    }

    private fun decodeQuotes(rows: List<WeMessage>): Map<Long, JevQuote> {
        val self = WeApi.selfWxId
        return rows.filter { it.typeCode == MessageType.QUOTE.code }.mapNotNull { row ->
            runCatching {
                val quote = MessageInfo.QuoteMessage(row.content)
                // 单聊的引用可能没有 chatusr，此时用 fromusr
                val sender = runCatching { quote.chatusr }.getOrNull()?.takeIf(String::isNotEmpty)
                    ?: runCatching { quote.fromusr }.getOrDefault("")
                val type = runCatching { quote.type }.getOrDefault(MessageType.TEXT.code)
                val text = if (type == MessageType.TEXT.code) runCatching { quote.content }.getOrDefault("")
                else JevWindow.placeholder(type)
                val group = row.talker.isGroupChatWxId
                row.msgId to JevQuote(
                    if (group) quote.title.stripWxId() else quote.title,
                    sender == self,
                    sender,
                    if (group) text.stripWxId() else text,
                )
            }.onFailure { WeLogger.w(TAG, "failed to decode quote (${it.javaClass.simpleName})") }.getOrNull()
        }.toMap()
    }

    private fun drain(key: String) {
        while (running < batchLimit.coerceIn(1, 20) && queue.isNotEmpty()) {
            val (job, version) = queue.removeFirst()
            if (pending[job.id] != version) continue
            if (job.visibleOnly && version == windowVersion &&
                WeChatMessageViewApi.findBoundViews { it.id == job.id }
                    .none { (view, _) -> view.isAttachedToWindow }) {
                pending.remove(job.id)
                continue
            }
            running++
            request(job, key, version)
        }
    }

    private fun request(job: JevJob, key: String, version: Int) {
        val body = job.body.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(JevProtocol.ENDPOINT)
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                WeLogger.w(TAG, "request failed (${e.javaClass.simpleName})")
                complete(job.id, key, version, null)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    if (!it.isSuccessful) {
                        WeLogger.w(TAG, "API returned HTTP ${it.code}")
                        null
                    } else {
                        runCatching { JevProtocol.parse(it.body.string(), job) }
                            .onFailure { WeLogger.w(TAG, "invalid API response (${it.javaClass.simpleName})") }
                            .getOrNull()
                    }
                }
                complete(job.id, key, version, result)
            }
        })
    }

    private fun complete(id: Long, key: String, version: Int, result: JevResult?) {
        mainHandler.post {
            running--
            if (pending[id] == version) {
                pending.remove(id)
                if (isActive && apiKey.trim() == key && result != null) {
                    results[id] = result
                    if (results.size > 2000) results.remove(results.keys.first())
                    val bound = WeChatMessageViewApi.findBoundViews { it.id == id }
                    var shown = 0
                    bound.forEach { (view, message) ->
                        if (WeChatMessageViewApi.getBoundMessage(view)?.id == id &&
                            showLabel(view, message, result)) shown++
                    }
                    if (loggedResultVersion != version) {
                        loggedResultVersion = version
                        WeLogger.i(TAG, "first result bound=${bound.size}, shown=$shown, row=${bound.firstOrNull()?.first?.javaClass?.simpleName ?: "none"}")
                    }
                }
            }
            if (isActive) {
                if (refreshNeeded && pending.isEmpty() && activeTalker != null && apiKey.isNotBlank()) {
                    refreshNeeded = false
                    loadLatest(activeTalker!!, refresh = true)
                }
                drain(apiKey.trim())
            }
        }
    }

    private fun showLabel(row: View, message: MessageInfo, result: JevResult): Boolean {
        val parent = row as? ViewGroup ?: return false
        val edge = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 12f, row.resources.displayMetrics,
        ).toInt()
        val params = when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = if (message.isSelfSender) Gravity.END else Gravity.START
                marginStart = edge
                marginEnd = edge
                topMargin = edge / 4
            }
            is RelativeLayout -> {
                val content = (0 until parent.childCount)
                    .map(parent::getChildAt)
                    .filterIsInstance<LinearLayout>()
                    .lastOrNull() ?: return false
                if (content.id == View.NO_ID) content.id = View.generateViewId()
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    addRule(RelativeLayout.BELOW, content.id)
                    addRule(if (message.isSelfSender) RelativeLayout.ALIGN_PARENT_END else RelativeLayout.ALIGN_PARENT_START)
                    marginStart = edge
                    marginEnd = edge
                    topMargin = edge / 4
                }
            }
            else -> return false
        }
        val existing = row.findViewWithTag<TextView>(VIEW_TAG)
        val badge = existing ?: TextView(row.context).apply {
            tag = VIEW_TAG
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
        }
        val dark = row.context.isDarkMode
        badge.text = result.label
        badge.setTextColor(when {
            result.warning -> if (dark) 0xFFE0A15A.toInt() else 0xFFC26A1B.toInt()
            dark -> 0xFFB9C4D1.toInt()
            else -> 0xFF536678.toInt()
        })
        badge.setOnClickListener { showPanel(it.context, message, result) }
        if (existing == null) parent.addView(badge, params) else badge.layoutParams = params
        return true
    }

    private fun clearLabel(row: View) {
        val badge = row.findViewWithTag<TextView>(VIEW_TAG) ?: return
        (badge.parent as? ViewGroup)?.removeView(badge)
    }

    private fun reanalyzeChat(talker: String) {
        results.clear()
        pending.clear()
        invalidate()
        WeChatMessageViewApi.findBoundViews { it.talker == talker }.forEach { (view, message) ->
            clearLabel(view)
            if (renderOnScroll) scheduleRendered(message)
        }
        if (!renderOnScroll) loadLatest(talker)
    }

    private fun showPanel(context: Context, message: MessageInfo, result: JevResult) {
        val talker = message.talker
        showComposeDialog(context) {
            var relation by remember { mutableStateOf(relationOf(talker)) }
            AlertDialogContent(
                title = { Text("Jev 分析") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text("${result.target.from}：${result.target.text}", maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Column(Modifier.padding(vertical = 12.dp)) {
                            when (result) {
                                is JevIncoming -> {
                                    ScoreRow("阴阳怪气", result.sarcasm)
                                    ScoreRow("调侃", result.teasing)
                                    result.emotions.take(3).forEach { (name, p) -> ScoreRow(name, p) }
                                    ScoreRow("敷衍", result.perfunctory)
                                    ScoreRow("网络黑话", result.slang)
                                }
                                is JevOutgoing -> {
                                    Text("总评 ${result.grade}", Modifier.padding(bottom = 4.dp))
                                    ScoreRow("回应", result.respond)
                                    ScoreRow("温度", result.warmth)
                                    ScoreRow("语气", result.tone)
                                    ScoreRow("显得冷淡", result.cold)
                                    ScoreRow("像阴阳怪气", result.sarcastic)
                                }
                            }
                        }
                        Text(
                            "${result.model} · ${result.tokens} token" +
                                if (result.afterCount > 0) " · 参考了之后 ${result.afterCount} 条相关消息" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = relation,
                            onValueChange = { relation = it.take(100) },
                            label = { Text("这个聊天的背景（如：对方是我妈）") },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (relation.trim() != relationOf(talker)) {
                            WePrefs.putString(relationKey(talker), relation.trim())
                            reanalyzeChat(talker)
                        }
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        results.remove(message.id)
                        scheduleRendered(message)
                        onDismiss()
                    }) { Text("重新分析") }
                },
            )
        }
    }

    @Composable
    private fun ScoreRow(name: String, value: Double) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { value.toFloat() }, modifier = Modifier.weight(1f).height(6.dp))
            Text(percent(value), Modifier.width(48.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodyMedium)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(apiKey) }
            var contextDraft by remember { mutableStateOf(contextCount.toString()) }
            var selfRefreshDraft by remember { mutableStateOf(selfRefreshCount.toString()) }
            var otherRefreshDraft by remember { mutableStateOf(otherRefreshCount.toString()) }
            var latestDraft by remember { mutableStateOf(latestCount.toString()) }
            var renderDraft by remember { mutableStateOf(renderOnScroll) }
            var batchDraft by remember { mutableStateOf(batchLimit.toString()) }
            var noteDraft by remember { mutableStateOf(note) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_jev_analysis_name)) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.chat_jev_privacy))
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text(stringResource(R.string.chat_jev_api_key)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                        OutlinedTextField(
                            value = contextDraft,
                            onValueChange = { contextDraft = it.filter(Char::isDigit).take(3) },
                            label = { Text(stringResource(R.string.chat_jev_context_count)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = selfRefreshDraft,
                            onValueChange = { selfRefreshDraft = it.filter(Char::isDigit).take(3) },
                            label = { Text(stringResource(R.string.chat_jev_self_refresh_count)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = otherRefreshDraft,
                            onValueChange = { otherRefreshDraft = it.filter(Char::isDigit).take(3) },
                            label = { Text(stringResource(R.string.chat_jev_other_refresh_count)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.chat_jev_render_mode), Modifier.weight(1f))
                            Switch(checked = renderDraft, onCheckedChange = { renderDraft = it })
                        }
                        if (!renderDraft) OutlinedTextField(
                            value = latestDraft,
                            onValueChange = { latestDraft = it.filter(Char::isDigit).take(3) },
                            label = { Text(stringResource(R.string.chat_jev_latest_count)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = batchDraft,
                            onValueChange = { batchDraft = it.filter(Char::isDigit).take(2) },
                            label = { Text(stringResource(R.string.chat_jev_batch_limit)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = noteDraft,
                            onValueChange = { noteDraft = it.take(300) },
                            label = { Text(stringResource(R.string.chat_jev_prompt)) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val nextKey = draft.trim()
                        val nextContext = contextDraft.toIntOrNull()?.coerceIn(0, 200) ?: contextCount
                        val nextSelfRefresh = selfRefreshDraft.toIntOrNull()?.coerceIn(0, 200) ?: selfRefreshCount
                        val nextOtherRefresh = otherRefreshDraft.toIntOrNull()?.coerceIn(0, 200) ?: otherRefreshCount
                        val nextLatest = latestDraft.toIntOrNull()?.coerceIn(1, 200) ?: latestCount
                        val nextBatch = batchDraft.toIntOrNull()?.coerceIn(1, 20) ?: batchLimit
                        val nextNote = noteDraft.trim()
                        val contentChanged = apiKey != nextKey || contextCount != nextContext ||
                            selfRefreshCount != nextSelfRefresh || otherRefreshCount != nextOtherRefresh || note != nextNote
                        val windowChanged = latestCount != nextLatest || renderOnScroll != renderDraft
                        apiKey = nextKey
                        contextCount = nextContext
                        selfRefreshCount = nextSelfRefresh
                        otherRefreshCount = nextOtherRefresh
                        latestCount = nextLatest
                        renderOnScroll = renderDraft
                        batchLimit = nextBatch
                        note = nextNote
                        if (contentChanged || windowChanged) resetWindow()
                        if (contentChanged) {
                            lastKey = nextKey
                            results.clear()
                            pending.clear()
                            WeChatMessageViewApi.findBoundViews { true }
                                .forEach { (view, _) -> clearLabel(view) }
                        } else if (isActive) drain(nextKey)
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }
}
