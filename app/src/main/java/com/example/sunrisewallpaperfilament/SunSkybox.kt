package com.example.sunrisewallpaperfilament

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import java.lang.Math.toRadians
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

data class SunSkybox(
    @Entity val renderable: Int,
    val vertexBuffer: VertexBuffer,
    val material: Material,
    val materialInstance: MaterialInstance,
    val moonTexture: Texture
) {

    fun destroy(engine: Engine) {
        engine.destroyEntity(renderable)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyMaterialInstance(materialInstance)
        engine.destroyMaterial(material)
        engine.destroyTexture(moonTexture)
        EntityManager.get().destroy(renderable)
    }

    fun applySunSettings(visible: Boolean, intensity: Float, radiusDeg: Float, glow: Float) {
        materialInstance.setParameter("sunVisible", if (visible) 1.0f else 0.0f)
        materialInstance.setParameter("sunIntensity", intensity)
        materialInstance.setParameter("sunRadiusDeg", radiusDeg)
        materialInstance.setParameter("glowStrength", glow)
    }


    fun applyStarsForHour(hour: Float) {
        val h = ((hour % 24f) + 24f) % 24f
        val vis = when {
            h >= STARS_START_HOUR -> ((h - STARS_START_HOUR) / STARS_FADE_HOURS).coerceIn(0f, 1f)
            h <= STARS_END_HOUR -> ((STARS_END_HOUR - h) / STARS_FADE_HOURS).coerceIn(0f, 1f)
            else -> 0f
        }
        materialInstance.setParameter("starVisibility", vis)
    }

    fun applySkyColors(zenith: FloatArray, horizon: FloatArray) {
        materialInstance.setParameter("skyZenith", zenith[0], zenith[1], zenith[2])
        materialInstance.setParameter("skyHorizon", horizon[0], horizon[1], horizon[2])
    }


    fun applyMoonForHour(hour: Float) {
        val dir = moonDirectionForHour(hour)
        materialInstance.setParameter("moonDir", dir[0], dir[1], dir[2])


        val horizonFade = ((dir[1] + 0.02f) / 0.05f).coerceIn(0f, 1f)
        materialInstance.setParameter("moonVisible", horizonFade)
    }

    fun applySunForHour(hour: Float, overcast: Float = 0f) {
        val dir = sunDirectionForHour(hour)
        materialInstance.setParameter("sunDir", dir[0], dir[1], dir[2])
        val horizonFade = ((dir[1] + 0.02f) / 0.05f).coerceIn(0f, 1f)
        materialInstance.setParameter("sunVisible", horizonFade)

        val elevation = asin(dir[1].coerceIn(-1f, 1f))
        val t = (elevation / ELEVATION_FULL_NOON).coerceIn(0f, 1f)


        var color = lerpRgb(SUN_HORIZON_LINEAR, SUN_NOON_LINEAR, t)

        val o = overcast.coerceIn(0f, 1f)


        if (o > 0.001f) {
            color = lerpRgb(color, OVERCAST_SUN_LINEAR, o)
        }

        materialInstance.setParameter("sunColor", color[0], color[1], color[2])


        val dim = 1f - 0.92f * o
        materialInstance.setParameter(
            "sunIntensity",
            SunConfig.INTENSITY * (0.55f + 0.45f * t) * dim
        )
        materialInstance.setParameter("glowStrength", SunConfig.GLOW * (1f - 0.75f * o))
    }

    fun applyStarsForHour(hour: Float, overcast: Float = 0f) {
        val h = ((hour % 24f) + 24f) % 24f
        val vis = when {
            h >= STARS_START_HOUR -> ((h - STARS_START_HOUR) / STARS_FADE_HOURS).coerceIn(0f, 1f)
            h <= STARS_END_HOUR -> ((STARS_END_HOUR - h) / STARS_FADE_HOURS).coerceIn(0f, 1f)
            else -> 0f
        }


        val o = overcast.coerceIn(0f, 1f)
        val overcastDim = 1f - 0.88f * o

        materialInstance.setParameter("starVisibility", vis * overcastDim)
    }


    fun applyMoonForHour(hour: Float, overcast: Float = 0f) {
        val dir = moonDirectionForHour(hour)
        materialInstance.setParameter("moonDir", dir[0], dir[1], dir[2])

        val horizonFade = ((dir[1] + 0.02f) / 0.05f).coerceIn(0f, 1f)


        val o = overcast.coerceIn(0f, 1f)
        val overcastDim = 1f - 0.85f * o

        materialInstance.setParameter("moonVisible", horizonFade * overcastDim)


    }
}

