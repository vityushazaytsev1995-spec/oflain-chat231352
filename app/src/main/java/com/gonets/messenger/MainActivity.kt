package com.gonets.messenger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.gonets.messenger.model.ConnectionRequest
import com.gonets.messenger.model.MeshPacket
import com.gonets.messenger.model.MessageType
import com.gonets.messenger.service.MeshForegroundService
import com.gonets.messenger.service.MeshService
import com.gonets.messenger.util.FileUtils
import com.gonets.messenger.util.QrUtils
import java.io.File
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, MeshForegroundService::class.java))
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { GonetsApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GonetsApp() {
    val context = LocalContext.current
    var myId by remember { mutableStateOf("") }
    var myName by remember { mutableStateOf("") }
    var isNameLocked by remember { mutableStateOf(false) }
    var meshService by remember { mutableStateOf<MeshService?>(null) }
    var hasPermissions by remember { mutableStateOf(false) }

    var showQrDialog by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showSetNameDialog by remember { mutableStateOf(false) }
    var tempName by remember { mutableStateOf("") }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var fullImageFile by remember { mutableStateOf<File?>(null) }

    val requiredPerms = if (Build.VERSION.SDK_INT >= 33) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA
    ) else arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    fun checkPerms() = requiredPerms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermissions = result.values.all { it }
        if (!hasPermissions) showPermissionRationale = true
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content != null) {
            val parsed = QrUtils.parseQrContent(content)
            if (parsed != null) {
                val (deviceId, name) = parsed
                Toast.makeText(context, "QR: найден $name", Toast.LENGTH_SHORT).show()
                meshService?.connectByDeviceId(deviceId)
            } else {
                Toast.makeText(context, "Неверный QR", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        val sp = context.getSharedPreferences("gonets_final", 0)
        myId = sp.getString("myId", null) ?: UUID.randomUUID().toString().take(6).uppercase().also {
            sp.edit().putString("myId", it).apply()
        }
        val savedName = sp.getString("myName", null)
        if (savedName == null) showSetNameDialog = true else { myName = savedName; isNameLocked = true }
        if (checkPerms()) hasPermissions = true else permLauncher.launch(requiredPerms)
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null && !btAdapter.isEnabled) {
            Toast.makeText(context, "Включите Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(hasPermissions, myName, isNameLocked) {
        if (hasPermissions && myName.isNotBlank() && isNameLocked && meshService == null) {
            val s = MeshService(context, myId, myName); s.start(); meshService = s
        }
    }

    val messages by meshService?.messagesFlow?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val peers by meshService?.peersFlow?.collectAsState() ?: remember { mutableStateOf(emptyMap()) }
    val incomingRequests by meshService?.incomingRequestsFlow?.collectAsState() ?: remember { mutableStateOf(emptyMap()) }
    val logs by meshService?.logsFlow?.collectAsState() ?: remember { mutableStateOf("") }

    var text by remember { mutableStateOf("") }
    var showPeers by remember { mutableStateOf(true) }
    var showLogs by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    if (showSetNameDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Добро пожаловать в Гонец") },
            text = {
                Column {
                    Text("Придумайте постоянный ник. Он будет виден всем рядом и его нельзя изменить.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = tempName, onValueChange = { tempName = it }, label = { Text("Ваш ник") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Ваш постоянный ID: $myId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                Button(enabled = tempName.length >= 2, onClick = {
                    val sp = context.getSharedPreferences("gonets_final", 0)
                    sp.edit().putString("myName", tempName).apply()
                    myName = tempName; isNameLocked = true; showSetNameDialog = false
                }) { Text("Сохранить") }
            }
        )
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Нужны разрешения") },
            text = { Text("Для поиска контактов нужны Bluetooth, Wi-Fi, Камера и Микрофон. Без них Гонец не найдет никого и QR не заработает.") },
            confirmButton = { Button(onClick = { showPermissionRationale = false; permLauncher.launch(requiredPerms) }) { Text("Разрешить") } }
        )
    }

    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("Мой QR-код") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Покажите этот код другому человеку. Он отсканирует и подключится к вам.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    val content = QrUtils.createQrContent(myId, myName)
                    LaunchedEffect(content) { qrBitmap = QrUtils.generateQrBitmap(content, 700) }
                    qrBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(260.dp)) } ?: CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(myName, style = MaterialTheme.typography.titleMedium)
                    Text("ID: $myId", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { Button(onClick = { showQrDialog = false }) { Text("Закрыть") } }
        )
    }

    fullImageFile?.let { file ->
        AlertDialog(
            onDismissRequest = { fullImageFile = null },
            title = { Text(file.name) },
            text = {
                val bmp = remember(file) {
                    try {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeFile(file.absolutePath, opts)
                    } catch (e: Exception) { null }
                }
                bmp?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(400.dp), contentScale = ContentScale.Fit) } ?: Text("Не удалось загрузить")
            },
            confirmButton = { Button(onClick = { fullImageFile = null }) { Text("Закрыть") } }
        )
    }

    incomingRequests.values.forEach { req ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Запрос на подключение") },
            text = { Text("${req.name} [${req.deviceId}] хочет подключиться к вам. Подтвердите, чтобы начать переписку без интернета.") },
            confirmButton = { Button(onClick = { meshService?.acceptConnectionRequest(req.endpointId) }) { Text("Принять") } },
            dismissButton = { OutlinedButton(onClick = { meshService?.rejectConnectionRequest(req.endpointId) }) { Text("Отклонить") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Гонец: $myName", style = MaterialTheme.typography.titleMedium); Text("ID: $myId • ${peers.values.count { it.isConnected }} в сети", style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    TextButton(onClick = { showQrDialog = true }) { Text("Мой QR") }
                    TextButton(onClick = {
                        if (!checkPerms()) permLauncher.launch(requiredPerms)
                        else qrScannerLauncher.launch(ScanOptions().apply { setDesiredBarcodeFormats(ScanOptions.QR_CODE); setPrompt("Наведите на QR Гонца"); setBeepEnabled(true) })
                    }) { Text("Скан") }
                    TextButton(onClick = { showPeers = !showPeers }) { Text(if (showPeers) "Скрыть" else "Контакты") }
                }
            )
        },
        bottomBar = {
            BottomInput(text, { text = it }, { if (text.isNotBlank()) { meshService?.sendText(text); text = "" } }, meshService)
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!hasPermissions) {
                Card(Modifier.padding(8.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⚠ Нет разрешений", style = MaterialTheme.typography.titleSmall)
                        Text("Дайте разрешения на Bluetooth, Wi-Fi, Камеру, Микрофон — иначе контакты не найдутся.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { permLauncher.launch(requiredPerms) }, modifier = Modifier.padding(top = 8.dp)) { Text("Дать разрешения") }
                    }
                }
            }
            if (showLogs) {
                Card(Modifier.padding(8.dp).fillMaxWidth().height(120.dp)) {
                    LazyColumn(Modifier.padding(8.dp)) { item { Text(logs, style = MaterialTheme.typography.labelSmall) } }
                }
            }
            if (showPeers) {
                Card(Modifier.padding(8.dp).fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Контакты поблизости:", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { meshService?.let { it.stop(); val s = MeshService(context, myId, myName); s.start(); meshService = s } }) { Text("Обновить") }
                        }
                        if (peers.isEmpty()) {
                            Text("Ищу... Включите Bluetooth и Wi-Fi, откройте Гонец на втором телефоне рядом (до 50м). Убедитесь что дали все разрешения.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            peers.values.forEach { peer ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${if (peer.isConnected) "🟢" else "⚪"} ${peer.name}", style = MaterialTheme.typography.bodyMedium)
                                        Text("ID: ${peer.deviceId} ${if (peer.isConnected) "• подключен" else "• найден"}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (!peer.isConnected) Button(onClick = { meshService?.requestConnectionTo(peer.endpointId) }, modifier = Modifier.height(36.dp)) { Text("Подключиться") } else Text("✓ Подключен", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                }
                                Divider()
                            }
                        }
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(8.dp)) {
                items(messages) { packet -> MessageBubble(packet, packet.originId == myId, onImageClick = { fullImageFile = it }) }
            }
        }
    }
}

