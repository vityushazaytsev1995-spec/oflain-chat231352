package com.gonets.messenger.model

import org.json.JSONObject

enum class MessageType { TEXT, IMAGE, FILE, VOICE }

data class MeshPacket(
    val id: String,
    val originId: String,
    val originName: String,
    val destId: String? = null,
    var ttl: Int = 7,
    val type: String,
    val text: String? = null,
    val timestamp: Long,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val filePayloadId: Long? = null,
    val durationMs: Long? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id); o.put("originId", originId); o.put("originName", originName)
        if (destId != null) o.put("destId", destId)
        o.put("ttl", ttl); o.put("type", type)
        if (text != null) o.put("text", text)
        o.put("timestamp", timestamp)
        if (fileName != null) o.put("fileName", fileName)
        if (fileSize != null) o.put("fileSize", fileSize)
        if (mimeType != null) o.put("mimeType", mimeType)
        if (filePayloadId != null) o.put("filePayloadId", filePayloadId)
        if (durationMs != null) o.put("durationMs", durationMs)
        return o.toString()
    }
    companion object {
        fun fromJson(s: String): MeshPacket {
            val o = JSONObject(s)
            return MeshPacket(
                id = o.getString("id"),
                originId = o.getString("originId"),
                originName = o.getString("originName"),
                destId = if (o.has("destId")) o.getString("destId") else null,
                ttl = o.optInt("ttl", 7),
                type = o.getString("type"),
                text = if (o.has("text")) o.getString("text") else null,
                timestamp = o.getLong("timestamp"),
                fileName = if (o.has("fileName")) o.getString("fileName") else null,
                fileSize = if (o.has("fileSize")) o.getLong("fileSize") else null,
                mimeType = if (o.has("mimeType")) o.getString("mimeType") else null,
                filePayloadId = if (o.has("filePayloadId")) o.getLong("filePayloadId") else null,
                durationMs = if (o.has("durationMs")) o.getLong("durationMs") else null
            )
        }
    }
}

data class Peer(val endpointId: String, val deviceId: String, val name: String, val isConnected: Boolean)
data class ConnectionRequest(val endpointId: String, val deviceId: String, val name: String)
