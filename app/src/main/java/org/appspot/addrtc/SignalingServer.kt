package org.appspot.addrtc

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress

class SignalingServer(private var events: Events?, port: Int) : WebSocketServer(InetSocketAddress(port)) {
    interface Events {
        fun onOpen()
        fun onClose(code: Int, reason: String, remote: Boolean)
        fun onMessage(json: JSONObject)
        fun onError(ex: Exception)
    }

    fun updateEvents(events: Events?) {
        this.events = events
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "receive: ${conn.remoteSocketAddress}")
        events?.onOpen()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "receive: ${conn.remoteSocketAddress}")
        events?.onClose(code, reason, remote)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "receive: ${conn.remoteSocketAddress}")
        val json = stringToJson(message)
        if (json != null) {
            events?.onMessage(json)
        }
    }

    override fun onError(conn: WebSocket, ex: Exception) {
        Log.d(TAG, "receive: ${conn.remoteSocketAddress}")
        events?.onError(ex)
    }

    override fun onStart() {
        Log.d(TAG, "start server")
    }

    init {
        isReuseAddr = true
    }

    companion object {
        private const val TAG = "RTCClientServer"
        fun stringToJson(message: String) : JSONObject? {
            var json: JSONObject? = null
            try {
                json = JSONObject(message)
            } catch (e: JSONException) {
                e.printStackTrace()
            }
            return json
        }
    }
}