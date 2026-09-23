package dev.ujhhgtg.wekit.features.items.chat

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
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
    WeChatMessageViewApi.IMessageViewLifecycleListener {
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
    private val results = LinkedHashMap<Long, String?>()
    private val pending = mutableMapOf<Long, Int>()
    private val queue = ArrayDeque<Pair<JevJob, Int>>()
    private val renderCandidates = linkedMapOf<Long, Long>()
    private val loadingCandidates = mutableMapOf<Long, Int>()
    private var renderScheduled = false
    private var activeTalker: String? = null
    private var running = 0
    private var loggedResultVersion = -1
    private var loggedRenderPlanVersion = -1
    private var activeChat: Any? = null
    private var windowVersion = 0
    private var apiKey by prefOption("jev_api_key", "")
    private var contextCount by prefOption("jev_context_count", 6)
    private var latestCount by prefOption("jev_latest_count", 20)
    private var renderOnScroll by prefOption("jev_render_on_scroll", false)
    private var batchLimit by prefOption("jev_batch_limit", 4)
    private var prompt by prefOption("jev_prompt", "")
    private var lastKey = ""

    override fun onEnable() {
        lastKey = apiKey.trim()
        WeChatMessageViewApi.addListener(this)
        WeChatMessageViewApi.addLifecycleListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeChatMessageViewApi.removeLifecycleListener(this)
        WeChatMessageViewApi.findBoundViews { true }.forEach { (view, _) -> clearLabel(view) }
        resetWindow()
        pending.clear()
    }

    private fun resetWindow() {
        val previous = windowVersion++
        activeChat = null
        activeTalker = null
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
        val label = results[message.id]
        clearLabel(view)
        if (message.id > 0 && message.typeCode == MessageType.TEXT.code && label != null) {
            showLabel(view, message, label)
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
        val version = windowVersion
        val talker = message.talker
        val count = latestCount.coerceIn(1, 200)
        val historyCount = contextCount.coerceIn(0, 200)
        val instructions = prompt
        windowLoader.execute {
            val jobs = runCatching {
                if (WeDatabaseApi.isReady) {
                    val rows = WeDatabaseApi.getMessages(talker.replace("'", "''"),
                        pageSize = count + historyCount * 3)
                    JevWindow.plan(rows, count, historyCount, instructions).also {
                        WeLogger.i(TAG, "window loaded=${rows.size}, planned=${it.size}")
                    }
                } else {
                    WeLogger.w(TAG, "database not ready for chat window")
                    emptyList()
                }
            }.onFailure { WeLogger.w(TAG, "failed to read chat window (${it.javaClass.simpleName})") }
                .getOrDefault(emptyList())
            mainHandler.post {
                if (version != windowVersion || !isActive || apiKey.trim() != key) return@post
                jobs.forEach { job ->
                    if (!results.containsKey(job.id) && !pending.containsKey(job.id)) {
                        pending[job.id] = version
                        queue.addLast(job to version)
                    }
                }
                WeLogger.i(TAG, "window queued=${queue.size}, cached=${jobs.size - queue.size}, running=$running")
                drain(key)
            }
        }
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        if (renderOnScroll && message.talker == activeTalker) scheduleRendered(message)
    }

    private fun scheduleRendered(message: MessageInfo) {
        val id = message.id
        if (!isActive || id <= 0 || message.typeCode != MessageType.TEXT.code ||
            results.containsKey(id) || pending.containsKey(id) || apiKey.isBlank()) return
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
                val instructions = prompt
                windowLoader.execute {
                    val jobs = runCatching {
                        if (!WeDatabaseApi.isReady) emptyList() else {
                            val rows = linkedMapOf<Long, WeMessage>()
                            for ((candidate, time) in selected.entries.sortedWith(
                                compareByDescending<Map.Entry<Long, Long>> { it.value }.thenByDescending { it.key }
                            )) {
                                if (candidate in rows) continue
                                loadTextMessagesBefore(talker, time, candidate,
                                    historyCount + selected.size + 8).forEach { rows[it.msgId] = it }
                            }
                            JevWindow.planVisible(rows.values.sortedWith(
                                compareByDescending<WeMessage> { it.createTime }
                                    .thenByDescending { it.msgId }
                            ), selected.keys, historyCount, instructions)
                        }
                    }.onFailure { WeLogger.w(TAG, "failed to read rendered messages (${it.javaClass.simpleName})") }
                        .getOrDefault(emptyList())
                    mainHandler.post {
                        selected.keys.forEach { if (loadingCandidates[it] == version) loadingCandidates.remove(it) }
                        if (version == windowVersion && isActive && apiKey.trim() == key) {
                            if (loggedRenderPlanVersion != version) {
                                loggedRenderPlanVersion = version
                                WeLogger.i(TAG, "render first batch selected=${selected.size}, planned=${jobs.size}")
                            }
                            jobs.forEach { job ->
                                if (pending[job.id] == version) {
                                    if (queue.size >= 50) {
                                        val (dropped, oldVersion) = queue.removeFirst()
                                        if (pending[dropped.id] == oldVersion) pending.remove(dropped.id)
                                    }
                                    queue.addLast(job to version)
                                }
                            }
                            drain(key)
                        }
                        val queued = jobs.mapTo(hashSetOf()) { it.id }
                        selected.keys.filter { it !in queued && pending[it] == version }
                            .forEach { pending.remove(it) }
                    }
                }
            }, 120)
        }
    }

    private fun loadTextMessagesBefore(talker: String, time: Long, msgId: Long, limit: Int): List<WeMessage> =
        WeDatabaseApi.executeQuery("""
            SELECT msgId, msgSvrId, talker, content, type, createTime, isSend
            FROM message
            WHERE talker = ? AND type = 1
              AND (createTime < ? OR (createTime = ? AND msgId <= ?))
            ORDER BY createTime DESC, msgId DESC
            LIMIT ?
        """.trimIndent(), arrayOf(talker, time, time, msgId, limit)).map { row ->
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

    private fun drain(key: String) {
        while (running < batchLimit.coerceIn(1, 20) && queue.isNotEmpty()) {
            val (job, version) = queue.removeFirst()
            if (pending[job.id] != version) continue
            if (renderOnScroll && version == windowVersion &&
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
                        runCatching { JevProtocol.label(it.body.string(), job.outgoing) }
                            .onFailure { WeLogger.w(TAG, "invalid API response (${it.javaClass.simpleName})") }
                            .getOrNull()
                    }
                }
                complete(job.id, key, version, result)
            }
        })
    }

    private fun complete(id: Long, key: String, version: Int, label: String?) {
        mainHandler.post {
            running--
            if (pending[id] == version) {
                pending.remove(id)
                if (isActive && apiKey.trim() == key) {
                    if (label != null) results[id] = label
                    if (results.size > 2000) results.remove(results.keys.first())
                    if (label != null) {
                        val bound = WeChatMessageViewApi.findBoundViews { it.id == id }
                        var shown = 0
                        bound.forEach { (view, message) ->
                            if (WeChatMessageViewApi.getBoundMessage(view)?.id == id &&
                                showLabel(view, message, label)) shown++
                        }
                        if (loggedResultVersion != version) {
                            loggedResultVersion = version
                            WeLogger.i(TAG, "first result bound=${bound.size}, shown=$shown, row=${bound.firstOrNull()?.first?.javaClass?.simpleName ?: "none"}")
                        }
                    }
                }
            }
            if (isActive) drain(apiKey.trim())
        }
    }

    private fun showLabel(row: View, message: MessageInfo, label: String): Boolean {
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
        badge.text = label
        badge.setTextColor(if (row.context.isDarkMode) 0xFFB9C4D1.toInt() else 0xFF536678.toInt())
        if (existing == null) parent.addView(badge, params) else badge.layoutParams = params
        return true
    }

    private fun clearLabel(row: View) {
        val badge = row.findViewWithTag<TextView>(VIEW_TAG) ?: return
        (badge.parent as? ViewGroup)?.removeView(badge)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(apiKey) }
            var contextDraft by remember { mutableStateOf(contextCount.toString()) }
            var latestDraft by remember { mutableStateOf(latestCount.toString()) }
            var renderDraft by remember { mutableStateOf(renderOnScroll) }
            var batchDraft by remember { mutableStateOf(batchLimit.toString()) }
            var promptDraft by remember { mutableStateOf(prompt) }
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
                            value = promptDraft,
                            onValueChange = { promptDraft = it.take(300) },
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
                        val nextLatest = latestDraft.toIntOrNull()?.coerceIn(1, 200) ?: latestCount
                        val nextBatch = batchDraft.toIntOrNull()?.coerceIn(1, 20) ?: batchLimit
                        val nextPrompt = promptDraft.trim()
                        val contentChanged = apiKey != nextKey || contextCount != nextContext || prompt != nextPrompt
                        val windowChanged = latestCount != nextLatest || renderOnScroll != renderDraft
                        apiKey = nextKey
                        contextCount = nextContext
                        latestCount = nextLatest
                        renderOnScroll = renderDraft
                        batchLimit = nextBatch
                        prompt = nextPrompt
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
