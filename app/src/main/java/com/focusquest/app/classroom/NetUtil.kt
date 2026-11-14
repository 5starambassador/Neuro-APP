package com.focusquest.app.classroom

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** IPv4 address of this device on the current (Wi‑Fi) network, for manual joining. */
fun localIpv4(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}
