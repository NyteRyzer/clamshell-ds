package com.clamshellds.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Small synthesised menu sounds in the spirit of the DSi menu (no copyrighted audio). */
class Sfx(context: Context, var enabled: Boolean) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val move: Int
    private val select: Int
    private val launch: Int
    private val back: Int

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        move = pool.load(wav(dir, "move", listOf(Note(1568.0, 0.035, 0.35))), 1)
        select = pool.load(wav(dir, "select", listOf(Note(1046.5, 0.05, 0.4), Note(1568.0, 0.07, 0.4))), 1)
        launch = pool.load(
            wav(dir, "launch", listOf(Note(784.0, 0.07, 0.4), Note(1046.5, 0.07, 0.4), Note(1318.5, 0.07, 0.4), Note(2093.0, 0.22, 0.35))), 1
        )
        back = pool.load(wav(dir, "back", listOf(Note(1046.5, 0.05, 0.35), Note(698.5, 0.08, 0.35))), 1)
    }

    fun move() = play(move)
    fun select() = play(select)
    fun launch() = play(launch)
    fun back() = play(back)

    private fun play(id: Int) {
        if (enabled) pool.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }

    fun release() = pool.release()

    private data class Note(val freq: Double, val seconds: Double, val volume: Double)

    private fun wav(dir: File, name: String, notes: List<Note>): String {
        val f = File(dir, "$name.wav")
        if (f.exists()) return f.absolutePath
        val rate = 44100
        val samples = ArrayList<Short>()
        for (n in notes) {
            val count = (n.seconds * rate).toInt()
            for (i in 0 until count) {
                val t = i.toDouble() / rate
                val env = exp(-t * 18.0) * (if (i < 80) i / 80.0 else 1.0)
                // soft bell: fundamental + octave partial
                val s = sin(2 * PI * n.freq * t) * 0.8 + sin(4 * PI * n.freq * t) * 0.2
                samples += (s * env * n.volume * Short.MAX_VALUE).toInt().toShort()
            }
        }
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        FileOutputStream(f).use { it.write(header.array()); it.write(data.array()) }
        return f.absolutePath
    }
}
