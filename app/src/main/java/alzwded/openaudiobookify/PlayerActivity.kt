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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Button
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.delay

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlayerScreen() }
    }
}

@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("player", 0) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var currentUri by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableStateOf(0) }
    var dur by remember { mutableStateOf(1) }
    var speed by remember { mutableStateOf(1.0f) }
    var sleepLeft by remember { mutableStateOf(0) }
    val savePos: () -> Unit = {
        val p = player
        val u = currentUri
        if (p != null && u != null) {
            prefs.edit().putString("pos_" + u, p.currentPosition.toString()).apply()
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
    val sections = remember(transcript) { transcript.split("【بخش ") }
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
    val currentIdx = ((pos.toFloat() / dur) * sections.size).toInt().coerceIn(0, (sections.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState()
    LaunchedEffect(currentIdx) {
        if (sections.size > 1) listState.animateScrollToItem(currentIdx)
    }
    val jump: (Int) -> Unit = { i ->
        val p = player
        if (p != null && sections.size > 0) {
            val target = (i.toFloat() / sections.size * p.duration).toInt().coerceIn(0, p.duration.coerceAtLeast(0))
            p.seekTo(target)
            pos = target
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("🎧 پخش‌کننده کتاب صوتی", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("انتخاب کتاب") }
                Button(onClick = { skip(-15000) }) { Text("⏪ ۵") }
                Button(onClick = { skip(15000) }) { Text("۱۵ ⏩") }
            }
            Spacer(Modifier.height(6.dp))
            Slider(value = pos.toFloat().coerceIn(0f, dur.toFloat()), onValueChange = { v -> player?.seekTo(v.toInt()); pos = v.toInt() }, valueRange = 0f..dur.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(onClick = {
                    val p = player ?: return@Button
                    if (playing) { p.pause(); savePos() } else { p.start() }
                    playing = !playing
                }) { Text(if (playing) "⏸ توقف" else "▶ پخش", fontSize = 18.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Text("سرعت پخش: " + speed.toString() + "x", color = Color(0xFFBB86FC))
            Slider(value = speed, onValueChange = { setSpeed(it) }, valueRange = 0.5f..2f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { sleepLeft = 15 }) { Text("خواب ۱۵") }
                Button(onClick = { sleepLeft = 30 }) { Text("۳۰") }
                Button(onClick = { sleepLeft = 60 }) { Text("۶۰") }
                Button(onClick = { sleepLeft = 0 }) { Text("خاموش") }
            }
            if (sleepLeft > 0) Text("⏰ تایمر خواب: " + sleepLeft + " دقیقه", color = Color(0xFF7CFC9A))
            Spacer(Modifier.height(6.dp))
            Text("متن هم‌زمان (برای پرش، روی بخش بزن):", color = Color.White, fontWeight = FontWeight.Bold)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(sections.size) { i ->
                    val body = sections[i]
                    val label = if (i == 0) "" else "【بخش " + i + "】\n"
                    Text(
                        label + body,
                        color = if (i == currentIdx) Color(0xFF7CFC9A) else Color.LightGray,
                        fontSize = if (i == currentIdx) 17.sp else 15.sp,
                        fontWeight = if (i == currentIdx) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth().clickable { jump(i) }
                    )
                }
            }
        }
    }
}
