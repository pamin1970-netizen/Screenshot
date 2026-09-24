package com.scrollshot.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            textSize = 16f
            text = "ScrollShot\n\n" +
                "1. Tap the button below and enable \"ScrollShot\" under Accessibility " +
                "(Installed apps / Downloaded apps).\n\n" +
                "2. Open any scrolling screen (web page, chat, list, feed).\n\n" +
                "3. Tap the blue ⇩ button on the right edge. The app scrolls and captures " +
                "the screen automatically, stitches one tall image, and opens it in DeepSeek.\n\n" +
                "Don't touch the screen while it's capturing.\n\n" +
                "Android 13+: if the switch is greyed out, open Settings > Apps > ScrollShot > " +
                "⋮ menu > \"Allow restricted settings\", then try again."
        })
        col.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
