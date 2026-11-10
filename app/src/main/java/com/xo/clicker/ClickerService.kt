package com.xo.clicker

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

class ClickerService : AccessibilityService() {
    private val dir = File("/storage/emulated/0/xo")
    private val h = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { log("error: $e") }
            h.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() { dir.mkdirs(); h.post(poll) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { h.removeCallbacks(poll); super.onDestroy() }

    private fun log(s: String) {
        try { File(dir, "log.txt").appendText(s + "\n") } catch (_: Exception) {}
    }

    private fun tick() {
        val job = File(dir, "job.txt")
        if (!job.exists()) return
        val cmd = job.readText().trim()
        job.delete()
        if (cmd == "dump") File(dir, "dump.txt").writeText(dump())
    }

    private fun dump(): String {
        val root = rootInActiveWindow ?: return "no Chrome window (open Chrome first)"
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            sb.append("  ".repeat(d)).append(n.className)
                .append(" | text=").append(n.text)
                .append(" | desc=").append(n.contentDescription)
                .append(" | id=").append(n.viewIdResourceName)
                .append(" | click=").append(n.isClickable)
                .append(" | edit=").append(n.isEditable).append("\n")
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, d + 1)
            }
        }
        walk(root, 0)
        return sb.toString()
    }
}
