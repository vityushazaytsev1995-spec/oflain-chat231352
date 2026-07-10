package com.gonets.messenger.service

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.gonets.messenger.model.ConnectionRequest
import com.gonets.messenger.model.MeshPacket
import com.gonets.messenger.model.MessageType
import com.gonets.messenger.model.Peer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MeshService(private val context: Context, val myDeviceId: String, val myName: String) {
    companion object {
        const val SERVICE_ID = "com.gonets.FINAL"
        val STRATEGY = Strategy.P2P_CLUSTER
    }
    private val client = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val peersFlow = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val messagesFlow = MutableStateFlow<List<MeshPacket>>(emptyList())
    val incomingRequestsFlow = MutableStateFlow<Map<String, ConnectionRequest>>(emptyMap())
    val logsFlow = MutableStateFlow("")
    private val connected = ConcurrentHashMap<String, Peer>()
    private val seenIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pendingPackets = Collections.synchronizedList(mutableListOf<MeshPacket>())
    private val pendingFileMeta = ConcurrentHashMap<Long, MeshPacket>()
    private val pendingFiles = ConcurrentHashMap<Long, File>()
    private val pendingQrTarget = MutableStateFlow<String?>(null)

    fun start() {
        log("Старт как $myName [$myDeviceId]")
        loadHistory()
        startAdvertising(); startDiscovery()
        scope.launch {
            while (true) {
                delay(10000)
                resendPending()
                if (peersFlow.value.isEmpty()) {
                    try { client.stopDiscovery(); client.stopAdvertising() } catch (_: Exception) {}
                    delay(1000); startAdvertising(); startDiscovery()
                }
            }
        }
    }
    fun stop() { try { client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery() } catch (_: Exception) {}; scope.cancel() }

    private fun markSeen(id: String): Boolean {
        if (seenIds.contains(id)) return false
        seenIds.add(id)
        if (seenIds.size > 2000) seenIds.remove(seenIds.first())
        return true
    }

    private fun startAdvertising() {
        val opts = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising("$myDeviceId|$myName", SERVICE_ID, lifecycleCallback, opts)
            .addOnSuccessListener { log("✓ Меня видно по Bluetooth и Wi-Fi") }
            .addOnFailureListener { log("✗ Ошибка видимости: ${it.message}") }
    }
    private fun startDiscovery() {
        val opts = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, opts)
            .addOnSuccessListener { log("✓ Ищу контакты рядом") }
            .addOnFailureListener { log("✗ Ошибка поиска: ${it.message}") }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val parts = info.endpointName.split("|", limit = 2)
            val devId = parts.getOrElse(0) { endpointId }
            val name = parts.getOrElse(1) { "Неизвестный" }
            if (devId == myDeviceId) return
            log("Найден: $name [$devId]")
            peersFlow.value = peersFlow.value + (endpointId to Peer(endpointId, devId, name, false))
            if (pendingQrTarget.value == devId) {
                pendingQrTarget.value = null
                requestConnectionTo(endpointId)
            }
        }
        override fun onEndpointLost(endpointId: String) {
            peersFlow.value[endpointId]?.let { if (!it.isConnected) peersFlow.value = peersFlow.value - endpointId }
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val parts = info.endpointName.split("|", limit = 2)
            val devId = parts.getOrElse(0) { endpointId }
            val name = parts.getOrElse(1) { info.endpointName }
            log("Запрос от $name - нужно подтверждение")
            incomingRequestsFlow.value = incomingRequestsFlow.value + (endpointId to ConnectionRequest(endpointId, devId, name))
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val existing = peersFlow.value[endpointId] ?: incomingRequestsFlow.value[endpointId]?.let { Peer(endpointId, it.deviceId, it.name, true) }
                val peer = Peer(endpointId, existing?.deviceId ?: endpointId, existing?.name ?: endpointId, true)
                connected[endpointId] = peer
                peersFlow.value = peersFlow.value + (endpointId to peer)
                incomingRequestsFlow.value = incomingRequestsFlow.value - endpointId
                log("Подключен: ${peer.name}")
                scope.launch { resendPending() }
            } else {
                incomingRequestsFlow.value = incomingRequestsFlow.value - endpointId
            }
        }
        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            incomingRequestsFlow.value = incomingRequestsFlow.value - endpointId
            peersFlow.value[endpointId]?.let { peersFlow.value = peersFlow.value + (endpointId to it.copy(isConnected = false)) }
        }
    }

    fun acceptConnectionRequest(endpointId: String) { client.acceptConnection(endpointId, payloadCallback) }
    fun rejectConnectionRequest(endpointId: String) { client.rejectConnection(endpointId); incomingRequestsFlow.value = incomingRequestsFlow.value - endpointId }
    fun requestConnectionTo(endpointId: String) {
        val peer = peersFlow.value[endpointId] ?: return
        client.requestConnection("$myDeviceId|$myName", endpointId, lifecycleCallback)
    }
    fun connectByDeviceId(deviceId: String) {
        val entry = peersFlow.value.entries.find { it.value.deviceId == deviceId }
        if (entry != null) requestConnectionTo(entry.key) else pendingQrTarget.value = deviceId
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    try {
                        val packet = MeshPacket.fromJson(String(payload.asBytes()!!))
                        handleIncoming(packet, endpointId)
                    } catch (e: Exception) { Log.e("Mesh", "parse fail", e) }
                }
                Payload.Type.FILE -> {
                    val file = payload.asFile()!!.asJavaFile()!!
                    val fileId = payload.id
                    val meta = pendingFileMeta.remove(fileId)
                    if (meta != null) {
                        completeFileReception(file, meta, endpointId)
                    } else {
                        // файл пришел раньше метаданных
                        pendingFiles[fileId] = file
                        log("Файл $fileId получен, жду метаданные...")
                    }
                }
                else -> {}
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun handleIncoming(packet: MeshPacket, fromEndpoint: String) {
        if (!markSeen(packet.id)) {
            Log.d("Mesh", "Duplicate ${packet.id}")
            return
        }

        if (packet.type == MessageType.TEXT.name) {
            val isForMe = packet.destId == null || packet.destId == myDeviceId
            if (isForMe) {
                messagesFlow.value = messagesFlow.value + packet
                saveHistory()
            }
            if (shouldForward(packet)) {
                forwardTextPacket(packet.copy(ttl = packet.ttl - 1), fromEndpoint)
            }
        } else {
            // Файл / Фото / Голосовое - ждем файл
            val fileId = packet.filePayloadId
            if (fileId != null) {
                val existingFile = pendingFiles.remove(fileId)
                if (existingFile != null) {
                    completeFileReception(existingFile, packet, fromEndpoint)
                } else {
                    pendingFileMeta[fileId] = packet
                    log("Метаданные файла ${packet.fileName} получены, жду файл...")
                    // Показываем в чате как загружается
                    messagesFlow.value = messagesFlow.value + packet
                    saveHistory()
                }
            }
        }
    }

    private fun completeFileReception(tempFile: File, meta: MeshPacket, fromEndpoint: String) {
        try {
            val dest = File(context.filesDir, "mesh_files/${meta.fileName}").also { it.parentFile?.mkdirs() }
            try { tempFile.renameTo(dest) } catch (_: Exception) { tempFile.copyTo(dest, true) }

            // Если сообщение уже было добавлено как "загружается", обновляем список чтобы триггернуть recompose
            val exists = messagesFlow.value.any { it.id == meta.id }
            if (!exists) {
                messagesFlow.value = messagesFlow.value + meta
            } else {
                // Пересоздаем список чтобы UI перерисовался и увидел что файл теперь существует
                messagesFlow.value = messagesFlow.value.toList()
            }
            saveHistory()
            log("✓ Файл получен: ${meta.fileName} ${(meta.fileSize ?: 0) / 1024}КБ")

            // Mesh forwarding для файлов
            if (shouldForward(meta) && meta.ttl > 1) {
                forwardFile(dest, meta.copy(ttl = meta.ttl - 1), fromEndpoint)
            }
        } catch (e: Exception) {
            Log.e("Mesh", "complete file fail", e)
        }
    }

    private fun shouldForward(p: MeshPacket): Boolean {
        if (p.ttl <= 0) return false
        if (p.originId == myDeviceId) return false
        if (p.destId != null && p.destId == myDeviceId) return false
        return true
    }

    private fun forwardTextPacket(packet: MeshPacket, exclude: String?) {
        if (connected.isEmpty()) { pendingPackets.add(packet); return }
        val payload = Payload.fromBytes(packet.toJson().toByteArray())
        connected.keys.forEach { if (it != exclude) client.sendPayload(it, payload) }
        log("Переслал текст ttl=${packet.ttl}")
    }

    private fun forwardFile(file: File, packet: MeshPacket, exclude: String?) {
        if (connected.size <= 1) return // некуда форвардить кроме отправителя
        try {
            val newFilePayload = Payload.fromFile(file)
            val newPacket = packet.copy(filePayloadId = newFilePayload.id, ttl = packet.ttl)
            val metaPayload = Payload.fromBytes(newPacket.toJson().toByteArray())
            connected.keys.forEach { epId ->
                if (epId != exclude) {
                    client.sendPayload(epId, newFilePayload)
                    client.sendPayload(epId, metaPayload)
                }
            }
            log("Переслал файл ${packet.fileName} ttl=${newPacket.ttl}")
        } catch (e: Exception) { Log.e("Mesh", "forward file fail", e) }
    }

    fun sendText(text: String) {
        val packet = MeshPacket(UUID.randomUUID().toString(), myDeviceId, myName, null, 7, MessageType.TEXT.name, text, System.currentTimeMillis())
        markSeen(packet.id); messagesFlow.value = messagesFlow.value + packet; saveHistory()
        val payload = Payload.fromBytes(packet.toJson().toByteArray())
        if (connected.isEmpty()) pendingPackets.add(packet) else connected.keys.forEach { client.sendPayload(it, payload) }
    }

    fun sendFile(file: File, mime: String, type: MessageType, durationMs: Long? = null) {
        try {
            val id = UUID.randomUUID().toString()
            val filePayload = Payload.fromFile(file)
            val packet = MeshPacket(id, myDeviceId, myName, null, 7, type.name, null, System.currentTimeMillis(), file.name, file.length(), mime, filePayload.id, durationMs)
            markSeen(id)
            val saved = File(context.filesDir, "mesh_files/${file.name}").also { it.parentFile?.mkdirs() }
            file.copyTo(saved, overwrite = true)
            messagesFlow.value = messagesFlow.value + packet; saveHistory()
            val meta = Payload.fromBytes(packet.toJson().toByteArray())
            if (connected.isEmpty()) {
                pendingPackets.add(packet)
                log("Нет подключений, файл в очереди")
            } else {
                connected.keys.forEach {
                    client.sendPayload(it, filePayload)
                    client.sendPayload(it, meta)
                }
                log("Отправляю файл ${file.name}")
            }
        } catch (e: Exception) { Log.e("Mesh", "sendFile fail", e) }
    }

    private fun saveHistory() {
        try {
            val sp = context.getSharedPreferences("gonets_history", 0)
            val jsonList = messagesFlow.value.takeLast(500).joinToString("\n") { it.toJson() }
            sp.edit().putString("packets", jsonList).apply()
        } catch (_: Exception) {}
    }
    private fun loadHistory() {
        try {
            val sp = context.getSharedPreferences("gonets_history", 0)
            val data = sp.getString("packets", "") ?: ""
            if (data.isNotEmpty()) {
                val packets = data.split("\n").mapNotNull { try { MeshPacket.fromJson(it) } catch (_: Exception) { null } }
                messagesFlow.value = packets
                seenIds.addAll(packets.map { it.id })
            }
        } catch (_: Exception) {}
    }
    private fun resendPending() {
        if (pendingPackets.isEmpty() || connected.isEmpty()) return
        val toSend = pendingPackets.toList(); pendingPackets.clear()
        toSend.forEach { if (it.ttl > 0) forwardTextPacket(it, null) }
    }
    private fun log(s: String) {
        logsFlow.value = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} $s\n${logsFlow.value}"
    }
}
