package com.example.sunrisewallpaperfilament

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import kotlin.math.abs
import kotlin.math.pow


const val CLOUD_DISTANCE = 20.0f


private const val BASE_CLOUD_WIDTH = 2.5f

data class CloudInstance(
    @Entity val entity: Int,
    val materialInstance: MaterialInstance,
    val width: Float,
    val height: Float,
    val y: Float,
    val speed: Float,
    var x: Float
)

class CloudController(
    private val engine: Engine,
    private val scene: Scene,
    private val material: Material,
    private val textures: List<Texture>,
    private val vertexBuffer: VertexBuffer,
    private val indexBuffer: IndexBuffer,
    val clouds: List<CloudInstance>
) {


    var enabled: Boolean = true
        set(value) {
            field = value
            targetOpacity = if (value) 1f else 0f
        }

    private var targetOpacity = 1f
    private var opacity = 0f
    private var tint = floatArrayOf(1f, 1f, 1f)
    private var timeFactor = 0.9f
    private var tintDirty = true

    private val OVERCAST_CLOUD_TINT_LINEAR = cloudSrgbToLinear(0x8A929B)


    fun applyTimeOfDay(hour: Float, overcast: Float = 0f) {
        val (t, f) = cloudTintForHour(hour)
        val o = overcast.coerceIn(0f, 1f)
        tint = if (o <= 0.001f) t else cloudLerpRgb(t, OVERCAST_CLOUD_TINT_LINEAR, o)
        timeFactor = f * (1f - 0.3f * o)
        tintDirty = true
    }


    fun frameUpdate(dtSeconds: Float, halfWidth: Float) {
        val tm = engine.transformManager

        for (c in clouds) {
            c.x += c.speed * dtSeconds
            val limit = halfWidth + c.width * 0.5f + 0.3f
            if (c.x > limit) c.x = -limit

            tm.setTransform(
                tm.getInstance(c.entity),
                floatArrayOf(
                    c.width, 0f, 0f, 0f,
                    0f, c.height, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    c.x, c.y, -CLOUD_DISTANCE, 1f
                )
            )
        }

        if (abs(opacity - targetOpacity) > 0.001f) {
            opacity += (targetOpacity - opacity) * minOf(1f, dtSeconds * 2f)
            tintDirty = true
        }

        if (tintDirty) {
            tintDirty = false
            val a = opacity * timeFactor
            for (c in clouds) {
                c.materialInstance.setParameter("tintOpacity", tint[0], tint[1], tint[2], a)
            }
        }
    }

    fun destroy() {
        for (c in clouds) {
            scene.removeEntity(c.entity)

            engine.destroyEntity(c.entity)
            engine.destroyMaterialInstance(c.materialInstance)
            EntityManager.get().destroy(c.entity)
        }
        for (t in textures) engine.destroyTexture(t)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyMaterial(material)
    }
}


private data class CloudDef(
    val asset: String,
    val svgW: Float,
    val svgH: Float,
    val scale: Float,
    val y: Float,
    val speed: Float,
    val startX: Float
)

private val CLOUD_DEFS = listOf(
    CloudDef(
        "clouds/object.svg", 120f, 50.3f, 1.00f, -0.6f,
        0.050f, 1.5f
    ),
    CloudDef(
        "clouds/objectsecond.svg", 120f, 60.0f, 0.5f, -1.6f,
        0.100f, 9.5f
    ),
    CloudDef(
        "clouds/objectthird.svg", 159f, 55.0f, 1.5f, -3.2f,
        0.070f, 4.0f
    ),
    CloudDef(
        "clouds/object.svg", 120f, 50.3f, 0.65f, 2.4f,
        0.080f, -1.0f
    ),
    CloudDef(
        "clouds/objectsecond.svg", 120f, 60.0f, 1.0f, 1.2f,
        0.130f, -4.5f
    )
)