object SunConfig {
    const val RADIUS_DEG = 2.0f
    const val INTENSITY = 8.0f
    const val GLOW = 0.5f

    val SUN_DIR = floatArrayOf(0.0f, 0.250f, -0.918f)
    val SKY_ZENITH = floatArrayOf(0.014f, 0.349f, 0.823f)
    val SKY_HORIZON = floatArrayOf(0.737f, 1.000f, 0.979f)
}


private val OVERCAST_DAY_ZENITH_LINEAR = srgbToLinear(0x6d7278)
private val OVERCAST_DAY_HORIZON_LINEAR = srgbToLinear(0x2986ff)


private val OVERCAST_NIGHT_ZENITH_LINEAR = srgbToLinear(0x00152e)
private val OVERCAST_NIGHT_HORIZON_LINEAR = srgbToLinear(0x000b19)

private val OVERCAST_SUN_LINEAR = srgbToLinear(0xE6DCD2)

const val MOONRISE_HOUR = 18.25f
const val MOONSET_HOUR = 5.75f
private const val MOON_NIGHT_LENGTH = (24f - MOONRISE_HOUR) + MOONSET_HOUR
const val STARS_START_HOUR = 19.0f
const val STARS_END_HOUR = 5.0f
private const val STARS_FADE_HOURS = 0.5f
private const val MOON_MAX_AZIMUTH_DEG = 11.0f
private const val MOON_MAX_ELEVATION_DEG = 16.0f

object MoonConfig {
    const val RADIUS_DEG = 1.5f
    const val INTENSITY = 1.3f
    val COLOR_LINEAR = srgbToLinear(0xEAF2F5)

}


private val OVERCAST_ZENITH_LINEAR = srgbToLinear(0x6d7278)
private val OVERCAST_HORIZON_LINEAR = srgbToLinear(0x2986ff)

fun skyColorsForHour(hour: Float, overcast: Float = 0f): Pair<FloatArray, FloatArray> {
    val h = ((hour % 24f) + 24f) % 24f
    var i = 0
    while (i < SKY_KEYFRAMES.size - 1 && h > SKY_KEYFRAMES[i + 1].hour) i++
    val a = SKY_KEYFRAMES[i]
    val b = SKY_KEYFRAMES[i + 1]
    val t = if (b.hour > a.hour) (h - a.hour) / (b.hour - a.hour) else 0f


    var zenith = lerpRgb(srgbToLinear(a.zenithSrgb), srgbToLinear(b.zenithSrgb), t)
    var horizon = lerpRgb(srgbToLinear(a.horizonSrgb), srgbToLinear(b.horizonSrgb), t)

    val o = overcast.coerceIn(0f, 1f)
    if (o > 0.001f) {


        val nightFactor = when {
            h >= 21.0f || h <= 4.0f -> 1.0f
            h > 19.0f && h < 21.0f -> (h - 19.0f) / 2.0f
            h > 4.0f && h < 6.0f -> 1.0f - (h - 4.0f) / 2.0f
            else -> 0.0f
        }.coerceIn(0f, 1f)


        val overcastZenith =
            lerpRgb(OVERCAST_DAY_ZENITH_LINEAR, OVERCAST_NIGHT_ZENITH_LINEAR, nightFactor)
        val overcastHorizon =
            lerpRgb(OVERCAST_DAY_HORIZON_LINEAR, OVERCAST_NIGHT_HORIZON_LINEAR, nightFactor)

        zenith = lerpRgb(zenith, overcastZenith, o)
        horizon = lerpRgb(horizon, overcastHorizon, o)
    }
    return zenith to horizon
}


fun moonDirectionForHour(hour: Float): FloatArray {
    val h = ((hour % 24f) + 24f) % 24f


    val sinceRise = if (h >= MOONRISE_HOUR) {
        h - MOONRISE_HOUR
    } else {
        h + (24f - MOONRISE_HOUR)
    }

    val phase = (sinceRise / MOON_NIGHT_LENGTH).toDouble()
    val theta = PI * phase

    val azimuth = toRadians(MOON_MAX_AZIMUTH_DEG.toDouble()) * cos(theta)
    val elevation = toRadians(MOON_MAX_ELEVATION_DEG.toDouble()) * sin(theta)

    val cosE = cos(elevation).toFloat()
    return floatArrayOf(
        cosE * sin(azimuth).toFloat(),
        sin(elevation).toFloat(),
        -cosE * cos(azimuth).toFloat()
    )
}


