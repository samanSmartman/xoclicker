package com.xo.clicker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(48, 48, 48, 48)
        val tv = TextView(this)
        tv.text = "XoClicker v0.1\nOnly works inside Chrome. No internet permission."
        l.addView(tv)
        fun btn(t: String, a: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { a() }
            l.addView(b)
        }
        btn("1) Allow file access (folder xo)") {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2) Open Accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        setContentView(l)
    }
}
