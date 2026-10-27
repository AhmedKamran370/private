package com.bundlebot

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class A11y : AccessibilityService() {
    companion object { var inst: A11y? = null }
    private var last = ""
    private val h = Handler(Looper.getMainLooper())
    private val loop = object : Runnable {
        override fun run() { try { if (Store.rec) snap() } catch (e: Exception) { }; h.postDelayed(this, 2000) }
    }

    override fun onServiceConnected() { Store.init(this); inst = this; h.removeCallbacks(loop); h.post(loop) }
    override fun onUnbind(i: android.content.Intent?): Boolean { inst = null; h.removeCallbacks(loop); return super.onUnbind(i) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        try {
            if (!Store.rec) return
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && e.packageName != packageName) {
                val t = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "")).trim()
                Store.recText = (Store.recText + "\nTAP: " + t).takeLast(60000)
            }
        } catch (ex: Exception) { }
    }

    private fun snap() {
        val root = rootInActiveWindow ?: return
        if (root.packageName == packageName) return
        val t = dump(root)
        if (t.isBlank() || t == last) return
        last = t
        Store.recText = (Store.recText + "\n---- SCREEN of ${root.packageName}\n" + t).takeLast(60000)
    }

    fun dump(root: AccessibilityNodeInfo?): String {
        val sb = StringBuilder(); var cnt = 0
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || cnt > 400) return
            cnt++
            if (!n.isPassword) {
                val s = (n.text ?: n.contentDescription)?.toString()
                if (!s.isNullOrBlank()) sb.append(if (n.isClickable) "[tap] " else "").append(s).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString()
    }

    // Screenshot, crop the bottom quarter (price area) and save to Pictures/BundleBot.
    fun shotTest(cb: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb("Needs Android 11 or newer"); return }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    val bmp = hw!!.copy(Bitmap.Config.ARGB_8888, false)
                    r.hardwareBuffer.close()
                    cb(save(Bitmap.createBitmap(bmp, 0, 0, bmp.width, (bmp.height * 0.75).toInt())))
                } catch (e: Exception) { cb("Failed: $e") }
            }
            override fun onFailure(code: Int) { cb("Screenshot refused (code $code). The app may block screenshots.") }
        })
    }

    private fun save(b: Bitmap): String {
        if (Build.VERSION.SDK_INT < 29) return "Saving needs Android 10 or newer"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "proof_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BundleBot")
        }
        val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return "Could not save"
        contentResolver.openOutputStream(u)?.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "Saved. Look in the Proofs page. If the picture is black, that app blocks screenshots."
    }
}
