package com.xo.clicker

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
            h.postDelayed(this, 200)
        }
    }

    override fun onServiceConnected() { dir.mkdirs(); h.post(poll) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { h.removeCallbacks(poll); super.onDestroy() }

    @Volatile private var jobStart = 0L

    private fun backToTelegram() {
        for (pkg in listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")) {
            try {
                val back = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(back)
                log("back to $pkg")
                return
            } catch (e: Exception) {
                // this Telegram app is not installed, try the next one
            }
        }
    }

    private fun cancelled(): Boolean = File(dir, "cancel.txt").exists()

    private fun sleepC(ms: Long): Boolean {
        val t = System.currentTimeMillis()
        while (System.currentTimeMillis() - t < ms) {
            if (cancelled()) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun log(s: String) {
        val sec = if (jobStart > 0) String.format("%.1f", (System.currentTimeMillis() - jobStart) / 1000.0) else "-"
        try { File(dir, "log.txt").appendText("[" + sec + "s] " + s + "\n") } catch (_: Exception) {}
    }

    private fun tick() {
        if (busy) return
        val job = File(dir, "job.txt")
        if (!job.exists()) return
        val name = job.readText().trim()
        job.delete()
        File(dir, "cancel.txt").delete()
        jobStart = System.currentTimeMillis()
        log("job started: $name")
        busy = true
        Thread {
            try { runJob(name) }
            catch (e: Exception) { log("job error: $e"); fail("exception") }
            finally { backToTelegram(); busy = false }
        }.start()
    }

    private fun chromeRoot(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == CHROME) return r
            }
        } catch (e: Exception) {}
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
        if (cancelled()) { log("cancelled (error skipped: $why)"); return }
        writeResult("XOERR: $why")
    }

    private fun writeResult(t: String) {
        if (cancelled()) return
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
            if (cancelled()) return null
            val r = f()
            if (r != null) return r
            Thread.sleep(150)
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

    private fun runJob(name: String, retry: Int = 0) {
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
        val START = cfg.optString("start", "XOSTART_7")
        val END = cfg.optString("end", "XOEND_7")
        val promptEnds = prompt.split(END).size - 1

        val i = Intent(Intent.ACTION_VIEW, Uri.parse(cfg.getString("url")))
            .setPackage(CHROME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        log("chrome opened, waiting wait_ms")
        if (sleepC(cfg.optLong("wait_ms", 5000))) { log("cancelled"); return }
        log("wait_ms done")

        val sendText = cfg.optString("send_text", "")
        val sendDesc = cfg.optString("send_desc", "")
        fun findSend() = nodes().firstOrNull {
            (sendText.isNotEmpty() && it.text?.toString() == sendText) ||
            (sendDesc.isNotEmpty() && it.contentDescription?.toString().equals(sendDesc, true))
        }
        fun findInput() = nodes().firstOrNull { it.isEditable && !isUrlBar(it) }
        var send: AccessibilityNodeInfo? = null
        var lastInput: AccessibilityNodeInfo? = null
        for (attempt in 1..4) {
            if (cancelled()) { log("cancelled"); return }
            var lastLaunch = System.currentTimeMillis()
            val input = waitFor(30000) {
                val found = findInput()
                if (found == null && System.currentTimeMillis() - lastLaunch > 4000) {
                    lastLaunch = System.currentTimeMillis()
                    if (chromeRoot() == null) {
                        log("chrome not in front, reopening")
                        startActivity(i)
                    }
                }
                found
            }
            if (input == null) { fail("input box not found"); return }
            log("attempt $attempt: input found")
            lastInput = input
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(200)
            if (attempt % 2 == 1) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("xo", prompt))
                input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            log("attempt $attempt: text set (prompt " + prompt.length + " chars)")
            Thread.sleep(400)
            send = waitFor(5000) { findSend() }
            if (send != null) break
            log("attempt $attempt: no send button")
        }
        // stale-result guard
        fun lastBlock(t: String): String {
            val x = t.lastIndexOf(START)
            if (x < 0) return ""
            val y = t.indexOf(END, x)
            return if (y > x) t.substring(x, y + END.length) else ""
        }
        val sb0 = StringBuilder()
        for (n in nodes()) { if (n.isEditable) continue; n.text?.let { sb0.append(it).append("\n") } }
        val baseAll = sb0.toString()
        val baseEnds = baseAll.split(END).size - 1
        val baseBlock = lastBlock(baseAll)
        var lastScroll = 0L
        log("baseline: ends=" + baseEnds)
        val li = lastInput
        if (send != null) {
            val head = prompt.trim().take(30)
            var sent = false
            for (k in 1..6) {
                val sb2 = findSend()
                var ok = false
                if (sb2 != null && sb2.isEnabled) ok = clickUp(sb2)
                log("send try " + k + ": found=" + (sb2 != null) + " enabled=" + (sb2?.isEnabled == true) + " click=" + ok)
                if (sleepC(1500)) { log("cancelled"); return }
                val inp2 = findInput()
                val still = inp2?.text?.toString()?.contains(head) == true
                if (!still) { sent = true; break }
            }
            log(if (sent) "send confirmed" else "send not confirmed")
        } else if (Build.VERSION.SDK_INT >= 30 && li != null &&
            li.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            log("sent via IME enter")
        } else {
            fail("send button not found"); return
        }

        var errTries = 0
        var lastErr = 0L
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 180000) {
            if (sleepC(500)) { log("cancelled"); return }
            val sb = StringBuilder()
            for (n in nodes()) {
                if (n.isEditable) continue
                n.text?.let { sb.append(it).append("\n") }
            }
            val all = sb.toString()
            val curEnds = all.split(END).size - 1
            val fresh = curEnds >= promptEnds + 1 && (curEnds > baseEnds || lastBlock(all) != baseBlock)
            val noResult = !fresh
            val errNow = noResult &&
                (all.contains("Failed to generate content") || all.contains("Permission denied. Please check your account")) &&
                !prompt.contains("Failed to generate content") && !prompt.contains("Permission denied")
            if (errNow && System.currentTimeMillis() - lastErr > 3000 && nodes().none { it.isClickable && (it.text ?: it.contentDescription)?.toString().equals("Stop", true) }) {
                if (errTries >= 2) { log("site error persists"); fail("site error persists"); return }
                errTries++
                lastErr = System.currentTimeMillis()
                nodes().firstOrNull { it.isClickable && (it.text ?: it.contentDescription)?.toString().equals("Dismiss", true) }?.let { clickUp(it); log("dismissed error toast") }
                val hit = { c: CharSequence? ->
                    val t = c?.toString() ?: "-"
                    t.length < 40 && (t.contains("retry", true) || t.contains("rerun", true) || t.contains("re-run", true) || t.contains("regenerate", true))
                }
                fun findRetry() = nodes().firstOrNull { !it.isEditable && (hit(it.text) || hit(it.contentDescription)) }
                var rb = findRetry()
                if (rb == null) {
                    val errNode = nodes().firstOrNull { !it.isEditable && it.text?.toString()?.contains("Permission denied. Please check") == true }
                    if (errNode != null) {
                        val c = clickUp(errNode)
                        log("clicked error message to reveal toolbar: " + c)
                        Thread.sleep(600)
                        rb = findRetry()
                    }
                }
                if (rb != null && clickUp(rb)) {
                    log("site error seen, clicked retry (" + errTries + ")")
                    continue
                }
                val labels = nodes().filter { it.isClickable }
                    .mapNotNull { (it.text ?: it.contentDescription)?.toString() }
                    .filter { it.length < 40 }.take(30)
                log("site error seen, no retry button; clickable: " + labels.joinToString(" | "))
                if (errTries >= 2) { log("retry already clicked, still waiting"); continue }
                if (retry < 1) {
                    if (sleepC(1500)) { log("cancelled"); return }
                    runJob(name, retry + 1)
                    return
                }
                fail("site error"); return
            }
            if (!fresh && System.currentTimeMillis() - lastScroll > 2500) {
                lastScroll = System.currentTimeMillis()
                nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            }
            if (fresh) {
                val s = all.lastIndexOf(START)
                val e = all.indexOf(END, if (s >= 0) s else 0)
                if (s >= 0 && e > s) {
                    log("result found, page text " + all.length + " chars")
                    writeResult(all.substring(s, e + END.length))
                    log("result written")
                    return
                }
            }
        }
        fail("timeout")
    }
}
