package com.example.sunrisewallpaperfilament

import android.content.Context
import android.content.SharedPreferences

object WallpaperSettings {
    const val PREFS_NAME = "wallpaper_settings"
    const val KEY_CLOUDS_ENABLED = "clouds_enabled"
    const val KEY_RAIN_ENABLED = "rain_enabled"
    const val KEY_SNOW_ENABLED = "snow_enabled"
    const val KEY_USE_SYSTEM_TIME = "use_system_time"
    const val KEY_CUSTOM_TIME_MINUTES = "custom_time_minutes"

    fun get(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    const val KEY_USE_REAL_WEATHER = "use_real_weather"
    const val KEY_LAST_WEATHER_UPDATE_MS = "last_weather_update_ms"

    const val KEY_LAST_LOCATION_LAT_BITS = "last_location_lat_bits"
    const val KEY_LAST_LOCATION_LON_BITS = "last_location_lon_bits"

    fun useRealWeather(context: Context): Boolean =
        get(context).getBoolean(KEY_USE_REAL_WEATHER, false)

    fun setUseRealWeather(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_USE_REAL_WEATHER, enabled).apply()
    }

    fun lastWeatherUpdateMs(context: Context): Long =
        get(context).getLong(KEY_LAST_WEATHER_UPDATE_MS, 0L)

    fun setLastWeatherUpdateMs(context: Context, value: Long) {
        get(context).edit().putLong(KEY_LAST_WEATHER_UPDATE_MS, value).apply()
    }

    fun setLocation(context: Context, latitude: Double, longitude: Double) {
        get(context).edit()
            .putLong(KEY_LAST_LOCATION_LAT_BITS, latitude.toRawBits())
            .putLong(KEY_LAST_LOCATION_LON_BITS, longitude.toRawBits())
            .apply()
    }

    fun lastLocation(context: Context): Pair<Double, Double>? {
        val prefs = get(context)
        val latBits = prefs.getLong(KEY_LAST_LOCATION_LAT_BITS, Long.MIN_VALUE)
        val lonBits = prefs.getLong(KEY_LAST_LOCATION_LON_BITS, Long.MIN_VALUE)

        if (latBits == Long.MIN_VALUE || lonBits == Long.MIN_VALUE) return null

        return Double.fromBits(latBits) to Double.fromBits(lonBits)
    }

    const val KEY_OVERCAST_ENABLED = "overcast_enabled"

    fun overcastEnabled(context: Context): Boolean =
        get(context).getBoolean(KEY_OVERCAST_ENABLED, false)

    fun setOvercastEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_OVERCAST_ENABLED, enabled).apply()
    }

    fun cloudsEnabled(context: Context): Boolean = get(context).getBoolean(KEY_CLOUDS_ENABLED, true)
    fun rainEnabled(context: Context): Boolean = get(context).getBoolean(KEY_RAIN_ENABLED, false)
    fun snowEnabled(context: Context): Boolean = get(context).getBoolean(KEY_SNOW_ENABLED, false)

    fun useSystemTime(context: Context): Boolean =
        get(context).getBoolean(KEY_USE_SYSTEM_TIME, true)

    fun customTimeMinutes(context: Context): Int =
        get(context).getInt(KEY_CUSTOM_TIME_MINUTES, 8 * 60)

    fun setCloudsEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_CLOUDS_ENABLED, enabled).apply()
    }

    fun setRainEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_RAIN_ENABLED, enabled).apply()
    }

    fun setSnowEnabled(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(KEY_SNOW_ENABLED, enabled).apply()
    }

    fun setUseSystemTime(context: Context, use: Boolean) {
        get(context).edit().putBoolean(KEY_USE_SYSTEM_TIME, use).apply()
    }

    fun setCustomTimeMinutes(context: Context, minutes: Int) {
        get(context).edit().putInt(KEY_CUSTOM_TIME_MINUTES, minutes).apply()
    }
}