@Composable
fun MessageBubble(packet: MeshPacket, isMe: Boolean, onImageClick: (File) -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.padding(4.dp).fillMaxWidth(0.85f).wrapContentWidth(if (isMe) Alignment.End else Alignment.Start), colors = CardDefaults.cardColors(containerColor = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(10.dp)) {
            Text("${packet.originName} • ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(packet.timestamp))}", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            when (packet.type) {
                MessageType.TEXT.name -> Text(packet.text ?: "", style = MaterialTheme.typography.bodyMedium)
                MessageType.IMAGE.name -> {
                    val file = File(context.filesDir, "mesh_files/${packet.fileName}")
                    if (file.exists()) {
                        val bmp = remember(file) {
                            try {
                                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                BitmapFactory.decodeFile(file.absolutePath, opts)
                            } catch (e: Exception) { null }
                        }
                        if (bmp != null) {
                            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.width(200.dp).height(200.dp).clip(RoundedCornerShape(12.dp)).clickable { onImageClick(file) }, contentScale = ContentScale.Crop)
                            Spacer(Modifier.height(4.dp))
                            Text(packet.fileName ?: "", style = MaterialTheme.typography.labelSmall)
                        } else {
                            Text("📷 ${packet.fileName} (ошибка загрузки)", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("📷 Загружается ${packet.fileName}...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                MessageType.FILE.name -> {
                    val file = File(context.filesDir, "mesh_files/${packet.fileName}")
                    Column {
                        Text("📄 ${packet.fileName}", style = MaterialTheme.typography.bodyMedium)
                        Text("${(packet.fileSize ?: 0) / 1024} КБ ${if (file.exists()) "• сохранен" else "• загрузка"}", style = MaterialTheme.typography.labelSmall)
                        if (file.exists()) {
                            Button(onClick = { try { val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.fromFile(file), packet.mimeType ?: "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; context.startActivity(intent) } catch (_: Exception) {} }, modifier = Modifier.padding(top = 4.dp).height(32.dp)) { Text("Открыть", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                MessageType.VOICE.name -> {
                    val file = File(context.filesDir, "mesh_files/${packet.fileName}")
                    if (file.exists()) VoicePlayer(file, packet.durationMs) else Text("🎤 Загружается...")
                }
            }
        }
    }
}

@Composable
fun VoicePlayer(file: File, durationMs: Long?) {
    var isPlaying by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var current by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(durationMs?.toInt() ?: 0) }

    DisposableEffect(file) {
        onDispose { try { player?.release() } catch (_: Exception) {} }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            kotlinx.coroutines.delay(200)
            player?.let { current = it.currentPosition }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp)).padding(8.dp)) {
        Button(onClick = {
            if (isPlaying) {
                try { player?.pause(); isPlaying = false } catch (_: Exception) {}
            } else {
                try {
                    if (player == null) {
                        player = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            prepare()
                            total = duration
                            setOnCompletionListener { isPlaying = false; current = 0 }
                        }
                    }
                    player?.start(); isPlaying = true
                } catch (e: Exception) {}
            }
        }, modifier = Modifier.size(36.dp), contentPadding = PaddingValues(0.dp)) {
            Text(if (isPlaying) "⏸" else "▶", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Голосовое ${total / 1000}с", style = MaterialTheme.typography.labelSmall)
            if (total > 0) {
                LinearProgressIndicator(progress = if (total > 0) current.toFloat() / total else 0f, modifier = Modifier.width(100.dp).height(4.dp))
                Text("${current / 1000}с / ${total / 1000}с", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun BottomInput(text: String, onText: (String) -> Unit, onSend: () -> Unit, meshService: MeshService?) {
    val context = LocalContext.current
    var isRec by remember { mutableStateOf(false) }
    var recorder: MediaRecorder? by remember { mutableStateOf(null) }
    var recFile: File? by remember { mutableStateOf(null) }
    var recStart by remember { mutableStateOf(0L) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val f = FileUtils.uriToFile(context, it) ?: return@let
            val mime = FileUtils.getMimeType(context, it)
            val type = if (mime.startsWith("image/")) MessageType.IMAGE else MessageType.FILE
            meshService?.sendFile(f, mime, type)
        }
    }
    Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { picker.launch("*/*") }) { Text("Файл") }
        TextButton(onClick = { picker.launch("image/*") }) { Text("Фото") }
        TextButton(onClick = {
            if (!isRec) {
                try {
                    val f = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                    recFile = f
                    recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                    recorder?.apply { setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setOutputFile(f.absolutePath); prepare(); start() }
                    recStart = System.currentTimeMillis(); isRec = true
                } catch (_: Exception) {}
            } else {
                try { recorder?.stop(); recorder?.release(); recFile?.let { meshService?.sendFile(it, "audio/mp4", MessageType.VOICE, durationMs = System.currentTimeMillis() - recStart) } } catch (_: Exception) {}
                isRec = false
            }
        }) { Text(if (isRec) "Стоп" else "Голос", color = if (isRec) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        OutlinedTextField(value = text, onValueChange = onText, modifier = Modifier.weight(1f), placeholder = { Text("Сообщение...") })
        Button(onClick = onSend, modifier = Modifier.padding(start = 4.dp)) { Text("Отпр.") }
    }
}
