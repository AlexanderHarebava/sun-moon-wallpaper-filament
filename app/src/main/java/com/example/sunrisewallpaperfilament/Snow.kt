package com.example.sunrisewallpaperfilament

import android.content.res.AssetManager
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private const val MAX_SNOW_PARTICLES = 500

private const val TAN_HALF_FOV = 0.41421356237f

private const val SNOW_BASE_ALPHA = 0.50f

internal class SnowParticle(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = -5f,
    var speed: Float = 0.45f,
    var radius: Float = 0.014f,
    var phase: Float = 0f
)

class SnowController internal constructor(
    private val engine: Engine,
    private val scene: Scene,
    private val material: Material,
    private val materialInstance: MaterialInstance,
    private val vertexBuffer: VertexBuffer,
    private val indexBuffer: IndexBuffer,
    private val entity: Int,
    private val particles: Array<SnowParticle>
) {
    var enabled: Boolean = false
        set(value) {
            field = value
            targetOpacity = if (value) 1f else 0f
        }

    private var targetOpacity = 0f
    private var opacity = 0f
    private var time = 0f
    private var aspect = 9f / 16f
    private val random = Random(345678901L)


    private val cpuVertices: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_SNOW_PARTICLES * 4 * 20)
            .order(ByteOrder.nativeOrder())

    private val cpuVerticesFloat = cpuVertices.asFloatBuffer()

    init {
        particles.forEach { resetParticle(it, initial = true) }
    }

    fun setAspect(newAspect: Float) {
        val a = if (newAspect.isNaN() || newAspect <= 0.1f || newAspect > 10f) {
            1f
        } else {
            newAspect
        }

        if (abs(aspect - a) < 0.0001f) return

        aspect = a


        particles.forEach { resetParticle(it, initial = true) }
    }

    fun frameUpdate(dt: Float) {
        if (abs(opacity - targetOpacity) > 0.0005f) {
            opacity += (targetOpacity - opacity) * minOf(1f, dt * 2.0f)
        } else {
            opacity = targetOpacity
        }

        time += dt

        materialInstance.setParameter("globalAlpha", opacity * SNOW_BASE_ALPHA)

        if (opacity <= 0.001f && targetOpacity <= 0.001f) {
            return
        }

        cpuVerticesFloat.clear()

        for (p in particles) {

            val sway = sin((time * 0.7f + p.phase).toDouble()).toFloat() * 0.16f

            p.y -= p.speed * dt
            p.x += (0.04f + sway) * dt

            val halfH = TAN_HALF_FOV * -p.z
            val halfW = halfH * aspect

            val bottom = p.y - p.radius

            if (bottom < -halfH * 1.15f) {
                resetParticle(p, initial = false)
            } else {
                if (p.x > halfW + p.radius * 2f) {
                    p.x = -halfW - p.radius
                } else if (p.x < -halfW - p.radius * 2f) {
                    p.x = halfW + p.radius
                }
            }


            val x0 = p.x - p.radius
            val x1 = p.x + p.radius
            val y0 = p.y + p.radius
            val y1 = p.y - p.radius
            val z = p.z

            putVertex(x0, y0, z, 0f, 1f)
            putVertex(x1, y0, z, 1f, 1f)
            putVertex(x1, y1, z, 1f, 0f)
            putVertex(x0, y1, z, 0f, 0f)
        }

        cpuVertices.position(0)
        cpuVertices.limit(cpuVertices.capacity())
        vertexBuffer.setBufferAt(engine, 0, cpuVertices)
    }

    fun destroy() {
        scene.removeEntity(entity)
        engine.destroyEntity(entity)
        engine.destroyMaterialInstance(materialInstance)
        engine.destroyMaterial(material)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        EntityManager.get().destroy(entity)
    }

    private fun putVertex(x: Float, y: Float, z: Float, u: Float, v: Float) {
        cpuVerticesFloat.put(x)
        cpuVerticesFloat.put(y)
        cpuVerticesFloat.put(z)
        cpuVerticesFloat.put(u)
        cpuVerticesFloat.put(v)
    }

    private fun resetParticle(p: SnowParticle, initial: Boolean) {
        val z = -(2.0f + random.nextFloat() * 8.0f)
        val depthScale = (-z / 6.0f).coerceIn(0.6f, 1.6f)

        p.z = z


        p.speed = (0.22f + random.nextFloat() * 0.55f) * depthScale


        p.radius = (0.0010f + random.nextFloat() * 0.013f) * depthScale


        p.phase = random.nextFloat() * 6.2831855f

        val halfH = TAN_HALF_FOV * -z
        val halfW = halfH * aspect.coerceAtLeast(0.1f)

        p.x = (random.nextFloat() * 2f - 1f) * halfW

        p.y = if (initial) {
            (random.nextFloat() * 2f - 1f) * halfH
        } else {
            halfH * (1.05f + random.nextFloat() * 0.25f) + p.radius * 2f
        }
    }
}