const val SUNRISE_HOUR = 6.0f
const val SUNSET_HOUR = 18.0f

private const val SUN_MAX_AZIMUTH_DEG = 11.0f
private const val SUN_MAX_ELEVATION_DEG = 17.5f
private val ELEVATION_FULL_NOON = (12.0 * PI / 180.0).toFloat()


private val SUN_HORIZON_LINEAR = srgbToLinear(0xFF7E33)
private val SUN_NOON_LINEAR = srgbToLinear(0xFFFAF0)


private val SUN_PATH_TILT = 25.0 * PI / 180.0

fun sunDirectionForHour(hour: Float): FloatArray {
    val phase = ((hour - SUNRISE_HOUR) / (SUNSET_HOUR - SUNRISE_HOUR)).toDouble()
    val theta = PI * phase

    val azimuth = toRadians(SUN_MAX_AZIMUTH_DEG.toDouble()) * cos(theta)
    val elevation = toRadians(SUN_MAX_ELEVATION_DEG.toDouble()) * sin(theta)

    val cosE = cos(elevation).toFloat()
    return floatArrayOf(
        cosE * sin(azimuth).toFloat(),
        sin(elevation).toFloat(),
        -cosE * cos(azimuth).toFloat()
    )

}


private data class SkyKeyframe(
    val hour: Float,
    val zenithSrgb: Int,
    val horizonSrgb: Int
)

private val SKY_KEYFRAMES = listOf(
    SkyKeyframe(0.0f, 0x020215, 0x061030),
    SkyKeyframe(4.5f, 0x0A0F2D, 0x3C2840),
    SkyKeyframe(6.0f, 0x28509F, 0xFF8C3C),
    SkyKeyframe(8.0f, 0x4682D2, 0xAAC8E6),
    SkyKeyframe(12.0f, 0x326EDC, 0x96C8F0),
    SkyKeyframe(15.0f, 0x3C78D2, 0xA0C8E6),
    SkyKeyframe(18.0f, 0x324696, 0xFF7832),
    SkyKeyframe(19.5f, 0x191946, 0x783C5A),
    SkyKeyframe(21.0f, 0x020215, 0x061030),
    SkyKeyframe(24.0f, 0x020215, 0x061030)
)


fun skyColorsForHour(hour: Float): Pair<FloatArray, FloatArray> {
    val h = ((hour % 24f) + 24f) % 24f

    var i = 0
    while (i < SKY_KEYFRAMES.size - 1 && h > SKY_KEYFRAMES[i + 1].hour) {
        i++
    }

    val a = SKY_KEYFRAMES[i]
    val b = SKY_KEYFRAMES[i + 1]
    val t = if (b.hour > a.hour) (h - a.hour) / (b.hour - a.hour) else 0f

    val zenith = lerpRgb(srgbToLinear(a.zenithSrgb), srgbToLinear(b.zenithSrgb), t)
    val horizon = lerpRgb(srgbToLinear(a.horizonSrgb), srgbToLinear(b.horizonSrgb), t)

    return zenith to horizon
}

private fun lerpRgb(a: FloatArray, b: FloatArray, t: Float): FloatArray = floatArrayOf(
    a[0] + (b[0] - a[0]) * t,
    a[1] + (b[1] - a[1]) * t,
    a[2] + (b[2] - a[2]) * t
)


private fun srgbToLinear(rgb: Int): FloatArray {
    fun channel(v: Int): Float {
        val c = v / 255f
        return if (c <= 0.04045f) {
            c / 12.92f
        } else {
            ((c + 0.055f) / 1.055f).pow(2.4f)
        }
    }
    return floatArrayOf(
        channel((rgb shr 16) and 0xFF),
        channel((rgb shr 8) and 0xFF),
        channel(rgb and 0xFF)
    )
}


fun loadMoonTexture(assets: AssetManager, engine: Engine): Texture {
    val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = assets.open("textures/moon.png").use {
        BitmapFactory.decodeStream(it, null, options)!!
    }

    val square = cropMoonSquare(bitmap)
    if (square !== bitmap) bitmap.recycle()

    val texture = Texture.Builder()
        .width(square.width)
        .height(square.height)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8)
        .build(engine)

    val buffer = ByteBuffer.allocateDirect(square.byteCount).order(ByteOrder.nativeOrder())
    square.copyPixelsToBuffer(buffer)
    buffer.rewind()

    texture.setImage(
        engine, 0,
        Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE)
    )
    square.recycle()
    return texture
}


