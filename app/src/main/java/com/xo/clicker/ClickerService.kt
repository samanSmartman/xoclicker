package com.xo.clicker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.File

class ClickerService : AccessibilityService() {
    private val dir = File("/storage/emulated/0/xo")
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private val CHROME = "com.android.chrome"
    private val START = "XOSTART_7"
    private val END = "XOEND_7"

    private val poll = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { log("tick error: $e") }
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
        if (busy) return
        val job = File(dir, "job.txt")
        if (!job.exists()) return
        val name = job.readText().trim()
        job.delete()
        busy = true
        Thread {
            try { runJob(name) }
            catch (e: Exception) { log("job error: $e"); fail("exception") }
            finally { busy = false }
        }.start()
    }

    private fun chromeRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow ?: return null
        return if (r.packageName?.toString() == CHROME) r else null
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out) }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val r = chromeRoot() ?: return emptyList()
        val l = ArrayList<AccessibilityNodeInfo>()
        collect(r, l)
        return l
    }

    private fun isUrlBar(n: AccessibilityNodeInfo) = n.viewIdResourceName?.endsWith("url_bar") == true

    private fun fail(why: String) {
        writeResult("XOERR: $why")
    }

    private fun writeResult(t: String) {
        val tmp = File(dir, "result.tmp")
        tmp.writeText(t)
        tmp.renameTo(File(dir, "result.txt"))
    }

    private fun loadCfg(name: String): JSONObject? {
        val f = File(dir, "jobs.json")
        if (!f.exists()) {
            f.writeText("""{
  "aistudio": {"url": "https://aistudio.google.com/prompts/new_chat", "send_text": "Run", "wait_ms": 5000},
  "gemini": {"url": "https://gemini.google.com/app", "send_desc": "Send message", "wait_ms": 5000}
}""")
        }
        return JSONObject(f.readText()).optJSONObject(name)
    }

    private fun waitFor(ms: Long, f: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) {
            val r = f()
            if (r != null) return r
            Thread.sleep(500)
        }
        return null
    }

    private fun clickUp(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        while (x != null) {
            if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = x.parent
        }
        return false
    }

    private fun runJob(name: String) {
        if (name == "dump") {
            val l = nodes()
            val sb = StringBuilder()
            if (l.isEmpty()) sb.append("active window is not Chrome (skipped, nothing read)")
            for (n in l) sb.append(n.className).append(" | text=").append(n.text)
                .append(" | desc=").append(n.contentDescription)
                .append(" | click=").append(n.isClickable)
                .append(" | edit=").append(n.isEditable).append("\n")
            File(dir, "dump.txt").writeText(sb.toString())
            return
        }
        val cfg = loadCfg(name)
        if (cfg == null) { fail("unknown job $name"); return }
        val promptFile = File(dir, "prompt.txt")
        if (!promptFile.exists()) { fail("no prompt.txt"); return }
        val prompt = promptFile.readText()
        val promptEnds = prompt.split(END).size - 1

        val i = Intent(Intent.ACTION_VIEW, Uri.parse(cfg.getString("url")))
            .setPackage(CHROME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        Thread.sleep(cfg.optLong("wait_ms", 5000))

        val input = waitFor(30000) {
            nodes().firstOrNull { it.isEditable && !isUrlBar(it) }
        }
        if (input == null) { fail("input box not found"); return }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Thread.sleep(1500)

        val sendText = cfg.optString("send_text", "")
        val sendDesc = cfg.optString("send_desc", "")
        val send = waitFor(15000) {
            nodes().firstOrNull {
                (sendText.isNotEmpty() && it.text?.toString() == sendText) ||
                (sendDesc.isNotEmpty() && it.contentDescription?.toString().equals(sendDesc, true))
            }
        }
        if (send == null) { fail("send button not found"); return }
        clickUp(send)

        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 180000) {
            Thread.sleep(2000)
            val sb = StringBuilder()
            for (n in nodes()) {
                if (n.isEditable) continue
                n.text?.let { sb.append(it).append("\n") }
            }
            val all = sb.toString()
            if (all.split(END).size - 1 >= promptEnds + 1) {
                val s = all.lastIndexOf(START)
                val e = all.indexOf(END, if (s >= 0) s else 0)
                if (s >= 0 && e > s) {
                    writeResult(all.substring(s, e + END.length))
                    return
                }
            }
        }
        fail("timeout")
    }
}
