package com.onmyway.app.feature.planning

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.onmyway.app.R
import com.onmyway.app.core.location.familiarPlaceSuggestions
import com.onmyway.app.core.model.Place
import com.onmyway.app.core.state.AppViewModel
import com.onmyway.app.core.ui.AppConfirmDialog
import com.onmyway.app.core.ui.AppDialogCard
import com.onmyway.app.core.ui.AppIconBadge
import com.onmyway.app.core.ui.PlacePicker
import com.onmyway.app.core.ui.appTextFieldColors
import com.onmyway.app.feature.navigation.destinationQuery
import java.util.Locale
import kotlinx.coroutines.delay

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
    fun pickAlias(alias: String) {
        aliases = false
        val place = vm.familiarPlaces[alias]
        if (place != null) setDestination(place) else { aliasToSave = alias; keyword = "" }
    }
    fun editAlias(alias: String) {
        aliases = false
        aliasToSave = alias
        keyword = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            input,
            { input = it.take(100) },
            enabled = enabled && !recording,
            label = { Text("想去哪里？") },
            placeholder = { Text("导航去图书馆") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = appTextFieldColors(),
            trailingIcon = {
                TextButton({
                    val query = destinationQuery(input)
                    if (query == null) notice = "请只输入本次目的地，如“导航去图书馆”。"
                    else vm.familiarPlace(query)?.let(::setDestination) ?: run { keyword = query }
                }, enabled = enabled && !recording && input.isNotBlank()) { Text("查找") }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val voiceContainer = if (recording) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.secondaryContainer
            val voiceContent = if (recording) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSecondaryContainer
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = voiceContainer,
                modifier = Modifier.weight(1f).heightIn(min = 54.dp)
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
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    AppIconBadge(
                        R.drawable.ic_mic,
                        size = 32.dp,
                        tint = voiceContent,
                        container = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.72f),
                    )
                    Column {
                        Text(
                            if (recording) "正在识别…" else "按住说话",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = voiceContent,
                        )
                        Text(
                            "松开后识别",
                            style = MaterialTheme.typography.labelSmall,
                            color = voiceContent.copy(alpha = 0.75f),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { aliases = true },
                enabled = enabled && !recording,
                modifier = Modifier.heightIn(min = 54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_place_pin), null, Modifier.size(18.dp))
                Text("常用地点", Modifier.padding(start = 5.dp))
            }
        }
        if (notice.isNotBlank()) Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    keyword?.let { query -> PlacePicker(vm, { keyword = null; aliasToSave = null }, initialKeyword = query) { place ->
        aliasToSave?.let { vm.saveFamiliarPlace(it, place) }
        aliasToSave = null; keyword = null; setDestination(place)
    } }
    if (aliases) AppDialogCard(
        "常用地点",
        onClose = { aliases = false },
        subtitle = "选择已保存的地点，或为常用称呼设置位置。",
    ) {
        Column(
            Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (vm.familiarPlaces.keys + familiarPlaceSuggestions).distinct().forEach { alias ->
                val place = vm.familiarPlaces[alias]
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AppIconBadge(
                            R.drawable.ic_place_pin,
                            size = 32.dp,
                            tint = MaterialTheme.colorScheme.tertiary,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                alias,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                place?.name ?: "设置地点",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (place != null) {
                            TextButton({ pickAlias(alias) }) { Text("使用") }
                            TextButton({ editAlias(alias) }) { Text("修改") }
                        } else {
                            TextButton({ editAlias(alias) }) { Text("设置") }
                        }
                    }
                }
            }
        }
        TextButton({ aliases = false }, Modifier.fillMaxWidth()) { Text("关闭") }
    }
    if (consent) AppConfirmDialog(
        title = "使用系统语音服务",
        message = "按住时使用麦克风，松开后识别。手机的语音识别和朗读服务可能联网处理音频或文字；OnMyWay 不保存录音。也可以继续输入文字。",
        confirmLabel = "同意使用",
        onConfirm = {
            voiceConsent = true
            voicePrefs.edit().putBoolean("consent", true).apply()
            consent = false
            notice = "再次按住说话"
        },
        onDismiss = { consent = false },
        dismissLabel = "使用文字",
    )
}
