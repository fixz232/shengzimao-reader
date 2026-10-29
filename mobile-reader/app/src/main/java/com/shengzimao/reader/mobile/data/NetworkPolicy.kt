package com.shengzimao.reader.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkPolicy {
    fun connected(context: Context): Boolean {
        val service = context.getSystemService(ConnectivityManager::class.java)
        val caps = service.getNetworkCapabilities(service.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    fun wifi(context: Context): Boolean {
        val service = context.getSystemService(ConnectivityManager::class.java)
        val caps = service.getNetworkCapabilities(service.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
