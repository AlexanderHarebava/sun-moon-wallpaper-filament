package com.example.sunrisewallpaperfilament

import android.Manifest
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View as FilamentView
import com.google.android.filament.Viewport
import com.google.android.filament.android.ChoreographerHelper
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.FilamentHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Utils
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.tan

private const val KEY_USE_REAL_WEATHER = "use_real_weather"
private const val KEY_LAST_WEATHER_UPDATE_MS = "last_weather_update_ms"
private const val KEY_LAST_LOCATION_LAT_BITS = "last_location_lat_bits"
private const val KEY_LAST_LOCATION_LON_BITS = "last_location_lon_bits"

private const val WEATHER_WORK_NAME = "real_weather_update"
private const val WEATHER_REFRESH_MINUTES = 30L

private data class RealWeatherState(
    val temperature: Float,
    val precipitation: Float,
    val rain: Float,
    val showers: Float,
    val snowfall: Float,
    val cloudCover: Float,
    val weatherCode: Int
)

private data class RealWeatherEffects(
    val clouds: Boolean,
    val rain: Boolean,
    val snow: Boolean,
    val overcast: Boolean
)

private object RealWeatherFetcher {

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
        3, 45, 48
    )

    fun fetch(latitude: Double, longitude: Double): RealWeatherState {
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

    fun parse(json: String): RealWeatherState {
        val current = JSONObject(json).getJSONObject("current")

        return RealWeatherState(
            temperature = current.optDouble("temperature_2m", 20.0).toFloat(),
            precipitation = current.optDouble("precipitation", 0.0).toFloat(),
            rain = current.optDouble("rain", 0.0).toFloat(),
            showers = current.optDouble("showers", 0.0).toFloat(),
            snowfall = current.optDouble("snowfall", 0.0).toFloat(),
            cloudCover = current.optDouble("cloud_cover", 0.0).toFloat(),
            weatherCode = current.optInt("weather_code", 0)
        )
    }

    fun toEffects(state: RealWeatherState): RealWeatherEffects {
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

        return RealWeatherEffects(
            clouds = clouds,
            rain = rain,
            snow = snow,
            overcast = overcast
        )
    }

    fun applyEffects(context: Context, effects: RealWeatherEffects) {
        WallpaperSettings.get(context).edit()
            .putBoolean(WallpaperSettings.KEY_CLOUDS_ENABLED, effects.clouds)
            .putBoolean(WallpaperSettings.KEY_RAIN_ENABLED, effects.rain)
            .putBoolean(WallpaperSettings.KEY_SNOW_ENABLED, effects.snow)
            .putBoolean(WallpaperSettings.KEY_OVERCAST_ENABLED, effects.overcast)
            .putBoolean(WallpaperSettings.KEY_USE_SYSTEM_TIME, true)
            .putLong(KEY_LAST_WEATHER_UPDATE_MS, System.currentTimeMillis())
            .apply()
    }
}

private fun realWeatherEnabled(context: Context): Boolean {
    return WallpaperSettings.get(context).getBoolean(KEY_USE_REAL_WEATHER, false)
}

private fun setRealWeatherEnabled(context: Context, enabled: Boolean) {
    WallpaperSettings.get(context).edit()
        .putBoolean(KEY_USE_REAL_WEATHER, enabled)
        .apply()
}

private fun lastWeatherUpdateMs(context: Context): Long {
    return WallpaperSettings.get(context).getLong(KEY_LAST_WEATHER_UPDATE_MS, 0L)
}

private fun setLastLocation(context: Context, latitude: Double, longitude: Double) {
    WallpaperSettings.get(context).edit()
        .putLong(KEY_LAST_LOCATION_LAT_BITS, latitude.toRawBits())
        .putLong(KEY_LAST_LOCATION_LON_BITS, longitude.toRawBits())
        .apply()
}

