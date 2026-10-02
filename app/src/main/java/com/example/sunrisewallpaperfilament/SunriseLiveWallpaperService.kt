package com.example.sunrisewallpaperfilament

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Viewport
import com.google.android.filament.android.ChoreographerHelper
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.FilamentHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import com.google.android.filament.View as FilamentView

class SunriseLiveWallpaperService : WallpaperService() {

    companion object {
        init {
            Utils.init()
        }
    }

    override fun onCreateEngine(): WallpaperService.Engine {
        return SunriseWallpaperEngine()
    }

    private inner class SunriseWallpaperEngine : WallpaperService.Engine() {
        private lateinit var prefs: SharedPreferences
        private lateinit var uiHelper: UiHelper
        private lateinit var displayHelper: DisplayHelper

        private lateinit var engine: com.google.android.filament.Engine
        private lateinit var renderer: Renderer
        private lateinit var scene: Scene
        private lateinit var view: FilamentView
        private lateinit var camera: Camera

        private var swapChain: SwapChain? = null

        private var sunSkybox: SunSkybox? = null
        private var cloudController: CloudController? = null
        private var rainController: RainController? = null
        private var snowController: SnowController? = null
        private val overcast = OvercastState()
        private val frameScheduler = FrameCallback()

        private var lastFrameTimeNanos = 0L
        private var cloudHalfWidth = 2.0f
        private var aspect = 9f / 16f

        private var lastTimeUpdateMs = 0L
        private var lastHour = -1f

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            surfaceHolder.setSizeFromLayout()
            surfaceHolder.setFormat(PixelFormat.RGBA_8888)

            displayHelper = DisplayHelper(this@SunriseLiveWallpaperService)

            setupUiHelper()
            setupFilament()
            setupView()
            setupScene()

            prefs = WallpaperSettings.get(this@SunriseLiveWallpaperService)
            applySavedSettings()
            prefs.registerOnSharedPreferenceChangeListener(settingsListener)
        }

        private val settingsListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    WallpaperSettings.KEY_CLOUDS_ENABLED -> {
                        cloudController?.enabled =
                            prefs.getBoolean(WallpaperSettings.KEY_CLOUDS_ENABLED, true)
                    }

                    WallpaperSettings.KEY_RAIN_ENABLED -> {
                        rainController?.enabled =
                            prefs.getBoolean(WallpaperSettings.KEY_RAIN_ENABLED, false)
                    }

                    WallpaperSettings.KEY_SNOW_ENABLED -> {
                        snowController?.enabled =
                            prefs.getBoolean(WallpaperSettings.KEY_SNOW_ENABLED, false)
                    }

                    WallpaperSettings.KEY_USE_SYSTEM_TIME,
                    WallpaperSettings.KEY_CUSTOM_TIME_MINUTES -> {

                        applyCurrentTime(force = true)
                    }

                    WallpaperSettings.KEY_OVERCAST_ENABLED -> {
                        overcast.setEnabled(
                            prefs.getBoolean(
                                WallpaperSettings.KEY_OVERCAST_ENABLED,
                                false
                            )
                        )
                    }

