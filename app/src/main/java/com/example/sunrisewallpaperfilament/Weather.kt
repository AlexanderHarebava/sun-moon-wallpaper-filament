package com.example.sunrisewallpaperfilament

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class WeatherState(
    val temperature: Float,
    val precipitation: Float,
    val rain: Float,
    val showers: Float,
    val snowfall: Float,
    val cloudCover: Float,
    val weatherCode: Int
)

data class WeatherEffects(
    val clouds: Boolean,
    val rain: Boolean,
    val snow: Boolean,
    val overcast: Boolean
)

object WeatherFetcher {


    private val RAIN_CODES = setOf(
        51, 53, 55,
        61, 63, 65,
        66, 67,
        80, 81, 82,
        95, 96, 99
    )

    private val SNOW_CODES = setOf(
        71, 73, 75, 77,
        85, 86
    )

    private val OVERCAST_CODES = setOf(
        3,
        45, 48
    )

    fun fetch(latitude: Double, longitude: Double): WeatherState {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=%.5f&longitude=%.5f" +
                        "&current=temperature_2m,precipitation,rain,showers,snowfall,weather_code,cloud_cover" +
                        "&timezone=auto",
                latitude,
                longitude
            )
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP error: ${connection.responseCode}")
            }

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return parse(text)
        } finally {
            connection.disconnect()
        }
    }

    fun parse(json: String): WeatherState {
        val current = JSONObject(json).getJSONObject("current")

        return WeatherState(
            temperature = current.optDouble("temperature_2m", 20.0).toFloat(),
            precipitation = current.optDouble("precipitation", 0.0).toFloat(),
            rain = current.optDouble("rain", 0.0).toFloat(),
            showers = current.optDouble("showers", 0.0).toFloat(),
            snowfall = current.optDouble("snowfall", 0.0).toFloat(),
            cloudCover = current.optDouble("cloud_cover", 0.0).toFloat(),
            weatherCode = current.optInt("weather_code", 0)
        )
    }

    fun toEffects(state: WeatherState): WeatherEffects {
        val anyPrecipitation =
            state.precipitation > 0.02f ||
                    state.rain + state.showers > 0.02f ||
                    state.snowfall > 0.02f

        val snow = state.snowfall > 0.02f ||
                state.weatherCode in SNOW_CODES ||
                (anyPrecipitation && state.temperature <= 0.5f)

        val rain = !snow && (
                state.rain + state.showers > 0.02f ||
                        state.weatherCode in RAIN_CODES ||
                        (anyPrecipitation && state.temperature > 0.5f)
                )

        val overcast = state.cloudCover >= 70f ||
                state.weatherCode in OVERCAST_CODES ||
                rain ||
                snow

        val clouds = state.cloudCover >= 25f || overcast

        return WeatherEffects(
            clouds = clouds,
            rain = rain,
            snow = snow,
            overcast = overcast
        )
    }

    fun applyEffects(context: Context, effects: WeatherEffects) {
        WallpaperSettings.get(context).edit()
            .putBoolean(WallpaperSettings.KEY_CLOUDS_ENABLED, effects.clouds)
            .putBoolean(WallpaperSettings.KEY_RAIN_ENABLED, effects.rain)
            .putBoolean(WallpaperSettings.KEY_SNOW_ENABLED, effects.snow)
            .putBoolean(WallpaperSettings.KEY_OVERCAST_ENABLED, effects.overcast)
            .putLong(WallpaperSettings.KEY_LAST_WEATHER_UPDATE_MS, System.currentTimeMillis())
            .apply()
    }
}