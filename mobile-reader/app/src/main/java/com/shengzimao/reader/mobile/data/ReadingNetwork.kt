package com.shengzimao.reader.mobile.data

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager

object ReadingNetwork {
    fun prefetchCount(context: Context, intervalMs: Long, loadMs: Long, max: Int, wifiOnly: Boolean = true): Int {
        val network = context.getSystemService(ConnectivityManager::class.java)
        if (network.activeNetwork == null) return 0
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val headroom = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        return AdaptivePrefetch.count(network.activeNetwork != null && !network.isActiveNetworkMetered,
            memory.lowMemory, headroom, intervalMs, loadMs, max, wifiOnly)
    }
}