                    WallpaperSettings.KEY_USE_REAL_WEATHER -> {
                        applySavedSettings()
                    }
                }
            }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                lastFrameTimeNanos = 0L
                frameScheduler.post()
            } else {
                frameScheduler.remove()
            }
        }

        override fun onDestroy() {
            super.onDestroy()

            if (::prefs.isInitialized) {
                prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
            }

            frameScheduler.remove()

            if (::uiHelper.isInitialized) {
                uiHelper.detach()
            }

            if (::engine.isInitialized) {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    swapChain = null
                }

                sunSkybox?.let {
                    scene.removeEntity(it.renderable)
                    it.destroy(engine)
                }
                sunSkybox = null

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
        }

        private fun setupUiHelper() {
            uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            uiHelper.renderCallback = SurfaceCallback()
            uiHelper.attachTo(surfaceHolder)
        }

        private fun setupFilament() {
            engine = com.google.android.filament.Engine.create()
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

            val sunPayload = readAssetBytes("materials/sun_skybox.filamat")

            val sun = buildSunSkybox(
                engine = engine,
                payload = sunPayload,
                sunR = r,
                sunG = g,
                sunB = b,
                moonTexture = moonTexture
            )

            scene.addEntity(sun.renderable)
            sunSkybox = sun

            camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)

            camera.lookAt(
                0.0, 0.0, 0.0,
                0.0, 0.0, -1.0,
                0.0, 1.0, 0.0
            )

            cloudController = createClouds(engine, scene, assets)
            rainController = createRain(engine, scene, assets)
            snowController = createSnow(engine, scene, assets)
            prefs = WallpaperSettings.get(this@SunriseLiveWallpaperService)
            applySavedSettings()
            prefs.registerOnSharedPreferenceChangeListener(settingsListener)
            applyCurrentTime(force = true)
        }

        private fun applySavedSettings() {
            val context = this@SunriseLiveWallpaperService

            cloudController?.enabled = WallpaperSettings.cloudsEnabled(context)
            rainController?.enabled = WallpaperSettings.rainEnabled(context)
            snowController?.enabled = WallpaperSettings.snowEnabled(context)
            overcast.setEnabled(WallpaperSettings.overcastEnabled(context), immediate = true)
        }

        private fun applyCurrentTime(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && lastTimeUpdateMs != 0L && now - lastTimeUpdateMs < 1000L) {
                return
            }
            lastTimeUpdateMs = now


            val hour = if (WallpaperSettings.useSystemTime(this@SunriseLiveWallpaperService)) {
                val calendar = Calendar.getInstance()
                calendar.get(Calendar.HOUR_OF_DAY) +
                        calendar.get(Calendar.MINUTE) / 60f +
                        calendar.get(Calendar.SECOND) / 3600f
            } else {
                WallpaperSettings.customTimeMinutes(this@SunriseLiveWallpaperService) / 60f
            }

            if (force || abs(hour - lastHour) > 0.0001f) {
                lastHour = hour
                applyTimeOfDay(hour)
            }
        }

        private fun applyTimeOfDay(hour: Float) {
            val o = overcast.factor
            val (zenith, horizon) = skyColorsForHour(hour, o)
            sunSkybox?.applySkyColors(zenith, horizon)
            sunSkybox?.applySunForHour(hour, o)
            sunSkybox?.applyStarsForHour(hour, o)
            sunSkybox?.applyMoonForHour(hour, o)
            cloudController?.applyTimeOfDay(hour, o)
        }

        private fun readAssetBytes(assetName: String): ByteBuffer {
            return try {
                assets.openFd(assetName).use { fd ->
                    val input = fd.createInputStream()
                    val dst = ByteBuffer.allocate(fd.length.toInt())
                    val src = Channels.newChannel(input)
                    src.read(dst)
                    src.close()
                    dst.rewind()
                    dst
                }
            } catch (e: Throwable) {
                val bytes = assets.open(assetName).use { it.readBytes() }
                ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { rewind() }
            }
        }

        inner class FrameCallback : ChoreographerHelper() {
            override fun onFrame(frameTimeNanos: Long) {
                if (!::uiHelper.isInitialized || !uiHelper.isReadyToRender) {
                    return
                }

                val sc = swapChain ?: return

                val dt = if (lastFrameTimeNanos == 0L) {
                    0.016f
                } else {
                    ((frameTimeNanos - lastFrameTimeNanos) / 1e9f).coerceIn(0f, 0.1f)
                }

                lastFrameTimeNanos = frameTimeNanos

                applyCurrentTime(force = false)
                if (overcast.update(dt) && lastHour >= 0f) {
                    applyTimeOfDay(lastHour)
                }
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

                lastFrameTimeNanos = 0L
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

                val newAspect = width.toDouble() / height.toDouble()
                aspect = newAspect.toFloat()

                camera.setProjection(
                    45.0,
                    newAspect,
                    0.1,
                    20.0,
                    Camera.Fov.VERTICAL
                )

                view.viewport = Viewport(0, 0, width, height)

                FilamentHelper.synchronizePendingFrames(engine)

                cloudHalfWidth = (
                        tan(22.5 * PI / 180.0) * CLOUD_DISTANCE * newAspect
                        ).toFloat()

                rainController?.setAspect(aspect)
                snowController?.setAspect(aspect)
            }
        }

        private fun getDisplayCompat(): Display {
            if (Build.VERSION.SDK_INT >= 30) {
                displayContext?.let { context ->
                    Api30Impl.getDisplay(context)?.let { display ->
                        return display
                    }
                }
            }

            @Suppress("DEPRECATION")
            return (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }
    }
}

@RequiresApi(30)
private object Api30Impl {
    fun getDisplay(context: Context): Display? {
        return context.display
    }
}