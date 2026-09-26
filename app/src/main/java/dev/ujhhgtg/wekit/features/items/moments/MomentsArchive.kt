package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.TimelineObjectProto
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveOptionDropdown
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

object MomentsArchive : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "朋友圈快照"
    override val nameRes = R.string.feature_moments_archive_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_moments_archive_description

    private const val TAG = "MomentsArchive"
    private const val DB_NAME = "wekit-moments.db"
    private const val TABLE = "sns_posts"

    private val db: SQLiteDatabase by lazy {
        val file = File(HostInfo.application.filesDir, DB_NAME)
        SQLiteDatabase.openOrCreateDatabase(file, null).also { database ->
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS $TABLE(
                     snsId INTEGER PRIMARY KEY,
                     userName TEXT NOT NULL,
                     createTime INTEGER NOT NULL DEFAULT 0,
                     type INTEGER NOT NULL DEFAULT 0,
                     sourceType INTEGER NOT NULL DEFAULT 0,
                     content BLOB,
                     attrBuf BLOB
                   )"""
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_user ON $TABLE(userName, createTime)")
        }
    }

    data class ArchivedPost(
        val snsId: Long,
        val userName: String,
        val createTime: Int,
        val type: Int,
        val contentDesc: String,
    )

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        runCatching { db.path } // open eagerly so table exists even before first write
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        archive(values)
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "SnsInfo") return
        archive(values)
    }

    /**
     * Upserts by snsId without clobbering columns the host update didn't carry: host `update`
     * statements ship only changed fields, so a blind REPLACE would null out content/attrBuf.
     */
    private fun archive(values: ContentValues) {
        val snsId = values.getAsLong("snsId") ?: return
        if (snsId == 0L) return
        runCatching {
            val cols = ContentValues()
            cols.put("snsId", snsId)
            values.getAsString("userName")?.let { cols.put("userName", it) }
            values.getAsInteger("createTime")?.let { cols.put("createTime", it) }
            values.getAsInteger("type")?.let { cols.put("type", it) }
            values.getAsInteger("sourceType")?.let { cols.put("sourceType", it) }
            values.getAsByteArray("content")?.let { cols.put("content", it) }
            values.getAsByteArray("attrBuf")?.let { cols.put("attrBuf", it) }
            if (!cols.containsKey("userName")) cols.put("userName", "")

            val updated = db.update(TABLE, cols, "snsId = ?", arrayOf(snsId.toString()))
            if (updated == 0) {
                db.insertWithOnConflict(TABLE, null, cols, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }.onFailure { WeLogger.w(TAG, "failed to archive snsId=$snsId", it) }
    }

    /** Bulk-import everything currently in the host SnsInfo cache. */
    fun importExisting(): Int {
        var count = 0
        WeMomentsApi.rawQuerySnsInfo(
            "SELECT snsId, userName, createTime, type, sourceType, content, attrBuf FROM SnsInfo WHERE snsId != 0"
        ).use { cursor ->
            val idx = (0 until cursor.columnCount).associateBy { cursor.getColumnName(it) }
            while (cursor.moveToNext()) {
                runCatching {
                    archive(ContentValues().apply {
                        put("snsId", cursor.getLong(idx.getValue("snsId")))
                        put("userName", cursor.getString(idx.getValue("userName")) ?: "")
                        put("createTime", cursor.getInt(idx.getValue("createTime")))
                        put("type", cursor.getInt(idx.getValue("type")))
                        put("sourceType", cursor.getInt(idx.getValue("sourceType")))
                        idx["content"]?.let { cursor.getBlob(it)?.let { b -> put("content", b) } }
                        idx["attrBuf"]?.let { cursor.getBlob(it)?.let { b -> put("attrBuf", b) } }
                    })
                    count++
                }
            }
        }
        return count
    }

    private fun queryPosts(filterUser: String?): List<ArchivedPost> {
        val posts = mutableListOf<ArchivedPost>()
        val sql = buildString {
            append("SELECT snsId, userName, createTime, type, content FROM $TABLE")
            if (filterUser != null) append(" WHERE userName = ?")
            append(" ORDER BY createTime DESC LIMIT 500")
        }
        val args = if (filterUser != null) arrayOf(filterUser) else emptyArray<String>()
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                val content = cursor.getBlob(4)
                val desc = content?.let(::decodeDesc).orEmpty()
                posts += ArchivedPost(
                    snsId = cursor.getLong(0),
                    userName = cursor.getString(1),
                    createTime = cursor.getInt(2),
                    type = cursor.getInt(3),
                    contentDesc = desc,
                )
            }
        }
        return posts
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeDesc(content: ByteArray): String =
        runCatching {
            ProtoBuf.decodeFromByteArray<TimelineObjectProto>(content).contentDesc
        }.getOrNull().orEmpty()

    /**
     * Jumps to the native post detail UI when the row still lives in the host SnsInfo cache.
     * Returns false when the host already evicted it — the snapshot keeps text/media metadata
     * but there is nothing left for the native UI to bind to.
     */
    private fun openNativeDetail(context: Context, snsId: Long): Boolean {
        val rowId = WeMomentsApi.rawQuerySnsInfo(
            "SELECT rowid FROM SnsInfo WHERE snsId = ?", arrayOf(snsId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false
        context.startActivity(Intent().apply {
            setClassName(PackageNames.WECHAT, "${PackageNames.WECHAT}.plugin.sns.ui.SnsCommentDetailUI")
            putExtra("INTENT_SNS_LOCAL_ID", "sns_table_$rowId")
            putExtra("INTENT_FROMGALLERY", false)
        })
        return true
    }

    private fun queryAuthors(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT userName, MAX(createTime) FROM $TABLE GROUP BY userName ORDER BY 2 DESC",
            emptyArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val wxid = cursor.getString(0)
                list += wxid to runCatching { WeDatabaseApi.getDisplayName(wxid) }.getOrDefault(wxid)
            }
        }
        return list
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var authors by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
            var posts by remember { mutableStateOf<List<ArchivedPost>>(emptyList()) }
            var selectedUser by remember { mutableStateOf<String?>(null) }
            var loading by remember { mutableStateOf(true) }
            var expanded by remember { mutableStateOf(false) }
            var importResult by remember { mutableIntStateOf(-1) }

            fun reload(user: String? = selectedUser) {
                loading = true
                thread {
                    val a = queryAuthors()
                    val p = queryPosts(user)
                    authors = a
                    posts = p
                    loading = false
                }
            }

            LaunchedEffect(Unit) { reload() }

            val allLabel = stringResource(R.string.moments_archive_all)
            val options = remember(authors, allLabel) {
                listOf(DropdownOption<String?>(null, allLabel)) +
                        authors.map { (wxid, name) -> DropdownOption<String?>(wxid, name) }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_moments_archive_name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                val selectedLabel = options.firstOrNull { it.value == selectedUser }?.label
                                    ?: allLabel
                                Text(
                                    selectedLabel,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expanded = true }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                ExpressiveOptionDropdown(
                                    expanded = expanded,
                                    value = selectedUser,
                                    options = options,
                                    onDismissRequest = { expanded = false },
                                    onValueChange = {
                                        selectedUser = it
                                        expanded = false
                                        reload(it)
                                    },
                                )
                            }
                            TextButton(onClick = {
                                thread {
                                    val n = runCatching { importExisting() }.getOrDefault(-1)
                                    importResult = n
                                    reload()
                                }
                            }) {
                                Text(stringResource(R.string.moments_archive_import))
                            }
                        }

                        if (importResult >= 0) {
                            Text(
                                stringResource(R.string.moments_archive_imported, importResult),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (loading) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) { CircularProgressIndicator() }
                        } else if (posts.isEmpty()) {
                            Text(
                                stringResource(R.string.moments_archive_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(posts, key = { it.snsId }) { post ->
                                    val name = remember(post.userName) {
                                        authors.firstOrNull { it.first == post.userName }?.second
                                            ?: post.userName
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (!openNativeDetail(context, post.snsId)) {
                                                    Toast.makeText(
                                                        context,
                                                        R.string.moments_archive_evicted,
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                remember(post.createTime) {
                                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                                        .format(Date(post.createTime.toLong() * 1000))
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (post.contentDesc.isNotEmpty()) {
                                            Text(
                                                post.contentDesc,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 3,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
