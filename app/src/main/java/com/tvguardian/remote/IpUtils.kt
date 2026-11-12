package com.tvguardian.remote

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.*

/** 获取设备在局域网内的 IP 地址 */
object IpUtils {

    fun getLocalIpAddress(context: Context): String? {
        // 优先从 WifiManager 拿（大多数电视是 WiFi）
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val ip = info.ipAddress
            if (ip != 0) {
                val host = InetAddress.getByName(
                    String.format(
                        Locale.CHINA, "%d.%d.%d.%d",
                        ip and 0xFF,
                        ip shr 8 and 0xFF,
                        ip shr 16 and 0xFF,
                        ip shr 24 and 0xFF
                    )
                )
                return host.hostAddress
            }
        } catch (_: Exception) {}

        // 回退：遍历所有网络接口
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") != true) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }
}