fun createClouds(engine: Engine, scene: Scene, assets: AssetManager): CloudController {
    readAssetBytes(assets, "materials/clouds.filamat").let { payload ->
        val material = Material.Builder().payload(payload, payload.remaining()).build(engine)

        val sampler = TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.CLAMP_TO_EDGE
        )

        val (vb, ib) = buildCloudQuad(engine)
        val textureCache = mutableMapOf<String, Texture>()
        val clouds = mutableListOf<CloudInstance>()

        CLOUD_DEFS.forEachIndexed { i, def ->
            val tex = textureCache.getOrPut(def.asset) {
                val w = 512
                bitmapToTexture(engine, rasterizeSvg(assets, def.asset, def.svgW, def.svgH, w))
            }

            val mi = material.createInstance()
            mi.setParameter("cloudTexture", tex, sampler)
            mi.setParameter("tintOpacity", 1f, 1f, 1f, 0f)

            val entity = EntityManager.get().create()
            RenderableManager.Builder(1)
                .boundingBox(Box(-0.5f, -0.5f, -0.01f, 0.5f, 0.5f, 0.01f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
                .material(0, mi)
                .priority(10 + i)
                .castShadows(false)
                .receiveShadows(false)
                .culling(false)
                .build(engine, entity)
            scene.addEntity(entity)

            val width = BASE_CLOUD_WIDTH * def.scale
            clouds.add(
                CloudInstance(
                    entity, mi,
                    width, width * def.svgH / def.svgW,
                    def.y, def.speed, def.startX
                )
            )
        }

        return CloudController(
            engine,
            scene,
            material,
            textureCache.values.toList(),
            vb,
            ib,
            clouds
        )
    }
}


private data class CloudKey(val hour: Float, val colorSrgb: Int, val factor: Float)

private val CLOUD_KEYS = listOf(
    CloudKey(0.0f, 0x2A3550, 0.35f),
    CloudKey(4.5f, 0x3A3F5C, 0.40f),
    CloudKey(6.5f, 0xFFB48A, 0.75f),
    CloudKey(9.0f, 0xFFFFFF, 0.90f),
    CloudKey(15.0f, 0xFFFFFF, 0.90f),
    CloudKey(18.0f, 0xFFA07A, 0.80f),
    CloudKey(19.5f, 0x4A4A6A, 0.50f),
    CloudKey(21.0f, 0x2A3550, 0.35f),
    CloudKey(24.0f, 0x2A3550, 0.35f)
)

fun cloudTintForHour(hour: Float): Pair<FloatArray, Float> {
    val h = ((hour % 24f) + 24f) % 24f

    var i = 0
    while (i < CLOUD_KEYS.size - 1 && h > CLOUD_KEYS[i + 1].hour) i++

    val a = CLOUD_KEYS[i]
    val b = CLOUD_KEYS[i + 1]
    val t = if (b.hour > a.hour) (h - a.hour) / (b.hour - a.hour) else 0f

    val color = cloudLerpRgb(cloudSrgbToLinear(a.colorSrgb), cloudSrgbToLinear(b.colorSrgb), t)
    val factor = a.factor + (b.factor - a.factor) * t
    return color to factor
}

private fun cloudLerpRgb(a: FloatArray, b: FloatArray, t: Float): FloatArray = floatArrayOf(
    a[0] + (b[0] - a[0]) * t,
    a[1] + (b[1] - a[1]) * t,
    a[2] + (b[2] - a[2]) * t
)

private fun cloudSrgbToLinear(rgb: Int): FloatArray {
    fun channel(v: Int): Float {
        val c = v / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    return floatArrayOf(
        channel((rgb shr 16) and 0xFF),
        channel((rgb shr 8) and 0xFF),
        channel(rgb and 0xFF)
    )
}


private fun rasterizeSvg(
    assets: AssetManager,
    path: String,
    svgW: Float,
    svgH: Float,
    targetWidth: Int
): Bitmap {
    val svg = SVG.getFromAsset(assets, path)
    if (svg.documentViewBox == null) {
        svg.setDocumentViewBox(0f, 0f, svgW, svgH)
    }
    val targetHeight = (targetWidth * svgH / svgW).toInt()
    svg.setDocumentWidth(targetWidth.toFloat())
    svg.setDocumentHeight(targetHeight.toFloat())

    val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    svg.renderToCanvas(Canvas(bmp))
    return bmp
}

private fun bitmapToTexture(engine: Engine, bmp: Bitmap): Texture {
    val texture = Texture.Builder()
        .width(bmp.width)
        .height(bmp.height)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8)
        .build(engine)

    val buffer = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
    bmp.copyPixelsToBuffer(buffer)
    buffer.rewind()
    texture.setImage(
        engine, 0,
        Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE)
    )
    bmp.recycle()
    return texture
}


private fun buildCloudQuad(engine: Engine): Pair<VertexBuffer, IndexBuffer> {
    val vertexData = ByteBuffer.allocateDirect(4 * 20).order(ByteOrder.nativeOrder())
    vertexData.asFloatBuffer().put(
        floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 1f,
            0.5f, -0.5f, 0f, 1f, 1f,
            0.5f, 0.5f, 0f, 1f, 0f,
            -0.5f, 0.5f, 0f, 0f, 0f
        )
    )

    val vb = VertexBuffer.Builder()
        .vertexCount(4)
        .bufferCount(1)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            0,
            VertexBuffer.AttributeType.FLOAT3,
            0,
            20
        )
        .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 20)
        .build(engine)
    vb.setBufferAt(engine, 0, vertexData)

    val indexData = ByteBuffer.allocateDirect(6 * 2).order(ByteOrder.nativeOrder())
    indexData.asShortBuffer().put(shortArrayOf(0, 1, 2, 0, 2, 3))
    val ib = IndexBuffer.Builder()
        .indexCount(6)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(engine)
    ib.setBuffer(engine, indexData)

    return vb to ib
}

private fun readAssetBytes(assets: AssetManager, assetName: String): ByteBuffer {
    assets.openFd(assetName).use { fd ->
        val input = fd.createInputStream()
        val dst = ByteBuffer.allocate(fd.length.toInt())
        val src = Channels.newChannel(input)
        src.read(dst)
        src.close()
        return dst.apply { rewind() }
    }
}