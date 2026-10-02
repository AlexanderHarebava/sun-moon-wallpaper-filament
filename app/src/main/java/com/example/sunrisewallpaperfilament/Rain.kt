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
import kotlin.random.Random

private const val MAX_RAIN_PARTICLES = 300


private const val TAN_HALF_FOV = 0.41421356237f

private const val PARTICLE_BASE_ALPHA = 0.4f

internal class RainParticle(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = -1f,
    var speed: Float = 1f,
    var length: Float = 0.2f,
    var width: Float = 0.01f
)

class RainController internal constructor(
    private val engine: Engine,
    private val scene: Scene,
    private val dropsMaterial: Material,
    private val dropsMaterialInstance: MaterialInstance,
    private val dropsVertexBuffer: VertexBuffer,
    private val dropsEntity: Int,
    private val particleMaterial: Material,
    private val particleMaterialInstance: MaterialInstance,
    private val particleVertexBuffer: VertexBuffer,
    private val particleIndexBuffer: IndexBuffer,
    private val particleEntity: Int,
    private val particles: Array<RainParticle>
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
    private val wind = 0.14f
    private val random = Random(987654321L)


    private val cpuVertices: ByteBuffer =
        ByteBuffer.allocateDirect(MAX_RAIN_PARTICLES * 4 * 20)
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
        dropsMaterialInstance.setParameter("aspect", aspect)


        particles.forEach { resetParticle(it, initial = true) }
    }

    fun frameUpdate(dt: Float) {
        if (abs(opacity - targetOpacity) > 0.0005f) {
            opacity += (targetOpacity - opacity) * minOf(1f, dt * 2.0f)
        } else {
            opacity = targetOpacity
        }

        time += dt

        dropsMaterialInstance.setParameter("time", time)
        dropsMaterialInstance.setParameter("intensity", opacity)
        particleMaterialInstance.setParameter("globalAlpha", opacity * PARTICLE_BASE_ALPHA)

        if (opacity <= 0.001f && targetOpacity <= 0.001f) {
            return
        }

        cpuVerticesFloat.clear()

        for (p in particles) {
            p.y -= p.speed * dt
            p.x += wind * dt

            val halfH = TAN_HALF_FOV * -p.z
            val halfW = halfH * aspect

            if (p.y - p.length < -halfH * 1.15f) {
                resetParticle(p, initial = false)
            } else {
                if (p.x > halfW + p.width * 2f) {
                    p.x = -halfW - p.width
                } else if (p.x < -halfW - p.width * 2f) {
                    p.x = halfW + p.width
                }
            }

            val x0 = p.x - p.width
            val x1 = p.x + p.width
            val y0 = p.y
            val y1 = p.y - p.length
            val z = p.z


            putVertex(x0, y0, z, 0f, 1f)
            putVertex(x1, y0, z, 1f, 1f)
            putVertex(x1, y1, z, 1f, 0f)
            putVertex(x0, y1, z, 0f, 0f)
        }

        cpuVertices.position(0)
        cpuVertices.limit(cpuVertices.capacity())

        particleVertexBuffer.setBufferAt(engine, 0, cpuVertices)
    }

    fun destroy() {
        scene.removeEntity(dropsEntity)
        scene.removeEntity(particleEntity)

        engine.destroyEntity(dropsEntity)
        engine.destroyEntity(particleEntity)

        engine.destroyMaterialInstance(dropsMaterialInstance)
        engine.destroyMaterialInstance(particleMaterialInstance)
        engine.destroyMaterial(dropsMaterial)
        engine.destroyMaterial(particleMaterial)
        engine.destroyVertexBuffer(dropsVertexBuffer)
        engine.destroyVertexBuffer(particleVertexBuffer)
        engine.destroyIndexBuffer(particleIndexBuffer)

        EntityManager.get().destroy(dropsEntity)
        EntityManager.get().destroy(particleEntity)
    }

    private fun putVertex(x: Float, y: Float, z: Float, u: Float, v: Float) {
        cpuVerticesFloat.put(x)
        cpuVerticesFloat.put(y)
        cpuVerticesFloat.put(z)
        cpuVerticesFloat.put(u)
        cpuVerticesFloat.put(v)
    }

    private fun resetParticle(p: RainParticle, initial: Boolean) {
        val z = -(2.0f + random.nextFloat() * 9.0f)
        val depthScale = (-z / 6.0f).coerceIn(0.5f, 1.8f)

        p.z = z
        p.speed = (3.2f + random.nextFloat() * 5.0f) * depthScale
        p.length = (0.04f + random.nextFloat() * 0.04f) * depthScale
        p.width = (0.006f + random.nextFloat() * 0.014f) * depthScale

        val halfH = TAN_HALF_FOV * -z
        val halfW = halfH * aspect.coerceAtLeast(0.1f)

        p.x = (random.nextFloat() * 2f - 1f) * halfW

        p.y = if (initial) {
            (random.nextFloat() * 2f - 1f) * halfH
        } else {
            halfH * (1.05f + random.nextFloat() * 0.3f) + p.length
        }
    }
}

