package com.callx.app.activities

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.callx.app.R

/**
 * AboutActivity — Chats tab → 3-dot overflow menu → "About".
 *
 * CallX2 ka pehla Kotlin screen. Java project me Kotlin support jodne ke
 * baad first proof-of-concept ke taur par banaya gaya — koi XML layout
 * nahi, [PerformanceReportActivity] / [CrashReportActivity] jaisa hi
 * code-built UI pattern follow karta hai taaki resource wiring ki zaroorat
 * na pade.
 *
 * Dikhata hai: app name, version name/code, package name, aur device's
 * Android version — sab kuch PackageManager se live read hota hai, kahin
 * hardcode nahi.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Header: back button + title ──────────────────────────────
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            val hPad = dp(12)
            setPadding(hPad, hPad, hPad, hPad)
        }

        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { finish() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        val headerTitle = TextView(this).apply {
            text = "About"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            val p = dp(12)
            setPadding(p, 0, 0, 0)
        }
        header.addView(
            headerTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        outer.addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // ── Body ──────────────────────────────────────────────────────
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, p, p, p)
        }

        val appNameView = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        }
        body.addView(appNameView)

        val (versionName, versionCode) = appVersionInfo()

        body.addView(infoRow("Version", "$versionName ($versionCode)"))
        body.addView(infoRow("Package", packageName))
        body.addView(infoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        body.addView(infoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}"))

        val tagline = TextView(this).apply {
            text = "Real-time chat, calls, reels aur social presence — " +
                "sab ek hi app me."
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
        }
        body.addView(tagline)

        outer.addView(body)
        scroll.addView(outer)
        setContentView(scroll)
    }

    /** Ek "Label: Value" row banata hai, PerformanceReportActivity jaisi styling ke sath. */
    private fun infoRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#888888"))
            textSize = 12f
        }
        val valueView = TextView(this).apply {
            text = value
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(2), 0, 0)
        }
        row.addView(labelView)
        row.addView(valueView)
        return row
    }

    /** versionName aur versionCode PackageManager se safely nikalta hai. */
    private fun appVersionInfo(): Pair<String, Long> {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            Pair(pInfo.versionName ?: "—", code)
        } catch (e: PackageManager.NameNotFoundException) {
            Pair("—", 0L)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
