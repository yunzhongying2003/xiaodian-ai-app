package com.xiaodian.ai

// 老板模式「待处理」页：顾客发来的语音 / 图片 / 视频 / 文件。
// 媒体都先用带鉴权头的请求下载到 cacheDir/media：
//   图片：按屏幕宽度用 inSampleSize 缩小后再解码；
//   语音：MediaPlayer 播放，离开页面（卡片移出）时 DisposableEffect 释放；
//   视频 / 文件：通过 FileProvider 用 ACTION_VIEW 交给系统应用打开。
// 回复草稿存在 ViewModel（按 msgid），自动刷新不会丢、不会抢焦点。
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HandoffPage(
    items: List<HandoffItem>,
    drafts: Map<String, String>,
    busy: Set<String>,
    loadError: String,
    onDraftChange: (HandoffItem, String) -> Unit,
    onReply: (HandoffItem) -> Unit,
    onResolve: (HandoffItem) -> Unit,
    onSendImage: (HandoffItem, android.net.Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val now = rememberNow()
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loadError.isNotBlank()) item(key = "err") { ErrorText(loadError) }
        if (items.isEmpty()) {
            item(key = "empty") {
                Text(
                    "暂无待处理",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }
        items(items, key = { "h-" + it.key() }) { item ->
            HandoffCard(
                item = item,
                now = now,
                draft = drafts[item.key()] ?: "",
                isBusy = "handoff:${item.key()}" in busy,
                onDraftChange = { onDraftChange(item, it) },
                onReply = { onReply(item) },
                onResolve = { onResolve(item) },
                onSendImage = { uri -> onSendImage(item, uri) },
            )
        }
    }
}

@Composable
private fun HandoffCard(
    item: HandoffItem,
    now: Long,
    draft: String,
    isBusy: Boolean,
    onDraftChange: (String) -> Unit,
    onReply: () -> Unit,
    onResolve: () -> Unit,
    onSendImage: (android.net.Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mediaStatus by remember { mutableStateOf("") }
    var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun releasePlayer() {
        val p = player
        player = null
        if (p != null) {
            try { p.stop() } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
    }

    // 卡片离开屏幕 / 切页 / 退出时释放播放器。
    DisposableEffect(item.key()) {
        onDispose { releasePlayer() }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onSendImage(uri)
    }

    fun openMedia() {
        if (item.mediaId.isBlank()) {
            mediaStatus = "这条消息没有媒体 ID"
            return
        }
        loading = true
        mediaStatus = ""
        scope.launch {
            try {
                when (item.msgtype) {
                    "image" -> {
                        val file = mediaFile(context, item, "jpg")
                        withContext(Dispatchers.IO) { HandoffRepository.downloadMedia(item.mediaId, file) }
                        val width = context.resources.displayMetrics.widthPixels
                        imageBitmap = withContext(Dispatchers.IO) { decodeSampled(file, width) }
                        if (imageBitmap == null) mediaStatus = "图片解码失败"
                    }
                    "voice" -> {
                        val file = mediaFile(context, item, "amr")
                        withContext(Dispatchers.IO) { HandoffRepository.downloadMedia(item.mediaId, file) }
                        releasePlayer()
                        val p = MediaPlayer()
                        p.setDataSource(file.absolutePath)
                        p.setOnCompletionListener {
                            if (player === it) releasePlayer()
                            mediaStatus = "播放结束"
                        }
                        p.prepare()
                        p.start()
                        player = p
                        mediaStatus = "正在播放语音"
                    }
                    else -> {
                        val file = mediaFile(context, item, "bin")
                        val contentType = withContext(Dispatchers.IO) {
                            HandoffRepository.downloadMedia(item.mediaId, file)
                        }
                        openWithSystem(context, file, item, contentType)
                    }
                }
            } catch (e: Exception) {
                mediaStatus = "打开失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${item.typeLabel()} · ${TimeFmt.relative(item.createdAt, now)}", style = MaterialTheme.typography.titleLarge)
            Text("顾客 ${shortCustomer(item.externalUserid)}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    onClick = { openMedia() },
                    enabled = !loading,
                    modifier = Modifier.height(52.dp),
                ) { Text(if (loading) "加载中…" else if (item.msgtype == "voice") "播放语音" else "查看${item.typeLabel()}") }
                if (imageBitmap != null || player != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            releasePlayer()
                            imageBitmap = null
                            mediaStatus = ""
                        },
                        modifier = Modifier.height(52.dp),
                    ) { Text(if (player != null) "停止" else "收起") }
                }
            }
            imageBitmap?.let { bmp ->
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "顾客图片",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (mediaStatus.isNotBlank()) Text(mediaStatus, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = { Text("回复顾客") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onReply,
                enabled = draft.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (isBusy) "提交中…" else "发送给顾客") }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("发送图片") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onResolve,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text("已处理") }
            }
        }
    }
}

private fun mediaFile(context: Context, item: HandoffItem, ext: String): File {
    val dir = File(context.cacheDir, "media")
    dir.mkdirs()
    val safe = item.key().replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
    return File(dir, "handoff_$safe.$ext")
}

/** 按目标宽度计算 inSampleSize，先读尺寸再缩小解码，避免大图 OOM。 */
private fun decodeSampled(file: File, reqWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val target = if (reqWidth > 0) reqWidth else 1080
    while (bounds.outWidth / (sample * 2) >= target) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)
}

/** 视频 / 文件：改成正确扩展名后用 FileProvider 交给系统应用打开。 */
private fun openWithSystem(context: Context, downloaded: File, item: HandoffItem, contentType: String) {
    var mime = contentType.substringBefore(';').trim().lowercase()
    if (mime.isEmpty() || mime == "application/octet-stream") {
        mime = if (item.msgtype == "video") "video/mp4" else "*/*"
    }
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        ?: if (item.msgtype == "video") "mp4" else "bin"
    val target = File(downloaded.parentFile, downloaded.nameWithoutExtension + "." + ext)
    if (target.absolutePath != downloaded.absolutePath) {
        if (target.exists()) target.delete()
        if (!downloaded.renameTo(target)) {
            downloaded.copyTo(target, overwrite = true)
            downloaded.delete()
        }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        throw IllegalStateException("手机上没有能打开这个${item.typeLabel()}的应用")
    }
}
