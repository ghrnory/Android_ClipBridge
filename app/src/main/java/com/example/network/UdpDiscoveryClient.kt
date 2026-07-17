package com.example.network

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object UdpDiscoveryClient {
    private const val TAG = "UdpDiscoveryClient"
    private const val DISCOVERY_PORT = 19002
    private const val DISCOVERY_REQUEST = "CLIPBOARD_SYNC_DISCOVER"
    private const val SERVER_INFO_PREFIX = "CLIPBOARD_SYNC_SERVER_INFO|"

    data class ServerInfo(val ipAddress: String, val name: String, val port: Int)

    suspend fun discoverServer(context: Context): ServerInfo? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            val broadcastAddress = getBroadcastAddress(context) ?: InetAddress.getByName("255.255.255.255")
            Log.d(TAG, "Broadcasting discovery to $broadcastAddress:$DISCOVERY_PORT")

            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 2500 // 2.5 seconds timeout
            }

            val sendData = DISCOVERY_REQUEST.toByteArray()
            val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddress, DISCOVERY_PORT)
            socket.send(sendPacket)

            // Buffer for incoming reply
            val recvBuf = ByteArray(1024)
            val receivePacket = DatagramPacket(recvBuf, recvBuf.size)

            Log.d(TAG, "Waiting for server response...")
            socket.receive(receivePacket)

            val message = String(receivePacket.data, 0, receivePacket.length).trim()
            val serverIp = receivePacket.address.hostAddress ?: ""
            Log.d(TAG, "Received UDP response from $serverIp: $message")

            if (message.startsWith(SERVER_INFO_PREFIX)) {
                val parts = message.removePrefix(SERVER_INFO_PREFIX).split("|")
                if (parts.size >= 2) {
                    val serverName = parts[0]
                    val port = parts[1].toIntOrNull() ?: 19001
                    Log.i(TAG, "Discovered server: $serverName at $serverIp:$port")
                    return@withContext ServerInfo(
                        ipAddress = serverIp,
                        name = serverName,
                        port = port
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Discovery timed out")
        } catch (e: Exception) {
            Log.e(TAG, "Error during discovery", e)
        } finally {
            socket?.close()
        }
        return@withContext null
    }

    private fun getBroadcastAddress(context: Context): InetAddress? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val dhcp: DhcpInfo = wifiManager.dhcpInfo ?: return null
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val quads = ByteArray(4)
        for (k in 0..3) {
            quads[k] = ((broadcast shr (k * 8)) and 0xFF).toByte()
        }
        return try {
            InetAddress.getByAddress(quads)
        } catch (e: Exception) {
            null
        }
    }
}
