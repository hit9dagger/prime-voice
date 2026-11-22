package cz.primevoice.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import cz.primevoice.core.DeviceResources

class DeviceCapabilities(private val context: Context) {
    private val manager = context.getSystemService(ActivityManager::class.java)
    @Volatile var engineLinked = false
    val eligibleAbi get() = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
    fun snapshot(): DeviceResources {
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return DeviceResources(memory.totalMem, memory.availMem, StatFs(context.filesDir.path).availableBytes,
            manager.isLowRamDevice, memory.lowMemory, eligibleAbi && engineLinked)
    }
}
