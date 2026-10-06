package com.onmyway.app

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class ShortRecording(private val context: Context) {
    private var recorder: android.media.AudioRecord? = null
    private var worker: Thread? = null
    private val output = java.io.ByteArrayOutputStream()
    @Volatile private var active = false
    @Volatile private var failed = false
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        stop()
        output.reset(); failed = false
        val min = android.media.AudioRecord.getMinBufferSize(16000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0)
        val recording = android.media.AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
            android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
        recorder = recording
        try {
            check(recording.state == android.media.AudioRecord.STATE_INITIALIZED)
            recording.startRecording(); active = true
            worker = Thread {
                val buffer = ByteArray(3200)
                while (active && output.size() < 960000) {
                    val count = try {
                        recording.read(buffer, 0, minOf(buffer.size, 960000 - output.size()))
                    } catch (_: Exception) { if (active) failed = true; break }
                    if (!active) break
                    if (count > 0) output.write(buffer, 0, count)
                    else { failed = true; break }
                }
            }.also { it.start() }
        } catch (e: Exception) { cancel(); throw e }
    }
    fun finish(): ByteArray {
        stop()
        check(!failed && output.size() >= 6400) { "录音太短或录音失败，请重新说一次。" }
        val pcm = output.toByteArray()
        output.reset()
        return java.nio.ByteBuffer.allocate(44 + pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
    private fun stop() {
        active = false
        val microphone = recorder
        val thread = worker
        runCatching { microphone?.stop() }
        thread?.join(300)
        // Releasing the recorder unblocks a stalled read. Never read or reset
        // the byte buffer until the writer has actually terminated.
        runCatching { microphone?.release() }
        recorder = null
        if (thread?.isAlive == true) thread.join(700)
        check(thread?.isAlive != true) { "录音设备仍在停止，请重试。" }
        worker = null
    }
    fun cancel() {
        runCatching { stop() }
        if (worker?.isAlive != true) output.reset()
    }
}

@Composable
internal fun CloudVoiceButton(server: String, enabled: Boolean, result: (String) -> Unit, failure: (String) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val recordingDevice = remember(context) { ShortRecording(context) }
    var recording by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val prefs = remember { context.getSharedPreferences("cloud_voice", Context.MODE_PRIVATE) }
    var consent by remember { mutableStateOf(prefs.getBoolean("consent_auto_send", false)) }
    var explanation by remember { mutableStateOf(false) }
    val onResult by rememberUpdatedState(result)
    val onFailure by rememberUpdatedState(failure)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (!it) onFailure("未获得麦克风权限，可以继续输入文字。")
    }
    fun cancel() { recordingDevice.cancel(); recording = false; job?.cancel(); processing = false }
    fun start() {
        if (!enabled || processing) return
        if (!consent) { explanation = true; return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(Manifest.permission.RECORD_AUDIO); return
        }
        try {
            recordingDevice.start()
            recording = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } catch (_: Exception) { onFailure("录音启动失败，请检查麦克风。") }
    }
    fun finish() {
        if (!recording) return
        recording = false
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val bytes = try { recordingDevice.finish() } catch (e: Exception) {
            onFailure(e.message ?: "录音失败，请重新说一次。"); return
        }
        processing = true
        job = scope.launch {
            try { onResult(Api(server).transcribe(bytes)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onFailure(networkMessage(e)) }
            finally { processing = false }
        }
    }
    val startAction by rememberUpdatedState({ start() })
    val finishAction by rememberUpdatedState({ finish() })
    val cancelAction by rememberUpdatedState({ cancel() })
    DisposableEffect(owner, recordingDevice) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) cancelAction() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); cancelAction() }
    }
    LaunchedEffect(recording) { if (recording) { delay(30_000); finishAction() } }
    Surface(color = if (recording) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
            if (!enabled || processing) disabled()
            onClick("录音或结束录音") { if (recording) finishAction() else startAction(); true }
        }
            .pointerInput(enabled) { detectTapGestures(onPress = {
                startAction()
                if (tryAwaitRelease()) finishAction() else cancelAction()
            }) }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (processing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(if (processing) "正在识别…" else if (recording) "松开结束 · 最长 30 秒" else if (!enabled) "请先确认上方建议" else "按住说话")
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text("使用云端语音识别") },
        text = { Text("录音将经你的后端发送给已配置的语音服务，松开后识别并自动提交本次语音，不拼接原有文字草稿。任务保存和路线修改仍需确认。本地临时录音会删除，离开页面停止录音。") },
        confirmButton = { TextButton({ prefs.edit().putBoolean("consent_auto_send", true).apply(); consent = true; explanation = false }) { Text("同意") } },
        dismissButton = { TextButton({ explanation = false }) { Text("取消") } })
}
