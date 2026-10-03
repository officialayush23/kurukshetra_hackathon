package com.bitchat.android.shell

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this phone has a working internet connection right now.
 *
 * "Validated" is the operative word: Android marks a network validated only after it has
 * actually reached the internet, so a Wi-Fi Aware link, a captive portal or a dead hotspot
 * does not count. The shell uses this to choose between the online app and the mesh.
 */
object NetworkMonitor {
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var registered = false
    private val validated = mutableSetOf<Network>()

    fun init(context: Context) {
        if (registered) return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        registered = true
        _online.value = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.isUsable() == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                synchronized(validated) {
                    if (caps.isUsable()) validated += network else validated -= network
                    _online.value = validated.isNotEmpty()
                }
            }

            override fun onLost(network: Network) {
                synchronized(validated) {
                    validated -= network
                    _online.value = validated.isNotEmpty()
                }
            }
        })
    }

    private fun NetworkCapabilities.isUsable(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
