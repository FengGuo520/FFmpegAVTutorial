package com.lovelymaple.ffmpegavtutorial

import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal fun setupStatusBarSpace(
    activity: androidx.appcompat.app.AppCompatActivity,
    statusBarSpace: View,
    lightStatusBarIcons: Boolean
) {
    WindowCompat.setDecorFitsSystemWindows(activity.window, false)
    activity.window.statusBarColor = Color.TRANSPARENT
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars =
        lightStatusBarIcons

    ViewCompat.setOnApplyWindowInsetsListener(statusBarSpace) { view, insets ->
        val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        val params = view.layoutParams
        if (params.height != topInset) {
            params.height = topInset
            view.layoutParams = params
        }
        insets
    }
}

internal fun setupNavigationBarSpace(navigationBarSpace: View) {
    ViewCompat.setOnApplyWindowInsetsListener(navigationBarSpace) { view, insets ->
        val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val params = view.layoutParams
        if (params.height != bottomInset) {
            params.height = bottomInset
            view.layoutParams = params
        }
        insets
    }
}
