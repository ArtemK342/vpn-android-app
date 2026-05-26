package org.amnezia.vpn.util

import android.content.Context
import android.util.Log as NativeLog

object Log {
    enum class Priority(val level: Int) {
        V(NativeLog.VERBOSE), D(NativeLog.DEBUG), I(NativeLog.INFO),
        W(NativeLog.WARN), E(NativeLog.ERROR), F(NativeLog.ASSERT)
    }

    @JvmStatic fun v(tag: String, msg: String) = NativeLog.v(tag, msg)
    @JvmStatic fun d(tag: String, msg: String) = NativeLog.d(tag, msg)
    @JvmStatic fun i(tag: String, msg: String) = NativeLog.i(tag, msg)
    @JvmStatic fun w(tag: String, msg: String) = NativeLog.w(tag, msg)
    @JvmStatic fun e(tag: String, msg: String) = NativeLog.e(tag, msg)
    @JvmStatic fun f(tag: String, msg: String) = NativeLog.wtf(tag, msg)

    fun v(tag: String, msg: Any?) = v(tag, msg.toString())
    fun d(tag: String, msg: Any?) = d(tag, msg.toString())
    fun i(tag: String, msg: Any?) = i(tag, msg.toString())
    fun w(tag: String, msg: Any?) = w(tag, msg.toString())
    fun e(tag: String, msg: Any?) = e(tag, msg.toString())
    fun f(tag: String, msg: Any?) = f(tag, msg.toString())

    fun init(context: Context) {} // no-op, kept for API compatibility
}
