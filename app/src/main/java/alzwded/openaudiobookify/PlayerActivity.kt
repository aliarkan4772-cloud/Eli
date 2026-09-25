package alzwded.openaudiobookify

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlayerScreen() }
    }
}

@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("player", 0) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentUri by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0) }
    var dur by remember { mutableStateOf(1) }
    var speed by remember { mutableStateOf(1.0f) }
    var sleepLeft by remember { mutableStateOf(0) }

    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var ttsMessage by remember { mutableStateOf("") }

    val savePos: () -> Unit = {
        val p = player
        val u = currentUri
        if (p != null && u != null) {
            prefs.edit().putString("pos_" + u, p.currentPosition.toString()).apply()
        }
    }

    val generateTTS: () -> Unit = {
        if (inputText.isNotBlank()) {
            isGenerating = true
            ttsMessage = "generating..."
            scope.launch(Dispatchers.IO) {
                try {
                    val hfToken = "hf_YOUR_TOKEN_HERE"
                    val modelId = "mehdi-hf/pocket-tts-farsi-v2"
                    val url = URL("https://api-inference.huggingface.co/models/$modelId")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = 120000
                    connection.readTimeout = 120000
                    connection.setRequestProperty("Authorization", "Bearer $hfToken")
                    connection.setRequestProperty("Content-Type", "application/json")

                    val safeText = inputText.replace("\\", "\\\\").replace("\"", "\\\"")
                    val jsonBody = "{\"inputs\": \"$safeText\"}"
                    connection.outputStream.use { os ->
                        os.write(jsonBody.toByteArray(Charsets.UTF_8))
                    }

                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_OK) {
                        val file = File(context.cacheDir, "tts_output.wav")
                        connection.inputStream.use { input ->
                            FileOutputStream(file).use { output ->
                                input.copyTo(output)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            player?.release()
                            currentUri = file.absolutePath
                            player = MediaPlayer().apply {
                                setDataSource(file.absolutePath)
                                prepare()
                                dur = duration.coerceAtLeast(1)
                            }
                            pos = 0
                            playing = false
                            ttsMessage = "sound ready!"
                        }
                    } else {
                        val err = connection.errorStream?.bufferedReader()?.readText() ?: "no msg"
                        withContext(Dispatchers.Main) { ttsMessage = "error $code: $err" }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { ttsMessage = "error: ${e.message}" }
                } finally {
                    withContext(Dispatchers.Main) { isGenerating = false }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            player?.release()
            currentUri = uri.toString()
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                dur = duration.coerceAtLeast(1)
                val saved = prefs.getString("pos_" + uri.toString(), null)
                if (saved != null) seekTo(saved.toInt())
            }
            pos = player?.currentPosition ?: 0
            playing = false
        }
    }

    val setSpeed: (Float) -> Unit = { s ->
        speed = s
        val p = player
        if (p != null) {
            val wasPlaying = p.isPlaying
            p.playbackParams = PlaybackParams().setSpeed(s)
            if (!wasPlaying) p.pause()
        }
    }

    val skip: (Int) -> Unit = { ms ->
        val p = player
        if (p != null) {
            val target = (p.currentPosition + ms).coerceIn(0, p.duration.coerceAtLeast(0))
            p.seekTo(target)
            pos = target
        }
    }

    val transcript = remember {
        val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OpenAudioBookify_Transcript.txt")
        if (f.exists()) f.readText() else ""
    }
    val sections = remember(transcript) { listOf(transcript) }

    LaunchedEffect(playing) {
        while (playing) {
            pos = player?.currentPosition ?: 0
            delay(500)
        }
    }

    LaunchedEffect(sleepLeft) {
        if (sleepLeft > 0) {
            delay(60000L)
            if (sleepLeft <= 1) {
                player?.pause()
                playing = false
                savePos()
                sleepLeft = 0
            } else {
                sleepLeft = sleepLeft - 1
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            savePos()
            player?.release()
        }
    }

    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("OpenAudioBookify", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("write text here") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { generateTTS() },
                enabled = !isGenerating && inputText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isGenerating) "generating..." else "Make Sound")
            }
            if (ttsMessage.isNotEmpty()) {
                Text(ttsMessage, color = Color(0xFFBB86FC), fontSize = 14.sp)
            }
            Spacer(Modifier.height(12.dp))

            Text("or pick an audio file:", color = Color(0xFFBB86FC))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Pick") }
                Button(onClick = { skip(-15000) }) { Text("-15") }
                Button(onClick = { skip(15000) }) { Text("+15") }
            }
            Spacer(Modifier.height(6.dp))
            Slider(
                value = pos.toFloat().coerceIn(0f, dur.toFloat()),
                onValueChange = { v -> player?.seekTo(v.toInt()); pos = v.toInt() },
                valueRange = 0f..dur.toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(onClick = {
                    val p = player
                    if (p != null) {
                        if (playing) { p.pause(); savePos() } else { p.start() }
                        playing = !playing
                    }
                }) { Text(if (playing) "Pause" else "Play", fontSize = 18.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Text("speed: $speed", color = Color(0xFFBB86FC))
            Slider(value = speed, onValueChange = { setSpeed(it) }, valueRange = 0.5f..2f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { sleepLeft = 15 }) { Text("15m") }
                Button(onClick = { sleepLeft = 30 }) { Text("30m") }
                Button(onClick = { sleepLeft = 60 }) { Text("60m") }
                Button(onClick = { sleepLeft = 0 }) { Text("off") }
            }
            if (sleepLeft > 0) Text("sleep: $sleepLeft min", color = Color(0xFF7CFC9A))
            Spacer(Modifier.height(6.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(sections.size) { i ->
                    Text(
                        sections[i],
                        color = Color.LightGray,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth()
                    )
                }
            }
        }
    }
}