fun createSnow(engine: Engine, scene: Scene, assets: AssetManager): SnowController {
    val payload = readSnowAsset(assets, "materials/snow_particles.filamat")

    val material = Material.Builder()
        .payload(payload, payload.remaining())
        .build(engine)

    val materialInstance = material.createInstance()
    materialInstance.setParameter("color", 0.97f, 0.99f, 1.0f)
    materialInstance.setParameter("globalAlpha", 0f)

    val (vertexBuffer, indexBuffer) = buildSnowParticleGeometry(engine, MAX_SNOW_PARTICLES)

    val entity = EntityManager.get().create()

    RenderableManager.Builder(1)
        .boundingBox(Box(-60f, -60f, -20f, 60f, 60f, 0f))
        .geometry(
            0,
            RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer,
            indexBuffer
        )
        .material(0, materialInstance)
        .priority(95)
        .castShadows(false)
        .receiveShadows(false)
        .culling(false)
        .build(engine, entity)

    scene.addEntity(entity)

    val particles = Array(MAX_SNOW_PARTICLES) { SnowParticle() }

    return SnowController(
        engine,
        scene,
        material,
        materialInstance,
        vertexBuffer,
        indexBuffer,
        entity,
        particles
    )
}

private fun buildSnowParticleGeometry(
    engine: Engine,
    count: Int
): Pair<VertexBuffer, IndexBuffer> {
    val vertexCount = count * 4
    val indexCount = count * 6

    val vb = VertexBuffer.Builder()
        .vertexCount(vertexCount)
        .bufferCount(1)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION,
            0,
            VertexBuffer.AttributeType.FLOAT3,
            0,
            20
        )
        .attribute(
            VertexBuffer.VertexAttribute.UV0,
            0,
            VertexBuffer.AttributeType.FLOAT2,
            12,
            20
        )
        .build(engine)

    val initialVertices = ByteBuffer.allocateDirect(vertexCount * 20)
        .order(ByteOrder.nativeOrder())

    initialVertices.rewind()
    vb.setBufferAt(engine, 0, initialVertices)

    val indices = ShortArray(indexCount)
    var idx = 0

    for (i in 0 until count) {
        val base = (i * 4).toShort()

        indices[idx++] = base
        indices[idx++] = (base + 1).toShort()
        indices[idx++] = (base + 2).toShort()

        indices[idx++] = base
        indices[idx++] = (base + 2).toShort()
        indices[idx++] = (base + 3).toShort()
    }

    val indexData = ByteBuffer.allocateDirect(indexCount * 2)
        .order(ByteOrder.nativeOrder())

    indexData.asShortBuffer().put(indices)
    indexData.rewind()

    val ib = IndexBuffer.Builder()
        .indexCount(indexCount)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(engine)

    ib.setBuffer(engine, indexData)

    return vb to ib
}

private fun readSnowAsset(assets: AssetManager, assetName: String): ByteBuffer {
    assets.openFd(assetName).use { fd ->
        val input = fd.createInputStream()
        val dst = ByteBuffer.allocate(fd.length.toInt())
        val src = Channels.newChannel(input)

        src.read(dst)
        src.close()

        return dst.apply { rewind() }
    }
}