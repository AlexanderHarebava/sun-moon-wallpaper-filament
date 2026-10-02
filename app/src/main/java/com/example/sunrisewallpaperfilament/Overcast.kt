package com.example.sunrisewallpaperfilament

import kotlin.math.abs


class OvercastState {
    var target: Float = 0f
        private set
    var factor: Float = 0f
        private set

    fun setEnabled(enabled: Boolean, immediate: Boolean = false) {
        target = if (enabled) 1f else 0f
        if (immediate) factor = target
    }


    fun update(dt: Float): Boolean {
        if (factor == target) return false
        if (abs(factor - target) < 0.002f) {
            factor = target
            return true
        }
        factor += (target - factor) * minOf(1f, dt * 1.8f)
        return true
    }
}