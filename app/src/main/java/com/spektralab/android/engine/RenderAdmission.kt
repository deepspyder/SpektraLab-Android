package com.spektralab.android.engine

import android.app.ActivityManager
import android.content.Context

/** Actual system pressure gate, not a claim that available RAM equals Vulkan capacity. */
internal object RenderAdmission {
    data class Memory(val available: Long, val threshold: Long, val low: Boolean)

    fun reason(memory: Memory): String? =
        if (memory.low || memory.available <= memory.threshold)
            "系统内存紧张，暂未开始全尺寸处理。请关闭其他应用后重试，原图尺寸和画质设置已保留。"
        else null

    fun check(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val state = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        reason(Memory(state.availMem, state.threshold, state.lowMemory))?.let {
            throw SpkException(it)
        }
    }
}
