package com.onmyway.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.Locale

private class VoiceInput(private val context: Context, val result: (String) -> Unit, val status: (String, Boolean) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    private var accepting = false
    var running = false
        private set
    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { status("手机暂无语音识别服务，请使用文字输入。", false); return }
        accepting = true; running = true
        status("正在听，松开后识别", true)
        try {
            val active = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
            active.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { if (accepting) status("正在识别…", true) }
                override fun onError(error: Int) {
                    running = false
                    if (accepting) status(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，请重试或输入文字。"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请允许麦克风权限，或输入文字。"
                        else -> "语音识别不可用，请重试或输入文字。"
                    }, false)
                    accepting = false
                }
                override fun onResults(results: Bundle?) {
                    running = false
                    if (!accepting) return
                    accepting = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    status(if (text.isBlank()) "没听清，请重试。" else "可修改文字，再查找地点", false)
                    if (text.isNotBlank()) result(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            active.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN").putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        } catch (_: Exception) { cancel(); status("语音服务启动失败，请输入文字。", false) }
    }
    fun stop() { if (running) { recognizer?.stopListening(); status("正在识别…", true) } }
    fun cancel() { accepting = false; running = false; recognizer?.cancel(); status("", false) }
    fun close() { cancel(); recognizer?.destroy(); recognizer = null }
}

@Composable
internal fun DestinationInput(vm: AppViewModel, enabled: Boolean, choose: (Place) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var input by rememberSaveable { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf<String?>(null) }
    var aliasToSave by remember { mutableStateOf<String?>(null) }
    var aliases by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    val voicePrefs = remember { context.getSharedPreferences("voice", Context.MODE_PRIVATE) }
    var voiceConsent by remember { mutableStateOf(voicePrefs.getBoolean("consent", false)) }
    var speechReady by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    val voice = remember(context) { VoiceInput(context, { input = it }, { text, active -> notice = text; recording = active }) }
    DisposableEffect(context, owner) {
        val engine = TextToSpeech(context) { status ->
            speechReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)?.let { it >= 0 } == true
        }
        tts = engine
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { voice.cancel(); engine.stop() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); voice.close(); engine.shutdown(); tts = null }
    }
    LaunchedEffect(recording) {
        if (recording) { delay(30_000); voice.cancel(); notice = "识别已超时，请重试或输入文字。" }
    }
    LaunchedEffect(enabled) { if (!enabled) voice.cancel() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice = if (granted) "再次按住说话" else "未授权麦克风，可输入文字。"
    }
    fun ready(): Boolean {
        if (!voiceConsent) { consent = true; return false }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.launch(Manifest.permission.RECORD_AUDIO); return false
        }
        return true
    }
    fun setDestination(place: Place) {
        choose(place)
        notice = "目的地已设为${place.name}，请核对起点后查看路线。"
        if (voiceConsent && speechReady) tts?.speak(notice, TextToSpeech.QUEUE_FLUSH, null, "destination")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(input, { input = it.take(100) }, enabled = enabled && !recording,
            label = { Text("想去哪里？") }, placeholder = { Text("导航去图书馆") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { TextButton({
                val query = destinationQuery(input)
                if (query == null) notice = "请只输入本次目的地，如“导航去图书馆”。"
                else vm.familiarPlace(query)?.let(::setDestination) ?: run { keyword = query }
            }, enabled = enabled && !recording && input.isNotBlank()) { Text("查找") } })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics { onClick("开始或结束语音输入") {
                        if (enabled) { if (voice.running) voice.stop() else if (ready()) { tts?.stop(); voice.start() } }; true
                    } }
                    .pointerInput(enabled, voiceConsent) {
                        detectTapGestures(onPress = {
                            if (enabled && !voice.running && ready()) {
                                tts?.stop(); voice.start()
                                val released = tryAwaitRelease()
                                if (released) voice.stop() else voice.cancel()
                            }
                        })
                    }) {
                Box(Modifier.padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(if (recording) "正在识别…" else "按住说话") }
            }
            TextButton({ aliases = true }, enabled = enabled && !recording) { Text("常用地点") }
        }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    keyword?.let { query -> PlacePicker(vm, { keyword = null; aliasToSave = null }, initialKeyword = query) { place ->
        aliasToSave?.let { vm.saveFamiliarPlace(it, place) }
        aliasToSave = null; keyword = null; setDestination(place)
    } }
    if (aliases) AlertDialog(onDismissRequest = { aliases = false }, title = { Text("常用地点") }, text = {
        Column {
            listOf("家", "宿舍", "学校", "公司").forEach { alias ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton({
                        aliases = false
                        val place = vm.familiarPlaces[alias]
                        if (place != null) setDestination(place) else { aliasToSave = alias; keyword = "" }
                    }, modifier = Modifier.weight(1f)) { Text("$alias · ${vm.familiarPlaces[alias]?.name ?: "设置地点"}") }
                    if (vm.familiarPlaces[alias] != null) TextButton({ aliases = false; aliasToSave = alias; keyword = "" }) { Text("修改") }
                }
            }
        }
    }, confirmButton = { TextButton({ aliases = false }) { Text("关闭") } })
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("使用系统语音服务") },
        text = { Text("按住时使用麦克风，松开后识别。手机的语音识别和朗读服务可能联网处理音频或文字；OnMyWay 不保存录音。也可以继续输入文字。") },
        confirmButton = { TextButton({ voiceConsent = true; voicePrefs.edit().putBoolean("consent", true).apply(); consent = false; notice = "再次按住说话" }) { Text("同意使用") } },
        dismissButton = { TextButton({ consent = false }) { Text("使用文字") } })
}