fun createRain(engine: Engine, scene: Scene, assets: AssetManager): RainController {

    val dropsPayload = readRainAsset(assets, "materials/rain_drops.filamat")
    val dropsMaterial = Material.Builder()
        .payload(dropsPayload, dropsPayload.remaining())
        .build(engine)

    val dropsMaterialInstance = dropsMaterial.createInstance()
    dropsMaterialInstance.setParameter("time", 0f)
    dropsMaterialInstance.setParameter("intensity", 0f)
    dropsMaterialInstance.setParameter("aspect", 1f)

    val dropsVertexBuffer = buildRainFullscreenTriangle(engine)

    val dropsEntity = EntityManager.get().create()

    RenderableManager.Builder(1)
        .boundingBox(Box(-1f, -1f, -1f, 1f, 1f, 1f))
        .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, dropsVertexBuffer)
        .material(0, dropsMaterialInstance)
        .priority(100)
        .castShadows(false)
        .receiveShadows(false)
        .culling(false)
        .build(engine, dropsEntity)


    val particlePayload = readRainAsset(assets, "materials/rain_particles.filamat")
    val particleMaterial = Material.Builder()
        .payload(particlePayload, particlePayload.remaining())
        .build(engine)

    val particleMaterialInstance = particleMaterial.createInstance()
    particleMaterialInstance.setParameter("color", 0.74f, 0.82f, 0.90f)
    particleMaterialInstance.setParameter("globalAlpha", 0f)

    val (particleVertexBuffer, particleIndexBuffer) =
        buildRainParticleGeometry(engine, MAX_RAIN_PARTICLES)

    val particleEntity = EntityManager.get().create()

    RenderableManager.Builder(1)
        .boundingBox(Box(-60f, -60f, -20f, 60f, 60f, 0f))
        .geometry(
            0,
            RenderableManager.PrimitiveType.TRIANGLES,
            particleVertexBuffer,
            particleIndexBuffer
        )
        .material(0, particleMaterialInstance)
        .priority(90)
        .castShadows(false)
        .receiveShadows(false)
        .culling(false)
        .build(engine, particleEntity)

    scene.addEntity(particleEntity)

    val particles = Array(MAX_RAIN_PARTICLES) { RainParticle() }

    return RainController(
        engine,
        scene,
        dropsMaterial,
        dropsMaterialInstance,
        dropsVertexBuffer,
        dropsEntity,
        particleMaterial,
        particleMaterialInstance,
        particleVertexBuffer,
        particleIndexBuffer,
        particleEntity,
        particles
    )
}

private fun buildRainFullscreenTriangle(engine: Engine): VertexBuffer {
    val data = ByteBuffer.allocateDirect(3 * 16).order(ByteOrder.nativeOrder())

    data.asFloatBuffer().put(
        floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
            3.0f, -1.0f, 0.0f, 1.0f,
            -1.0f, 3.0f, 0.0f, 1.0f
        )
    )

    data.rewind()

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

    vb.setBufferAt(engine, 0, data)

    return vb
}

private fun buildRainParticleGeometry(
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

private fun readRainAsset(assets: AssetManager, assetName: String): ByteBuffer {
    assets.openFd(assetName).use { fd ->
        val input = fd.createInputStream()
        val dst = ByteBuffer.allocate(fd.length.toInt())
        val src = Channels.newChannel(input)
        src.read(dst)
        src.close()
        return dst.apply { rewind() }
    }
}