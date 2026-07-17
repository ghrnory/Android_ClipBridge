package com.example.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class ClipboardSocketClient(
    private val serverIp: String,
    private val port: Int,
    private val callback: Callback
) {
    interface Callback {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessageReceived(text: String)
    }

    private companion object {
        const val TAG = "ClipboardSocketClient"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep connection open
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var tryTrailingSlash = false

    fun connect() {
        if (isConnected) return

        val path = if (tryTrailingSlash) "clipboard/" else "clipboard"
        val url = "ws://$serverIp:$port/$path"
        Log.d(TAG, "Connecting to WebSocket URL: $url")
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket Connected successfully to $url")
                isConnected = true
                tryTrailingSlash = false // Reset fallback flag
                callback.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                callback.onMessageReceived(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket Closed: $code / $reason")
                isConnected = false
                callback.onDisconnected(reason.ifEmpty { "Connection Closed" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure on $url: ${t.message}", t)
                isConnected = false
                
                if (!tryTrailingSlash) {
                    Log.i(TAG, "Connection failed. Retrying with trailing slash...")
                    tryTrailingSlash = true
                    webSocket?.close(1001, "Retrying with trailing slash")
                    connect()
                } else {
                    tryTrailingSlash = false // Reset fallback flag
                    callback.onDisconnected(t.message ?: "Connection Failure")
                }
            }
        })
    }

    fun sendClipboardText(text: String): Boolean {
        val ws = webSocket
        if (ws != null && isConnected) {
            Log.d(TAG, "Sending text to server: $text")
            return ws.send(text)
        }
        Log.w(TAG, "Cannot send, socket not connected")
        return false
    }

    fun disconnect() {
        webSocket?.close(1000, "App disconnect")
        webSocket = null
        isConnected = false
    }
}