private fun lastLocation(context: Context): Pair<Double, Double>? {
    val prefs = WallpaperSettings.get(context)
    val latBits = prefs.getLong(KEY_LAST_LOCATION_LAT_BITS, Long.MIN_VALUE)
    val lonBits = prefs.getLong(KEY_LAST_LOCATION_LON_BITS, Long.MIN_VALUE)

    if (latBits == Long.MIN_VALUE || lonBits == Long.MIN_VALUE) return null

    return Double.fromBits(latBits) to Double.fromBits(lonBits)
}

private fun scheduleRealWeatherWork(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val request = PeriodicWorkRequest.Builder(
        RealWeatherWorker::class.java,
        WEATHER_REFRESH_MINUTES,
        TimeUnit.MINUTES
    )
        .setConstraints(constraints)
        .addTag(WEATHER_WORK_NAME)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WEATHER_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request
    )
}

private fun cancelRealWeatherWork(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(WEATHER_WORK_NAME)
}

class RealWeatherWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext

        if (!realWeatherEnabled(context)) {
            return Result.success()
        }

        val location = resolveBackgroundLocation(context) ?: return Result.success()

        return try {
            val state = RealWeatherFetcher.fetch(location.first, location.second)
            val effects = RealWeatherFetcher.toEffects(state)
            RealWeatherFetcher.applyEffects(context, effects)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun resolveBackgroundLocation(context: Context): Pair<Double, Double>? {
        val saved = lastLocation(context)

        val hasPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            try {
                val locationManager =
                    context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                ).filter { locationManager.isProviderEnabled(it) }

                var best: Location? = null

                for (provider in providers) {
                    try {
                        val location = locationManager.getLastKnownLocation(provider)
                        if (location != null) {
                            if (best == null || location.time > best.time) {
                                best = location
                            }
                        }
                    } catch (_: Exception) {
                    }
                }

                best?.let {
                    setLastLocation(context, it.latitude, it.longitude)
                    return it.latitude to it.longitude
                }
            } catch (_: Exception) {
            }
        }

        return saved
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        init {
            Utils.init()
        }

        private const val DEFAULT_MINUTES = 8 * 60
    }

    private var cloudController: CloudController? = null
    private var lastFrameTimeNanos = 0L
    private var cloudHalfWidth = 2.0f

    private lateinit var surfaceView: SurfaceView
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: FilamentView
    private lateinit var camera: Camera

    private lateinit var timeSlider: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var useSystemTimeSwitch: Switch
    private lateinit var cloudSwitch: Switch
    private lateinit var rainButton: Button
    private lateinit var snowButton: Button
    private lateinit var overcastButton: Button
    private lateinit var realWeatherButton: Button

    private var snowController: SnowController? = null
    private var sunSkybox: SunSkybox? = null
    private var swapChain: SwapChain? = null
    private val frameScheduler = FrameCallback()
    private var rainController: RainController? = null
    private var rainEnabled = false
    private var snowEnabled = false
    private var overcastEnabled = false
    private val overcast = OvercastState()
    private var currentMinutes = DEFAULT_MINUTES

    private var useRealWeather = false

    @Volatile
    private var activityDestroyed = false

    private val weatherExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var locationListener: LocationListener? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }

        if (granted) {
            if (useRealWeather) {
                fetchLocationAndWeather()
            }
        } else {
            useRealWeather = false
            setRealWeatherEnabled(this@MainActivity, false)
            cancelRealWeatherWork(this@MainActivity)
            updateRealWeatherUi()

            Toast.makeText(
                this@MainActivity,
                getString(R.string.toast_real_weather_requires_location),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        val root = FrameLayout(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        displayHelper = DisplayHelper(this)

        setupSurfaceView()
        setupFilament()
        setupView()
        setupScene()

        cloudController = createClouds(engine, scene, assets)
        rainController = createRain(engine, scene, assets)
        snowController = createSnow(engine, scene, assets)

        setupControls(root)
    }

    private fun setupSurfaceView() {
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
    }

    private fun setupFilament() {
        engine = Engine.create()
        renderer = engine.createRenderer()
        frameScheduler.setRenderer(renderer)
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera(EntityManager.get().create())
    }

    private fun setupView() {
        view.camera = camera
        view.scene = scene
    }

    private fun setupScene() {
        val (r, g, b) = Colors.cct(5_600.0f)
        val moonTexture = loadMoonTexture(assets, engine)

        readUncompressedAsset("materials/sun_skybox.filamat").let { payload ->
            val sun = buildSunSkybox(engine, payload, r, g, b, moonTexture)
            scene.addEntity(sun.renderable)
            sunSkybox = sun
        }

        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
        camera.lookAt(
            0.0, 0.0, 0.0,
            0.0, 0.0, -1.0,
            0.0, 1.0, 0.0
        )
    }

    private fun setupControls(root: FrameLayout) {
        val initialClouds = WallpaperSettings.cloudsEnabled(this)
        val initialOvercast = WallpaperSettings.overcastEnabled(this)
        val initialRain = WallpaperSettings.rainEnabled(this)
        val initialSnow = WallpaperSettings.snowEnabled(this)

        useRealWeather = realWeatherEnabled(this)

        if (useRealWeather && !WallpaperSettings.useSystemTime(this)) {
            WallpaperSettings.setUseSystemTime(this, true)
        }

        val initialUseSystemTime = WallpaperSettings.useSystemTime(this)
        val initialMinutes = WallpaperSettings.customTimeMinutes(this)

        val initialSliderMinutes = if (initialUseSystemTime) {
            val calendar = java.util.Calendar.getInstance()
            calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    calendar.get(java.util.Calendar.MINUTE)
        } else {
            initialMinutes
        }

        rainEnabled = initialRain
        snowEnabled = initialSnow
        overcastEnabled = initialOvercast

        timeLabel = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
        }

        timeSlider = SeekBar(this).apply {
            max = 24 * 60 - 1
            progress = initialSliderMinutes
            isEnabled = !initialUseSystemTime && !useRealWeather

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    applyTimeOfDay(progress)
                    WallpaperSettings.setCustomTimeMinutes(this@MainActivity, progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }

        useSystemTimeSwitch = Switch(this).apply {
            text = getString(R.string.system_time)
            isChecked = initialUseSystemTime
            isEnabled = !useRealWeather
            setTextColor(0xFFFFFFFF.toInt())

            setOnCheckedChangeListener { _, isChecked ->
                WallpaperSettings.setUseSystemTime(this@MainActivity, isChecked)
                timeSlider.isEnabled = !isChecked && !useRealWeather

                if (isChecked) {
                    val calendar = java.util.Calendar.getInstance()
                    val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                            calendar.get(java.util.Calendar.MINUTE)

                    timeSlider.progress = minutes
                    applyTimeOfDay(minutes)
                } else {
                    applyTimeOfDay(timeSlider.progress)
                }
            }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 48)

            addView(
                timeLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                useSystemTimeSwitch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                timeSlider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        realWeatherButton = Button(this).apply {
            text = getString(
                if (useRealWeather) {
                    R.string.real_weather_enabled
                } else {
                    R.string.real_weather_enable
                }
            )

            setTextColor(0xFFFFFFFF.toInt())

            setOnClickListener {
                useRealWeather = !useRealWeather
                setRealWeatherEnabled(this@MainActivity, useRealWeather)

                if (useRealWeather) {
                    WallpaperSettings.setUseSystemTime(this@MainActivity, true)

                    useSystemTimeSwitch.isChecked = true
                    useSystemTimeSwitch.isEnabled = false
                    timeSlider.isEnabled = false

                    val calendar = java.util.Calendar.getInstance()
                    val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                            calendar.get(java.util.Calendar.MINUTE)

                    timeSlider.progress = minutes
                    applyTimeOfDay(minutes)

                    scheduleRealWeatherWork(this@MainActivity)
                    updateRealWeatherUi()
                    ensureLocationPermissionAndFetchWeather()
                } else {
                    cancelRealWeatherWork(this@MainActivity)

                    useSystemTimeSwitch.isEnabled = true
                    timeSlider.isEnabled = !WallpaperSettings.useSystemTime(this@MainActivity)

                    updateRealWeatherUi()
                }
            }
        }

        controls.addView(
            realWeatherButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        cloudSwitch = Switch(this).apply {
            text = getString(R.string.clouds)
            isChecked = initialClouds
            setTextColor(0xFFFFFFFF.toInt())

            setOnCheckedChangeListener { _, isChecked ->
                cloudController?.enabled = isChecked
                WallpaperSettings.setCloudsEnabled(this@MainActivity, isChecked)
            }
        }

        controls.addView(
            cloudSwitch,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        rainButton = Button(this).apply {
            text = toggleLabel(R.string.label_rain, initialRain)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                rainEnabled = !rainEnabled
                text = toggleLabel(R.string.label_rain, rainEnabled)
                rainController?.enabled = rainEnabled
                WallpaperSettings.setRainEnabled(this@MainActivity, rainEnabled)
            }
        }

        controls.addView(
            rainButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        snowButton = Button(this).apply {
            text = toggleLabel(R.string.label_snow, initialSnow)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                snowEnabled = !snowEnabled
                text = toggleLabel(R.string.label_snow, snowEnabled)
                snowController?.enabled = snowEnabled
                WallpaperSettings.setSnowEnabled(this@MainActivity, snowEnabled)
            }
        }

        controls.addView(
            snowButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        overcastButton = Button(this).apply {
            text = toggleLabel(R.string.label_overcast, initialOvercast)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                overcastEnabled = !overcastEnabled
                text = toggleLabel(R.string.label_overcast, overcastEnabled)
                overcast.setEnabled(overcastEnabled)
                WallpaperSettings.setOvercastEnabled(this@MainActivity, overcastEnabled)
                applyTimeOfDay(timeSlider.progress)
            }
        }

        controls.addView(
            overcastButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val installWallpaperButton = Button(this).apply {
            text = getString(R.string.install_wallpaper)
            setTextColor(0xFFFFFFFF.toInt())

            setOnClickListener {
                openLiveWallpaperPicker()
            }
        }

        controls.addView(
            installWallpaperButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val aboutButton = Button(this).apply {
            text = getString(R.string.about_title)
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AboutActivity::class.java))
            }
        }
        controls.addView(
            aboutButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        cloudController?.enabled = initialClouds
        rainController?.enabled = initialRain
        snowController?.enabled = initialSnow
        overcast.setEnabled(initialOvercast, immediate = true)

        applyTimeOfDay(timeSlider.progress)
        updateRealWeatherUi()
    }

    private fun openLiveWallpaperPicker() {
        val wallpaperComponent = ComponentName(
            this,
            SunriseLiveWallpaperService::class.java
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        wallpaperComponent
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_wallpaper_picker_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyTimeOfDay(minutesRaw: Int) {
        val minutes = minutesRaw % (24 * 60)
        val hour = minutes / 60f
        currentMinutes = minutes

        val o = overcast.factor
        val (zenith, horizon) = skyColorsForHour(hour, o)

        timeLabel.text = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

        sunSkybox?.applySkyColors(zenith, horizon)
        sunSkybox?.applySunForHour(hour, o)
        sunSkybox?.applyStarsForHour(hour, o)
        cloudController?.applyTimeOfDay(hour, o)
        sunSkybox?.applyMoonForHour(hour, o)
    }

    override fun onResume() {
        super.onResume()
        frameScheduler.post()

        useRealWeather = realWeatherEnabled(this)

        if (useRealWeather) {
            if (!WallpaperSettings.useSystemTime(this)) {
                WallpaperSettings.setUseSystemTime(this, true)
            }

            if (::useSystemTimeSwitch.isInitialized) {
                useSystemTimeSwitch.isChecked = true
                useSystemTimeSwitch.isEnabled = false
            }

            val calendar = java.util.Calendar.getInstance()
            val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    calendar.get(java.util.Calendar.MINUTE)

            if (::timeSlider.isInitialized) {
                timeSlider.isEnabled = false
                timeSlider.progress = minutes
                applyTimeOfDay(minutes)
            }

            scheduleRealWeatherWork(this)
        } else {
            cancelRealWeatherWork(this)

            if (WallpaperSettings.useSystemTime(this)) {
                val calendar = java.util.Calendar.getInstance()
                val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                        calendar.get(java.util.Calendar.MINUTE)

                if (::timeSlider.isInitialized) {
                    timeSlider.progress = minutes
                    applyTimeOfDay(minutes)
                }
            }

            if (::useSystemTimeSwitch.isInitialized) {
                useSystemTimeSwitch.isEnabled = true
            }
        }

        updateRealWeatherUi()

        if (useRealWeather && hasLocationPermission() && isWeatherStale()) {
            fetchLocationAndWeather()
        }
    }

    override fun onPause() {
        super.onPause()
        frameScheduler.remove()
        removeLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()

        activityDestroyed = true

        frameScheduler.remove()
        removeLocationUpdates()

        mainHandler.removeCallbacksAndMessages(null)
        weatherExecutor.shutdownNow()

        uiHelper.detach()

        sunSkybox?.let {
            scene.removeEntity(it.renderable)
            it.destroy(engine)
            sunSkybox = null
        }

        cloudController?.destroy()
        cloudController = null

        rainController?.destroy()
        rainController = null

        snowController?.destroy()
        snowController = null

        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        engine.destroy()
    }

    private fun toggleLabel(@StringRes labelRes: Int, enabled: Boolean): String {
        val stateRes = if (enabled) R.string.state_on else R.string.state_off
        return getString(R.string.toggle_format, getString(labelRes), getString(stateRes))
    }

    private fun updateRealWeatherUi() {
        if (!::realWeatherButton.isInitialized) return

        realWeatherButton.text = getString(
            if (useRealWeather) {
                R.string.real_weather_enabled
            } else {
                R.string.real_weather_enable
            }
        )

        val manualEnabled = !useRealWeather

        if (::cloudSwitch.isInitialized) {
            cloudSwitch.isEnabled = manualEnabled
        }

        if (::rainButton.isInitialized) {
            rainButton.isEnabled = manualEnabled
        }

        if (::snowButton.isInitialized) {
            snowButton.isEnabled = manualEnabled
        }

        if (::overcastButton.isInitialized) {
            overcastButton.isEnabled = manualEnabled
        }

        if (::useSystemTimeSwitch.isInitialized) {
            useSystemTimeSwitch.isEnabled = manualEnabled
        }

        if (::timeSlider.isInitialized) {
            timeSlider.isEnabled = !WallpaperSettings.useSystemTime(this) && manualEnabled
        }
    }

    private fun hasLocationPermission(): Boolean {
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return coarseGranted || fineGranted
    }

    private fun ensureLocationPermissionAndFetchWeather() {
        if (!useRealWeather) return

        if (hasLocationPermission()) {
            fetchLocationAndWeather()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocationAndWeather() {
        if (!useRealWeather) return
        if (!hasLocationPermission()) return

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { locationManager.isProviderEnabled(it) }

        if (providers.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_location_provider), Toast.LENGTH_SHORT)
                .show()
            return
        }

        try {
            val bestLastLocation = providers
                .mapNotNull { locationManager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }

            if (bestLastLocation != null) {
                fetchWeather(bestLastLocation.latitude, bestLastLocation.longitude)

                val ageMs = System.currentTimeMillis() - bestLastLocation.time
                if (ageMs <= 30 * 60 * 1000L) {
                    return
                }
            }

            removeLocationUpdates()

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    removeLocationUpdates()
                    fetchWeather(location.latitude, location.longitude)
                }

                override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}

                override fun onProviderEnabled(provider: String) {}

                override fun onProviderDisabled(provider: String) {}
            }

            locationListener = listener
            locationManager.requestLocationUpdates(
                providers.first(),
                0L,
                0f,
                listener
            )
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                getString(R.string.toast_no_location_permission),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_location_failed), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun removeLocationUpdates() {
        locationListener?.let { listener ->
            try {
                val locationManager =
                    getSystemService(Context.LOCATION_SERVICE) as LocationManager
                locationManager.removeUpdates(listener)
            } catch (_: Exception) {
            }
        }

        locationListener = null
    }

    private fun fetchWeather(latitude: Double, longitude: Double) {
        setLastLocation(this, latitude, longitude)

        weatherExecutor.execute {
            if (activityDestroyed) return@execute

            try {
                val state = RealWeatherFetcher.fetch(latitude, longitude)
                val effects = RealWeatherFetcher.toEffects(state)

                mainHandler.post {
                    if (!activityDestroyed) {
                        applyRealWeatherEffects(effects)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (!activityDestroyed) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_weather_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun applyRealWeatherEffects(effects: RealWeatherEffects) {
        if (!useRealWeather) return

        RealWeatherFetcher.applyEffects(this, effects)

        rainEnabled = effects.rain
        snowEnabled = effects.snow
        overcastEnabled = effects.overcast

        cloudController?.enabled = effects.clouds
        rainController?.enabled = effects.rain
        snowController?.enabled = effects.snow
        overcast.setEnabled(effects.overcast)

        if (::cloudSwitch.isInitialized) {
            cloudSwitch.isChecked = effects.clouds
        }

        if (::rainButton.isInitialized) {
            rainButton.text = toggleLabel(R.string.label_rain, effects.rain)
        }

        if (::snowButton.isInitialized) {
            snowButton.text = toggleLabel(R.string.label_snow, effects.snow)
        }

        if (::overcastButton.isInitialized) {
            overcastButton.text = toggleLabel(R.string.label_overcast, effects.overcast)
        }

        if (WallpaperSettings.useSystemTime(this)) {
            val calendar = java.util.Calendar.getInstance()
            val minutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    calendar.get(java.util.Calendar.MINUTE)

            if (::timeSlider.isInitialized) {
                timeSlider.progress = minutes
                applyTimeOfDay(minutes)
            }
        } else {
            if (::timeSlider.isInitialized) {
                applyTimeOfDay(timeSlider.progress)
            }
        }
    }

    private fun isWeatherStale(): Boolean {
        val last = lastWeatherUpdateMs(this)
        return System.currentTimeMillis() - last > 30 * 60 * 1000L
    }

    inner class FrameCallback : ChoreographerHelper() {
        override fun onFrame(frameTimeNanos: Long) {
            val sc = swapChain ?: return

            if (!uiHelper.isReadyToRender) {
                return
            }

            val dt = if (lastFrameTimeNanos == 0L) {
                0.016f
            } else {
                ((frameTimeNanos - lastFrameTimeNanos) / 1e9f).coerceIn(0f, 0.1f)
            }

            if (overcast.update(dt)) {
                applyTimeOfDay(currentMinutes)
            }

            lastFrameTimeNanos = frameTimeNanos

            cloudController?.frameUpdate(dt, cloudHalfWidth)
            rainController?.frameUpdate(dt)
            snowController?.frameUpdate(dt)

            if (renderer.beginFrame(sc, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
            }
        }
    }

    inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
            displayHelper.attach(renderer, getDisplayCompat())
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()

            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return

            val aspect = width.toDouble() / height.toDouble()

            camera.setProjection(
                45.0,
                aspect,
                0.1,
                20.0,
                Camera.Fov.VERTICAL
            )

            view.viewport = Viewport(0, 0, width, height)
            FilamentHelper.synchronizePendingFrames(engine)

            cloudHalfWidth = (tan(22.5 * PI / 180.0) * CLOUD_DISTANCE * aspect).toFloat()

            rainController?.setAspect(aspect.toFloat())
            snowController?.setAspect(aspect.toFloat())
        }
    }

    private fun getDisplayCompat(): Display {
        if (Build.VERSION.SDK_INT >= 30) {
            display?.let { return it }
        }

        @Suppress("DEPRECATION")
        return (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay
    }

    private fun readUncompressedAsset(assetName: String): ByteBuffer {
        assets.openFd(assetName).use { fd ->
            val input = fd.createInputStream()
            val dst = ByteBuffer.allocate(fd.length.toInt())
            val src = Channels.newChannel(input)
            src.read(dst)
            src.close()
            return dst.apply { rewind() }
        }
    }
}