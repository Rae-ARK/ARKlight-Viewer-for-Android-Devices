package com.arklight.viewer

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Enables Material You dynamic color (wallpaper-derived theming) on
 * Android 12+ (API 31+) devices, app-wide.
 *
 * [DynamicColors.applyToActivitiesIfAvailable] is a no-op below API 31,
 * so this is safe across the app's full minSdk 24 range -- devices
 * that can't do dynamic color just keep the branded fallback palette
 * defined in themes.xml / values-night/themes.xml.
 */
class ArkViewerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
