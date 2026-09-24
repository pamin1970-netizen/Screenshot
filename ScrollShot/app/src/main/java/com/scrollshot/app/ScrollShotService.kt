package com.scrollshot.app

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

class ScrollShotService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var wm: WindowManager
    private var button: TextView? = null
    private var busy = false

    companion object {
        const val DEEPSEEK_PKG = "com.deepseek.chat"
        const val MAX_FRAMES = 20
        const val MAX_HEIGHT = 20000
        const val SCROLL_SETTLE_MS = 800L
        const val SHOT_GAP_MS = 1100L   // takeScreenshot is rate limited (~1/sec)
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        button?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    private fun addButton() {
        val size = (52 * resources.displayMetrics.density).toInt()
        val b = TextView(this).apply {
            text = "⇩"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1565C0.toInt())
            }
            setOnClickListener { if (!busy) capture() }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        wm.addView(b, lp)
        button = b
    }

    private fun capture() {
        busy = true
        scope.launch {
            try {
                button?.visibility = View.INVISIBLE
                toast("Capturing… don't touch the screen")
                delay(500)
                val file = withScrolling()
                if (file != null) share(file) else toast("Screenshot failed")
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            } finally {
                button?.visibility = View.VISIBLE
                busy = false
            }
        }
    }

    private suspend fun withScrolling(): File? {
        val first = shotRetry() ?: return null
        val w = first.width
        val h = first.height
        val scroller = rootInActiveWindow?.let { findScrollable(it) }

        // No scrollable container: just save the plain screenshot.
        if (scroller == null) return withContext(Dispatchers.Default) { save(first) }

        val r = Rect().also { scroller.getBoundsInScreen(it) }
        r.intersect(0, 0, w, h)
        if (r.height() < 100) return withContext(Dispatchers.Default) { save(first) }

        val header = if (r.top > 0) Bitmap.createBitmap(first, 0, 0, w, r.top) else null
        var prev = Bitmap.createBitmap(first, 0, r.top, w, r.height())
        val segs = mutableListOf(prev to 0)        // (bitmap, rows to skip at top)
        var lastFull = first
        var total = (header?.height ?: 0) + prev.height

        for (i in 1 until MAX_FRAMES) {
            if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            delay(SCROLL_SETTLE_MS)
            delay(SHOT_GAP_MS - SCROLL_SETTLE_MS)
            val f = shotRetry() ?: break
            val crop = Bitmap.createBitmap(f, 0, r.top, w, r.height())
            val o = withContext(Dispatchers.Default) { Stitcher.overlap(prev, crop) }
            if (o >= crop.height - 2) { lastFull = f; break }          // nothing moved: bottom reached
            segs.add(crop to o)
            total += crop.height - o
            if (lastFull !== first) lastFull.recycle()
            lastFull = f
            prev = crop
            if (total > MAX_HEIGHT) break
        }

        val footer = if (r.bottom < h) Bitmap.createBitmap(lastFull, 0, r.bottom, w, h - r.bottom) else null
        return withContext(Dispatchers.Default) {
            val out = Bitmap.createBitmap(w, total + (footer?.height ?: 0), Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            var y = 0
            header?.let { c.drawBitmap(it, 0f, 0f, null); y += it.height }
            for ((b, skip) in segs) {
                c.drawBitmap(b, Rect(0, skip, w, b.height), Rect(0, y, w, y + b.height - skip), null)
                y += b.height - skip
            }
            footer?.let { c.drawBitmap(it, 0f, y.toFloat(), null) }
            save(out)
        }
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n.isScrollable && n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > bestArea) { best = n; bestArea = area }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return best
    }

    private suspend fun shotRetry(): Bitmap? = shot() ?: run { delay(1200); shot() }

    private suspend fun shot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                result.hardwareBuffer.close()
                cont.resume(bmp)
            }
            override fun onFailure(errorCode: Int) { cont.resume(null) }
        })
    }

    private fun save(bmp: Bitmap): File {
        val dir = File(cacheDir, "shots").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "scroll_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent(send).setPackage(DEEPSEEK_PKG))
        } catch (e: Exception) {
            toast("DeepSeek didn't accept the image – pick an app")
            startActivity(Intent.createChooser(send, "Send screenshot to…")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