private fun cropMoonSquare(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val colCount = IntArray(w)
    val rowCount = IntArray(h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val c = pixels[row + x]
            val lum = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
            if (lum > 60) {
                colCount[x]++
                rowCount[y]++
            }
        }
    }


    val minCol = (h * 0.02).toInt().coerceAtLeast(4)
    val minRow = (w * 0.02).toInt().coerceAtLeast(4)

    var minX = -1;
    var maxX = -1
    for (x in 0 until w) if (colCount[x] > minCol) {
        if (minX < 0) minX = x; maxX = x
    }
    var minY = -1;
    var maxY = -1
    for (y in 0 until h) if (rowCount[y] > minRow) {
        if (minY < 0) minY = y; maxY = y
    }
    if (minX < 0 || minY < 0) return bitmap

    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    var side = maxOf(maxX - minX, maxY - minY) + 16
    side = minOf(side, minOf(w, h))

    val left = (cx - side / 2).coerceIn(0, w - side)
    val top = (cy - side / 2).coerceIn(0, h - side)
    return Bitmap.createBitmap(bitmap, left, top, side, side)
}


fun buildSunSkybox(
    engine: Engine,
    payload: ByteBuffer,
    sunR: Float,
    sunG: Float,
    sunB: Float,
    moonTexture: Texture
): SunSkybox {
    val material = Material.Builder().payload(payload, payload.remaining()).build(engine)
    val mi = material.createInstance()

    mi.setParameter(
        "sunDir",
        SunConfig.SUN_DIR[0],
        SunConfig.SUN_DIR[1],
        SunConfig.SUN_DIR[2]
    )
    mi.setParameter("sunRadiusDeg", SunConfig.RADIUS_DEG)
    mi.setParameter("sunIntensity", SunConfig.INTENSITY)
    mi.setParameter("glowStrength", SunConfig.GLOW)
    mi.setParameter("sunVisible", 1.0f)
    mi.setParameter("sunColor", sunR, sunG, sunB)
    val moonSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE
    )
    mi.setParameter("moonTexture", moonTexture, moonSampler)
    mi.setParameter("moonDir", 0.0f, -1.0f, 0.0f)
    mi.setParameter("moonRadiusDeg", MoonConfig.RADIUS_DEG)
    mi.setParameter("moonIntensity", MoonConfig.INTENSITY)
    mi.setParameter("moonVisible", 0.0f)
    mi.setParameter("starVisibility", 0.0f)
    mi.setParameter(
        "moonColor",
        MoonConfig.COLOR_LINEAR[0],
        MoonConfig.COLOR_LINEAR[1],
        MoonConfig.COLOR_LINEAR[2]
    )
    mi.setParameter(
        "skyZenith",
        SunConfig.SKY_ZENITH[0],
        SunConfig.SKY_ZENITH[1],
        SunConfig.SKY_ZENITH[2]
    )
    mi.setParameter(
        "skyHorizon",
        SunConfig.SKY_HORIZON[0],
        SunConfig.SKY_HORIZON[1],
        SunConfig.SKY_HORIZON[2]
    )

    val vertexData = ByteBuffer.allocateDirect(3 * 16).order(ByteOrder.nativeOrder())
    val floatBuffer = vertexData.asFloatBuffer()
    floatBuffer.put(
        floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
            3.0f, -1.0f, 0.0f, 1.0f,
            -1.0f, 3.0f, 0.0f, 1.0f
        )
    )

    val vb = VertexBuffer.Builder()
        .vertexCount(3)
        .bufferCount(1)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            0,
            VertexBuffer.AttributeType.FLOAT4,
            0,
            16
        )
        .build(engine)
    vb.setBufferAt(engine, 0, vertexData)

    val entity = EntityManager.get().create()
    RenderableManager.Builder(1)
        .boundingBox(Box(-1f, -1f, -1f, 1f, 1f, 1f))
        .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb)
        .material(0, mi)
        .priority(0)
        .castShadows(false)
        .receiveShadows(false)
        .culling(false)
        .build(engine, entity)

    return SunSkybox(entity, vb, material, mi, moonTexture)
}
