package com.callx.app.splash

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.callx.app.R
import com.callx.app.activities.MainActivity

/**
 * Kali X launch splash screen — user-supplied PNG
 * (res/drawable-nodpi/splash_screen_image.png) full-bleed, EXACTLY as
 * provided, no redraw. Shown once on every real cold start (this is
 * now the app's LAUNCHER activity, see AndroidManifest), then hands
 * off to MainActivity.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val goToMain = Runnable { launchMain() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge, pure-black system bars — full-bleed image, no
        // safe-area cutout, matches the image's own black backdrop.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.splash_screen_image)
        }
        setContentView(imageView)

        // Splash dikhta hai, phir seedha MainActivity par chala jata hai.
        handler.postDelayed(goToMain, SPLASH_DURATION_MS)
    }

    private fun launchMain() {
        if (isFinishing || isDestroyed) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goToMain)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_DURATION_MS = 1200L
    }
}
