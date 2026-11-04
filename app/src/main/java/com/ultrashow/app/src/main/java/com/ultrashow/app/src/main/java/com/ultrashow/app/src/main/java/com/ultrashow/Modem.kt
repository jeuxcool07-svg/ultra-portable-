package com.ultrashow

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

object Modem {
    const val SAMPLE_RATE = 44100
    const val BIT_MS = 100
    const val CHUNK_MS = 10
    const val CHUNK = SAMPLE_RATE * CHUNK_MS / 1000
    const val CHUNKS_PER_BIT = BIT_MS / CHUNK_MS
    const val FRAME_BITS = 16
    const val FRAME_CHUNKS = FRAME_BITS * CHUNKS_PER_BIT

    val MODE_NAMES = arrayOf("Audible (test)", "Ultrason 17-18 kHz", "Ultrason 19-20 kHz")
    val FREQS = arrayOf(
        intArrayOf(2000, 3000),
        intArrayOf(17000, 18000),
        intArrayOf(19000, 20000)
    )

    // 16 bits : 10101010 (preambule) + 4 bits du message + 4 bits inverses (controle)
    private fun frameBits(id: Int): IntArray {
        val bits = IntArray(FRAME_BITS)
        for (i in 0 until 8) bits[i] = if (i % 2 == 0) 1 else 0
        for (i in 0 until 4) {
            val b = (id shr (3 - i)) and 1
            bits[8 + i] = b
            bits[12 + i] = 1 - b
        }
        return bits
    }

    // Fabrique le son a jouer pour le message numero "id" dans le mode "mode"
    fun encode(mode: Int, id: Int): ShortArray {
        val f0 = FREQS[mode][0].toDouble()
        val f1 = FREQS[mode][1].toDouble()
        val bits = frameBits(id)
        val bitSamples = SAMPLE_RATE * BIT_MS / 1000
        val pad = SAMPLE_RATE * 300 / 1000
        val toneLen = bits.size * bitSamples
        val fade = SAMPLE_RATE * 20 / 1000
        val out = ShortArray(pad + toneLen + pad)
        var phase = 0.0
        for (b in bits.indices) {
            val step = 2.0 * PI * (if (bits[b] == 1) f1 else f0) / SAMPLE_RATE
            for (k in 0 until bitSamples) {
                val n = b * bitSamples + k
                var amp = 0.8
                if (n < fade) amp *= n.toDouble() / fade
                if (n > toneLen - fade) amp *= (toneLen - n).toDouble() / fade
                out[pad + n] = (sin(phase) * amp * 32767.0).toInt().toShort()
                phase += step
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
        }
        return out
    }
}

// Ecoute le son du micro et previent quand un message complet est reconnu.
class Decoder(private val onMessage: (Int, Int) -> Unit) {
    private val modes = Modem.FREQS.size
    private val nf = modes * 2
    private val coeff = DoubleArray(nf)
    private val ring = Array(nf) { DoubleArray(Modem.FRAME_CHUNKS) }
    private val levels = DoubleArray(modes)
    private val chunk = DoubleArray(Modem.CHUNK)
    private var chunkFill = 0
    private var pos = 0
    private var filled = 0

    init {
        for (m in 0 until modes) {
            for (k in 0..1) {
                coeff[m * 2 + k] = 2.0 * cos(2.0 * PI * Modem.FREQS[m][k] / Modem.SAMPLE_RATE)
            }
        }
    }

    @Synchronized
    fun reset() {
        pos = 0
        filled = 0
        chunkFill = 0
        for (r in ring) r.fill(0.0)
        levels.fill(0.0)
    }

    // Niveau recu (en dB) pour un mode : plus c'est haut, mieux le micro entend
    @Synchronized
    fun levelDb(mode: Int): Double = 10.0 * log10(levels[mode] + 1e-12)

    @Synchronized
    fun feed(samples: ShortArray, len: Int) {
        for (i in 0 until len) {
            chunk[chunkFill++] = samples[i] / 32768.0
            if (chunkFill == Modem.CHUNK) {
                chunkFill = 0
                processChunk()
            }
        }
    }

    private fun processChunk() {
        val norm = Modem.CHUNK.toDouble() * Modem.CHUNK
        for (f in 0 until nf) {
            val c = coeff[f]
            var s1 = 0.0
            var s2 = 0.0
            for (x in chunk) {
                val s = x + c * s1 - s2
                s2 = s1
                s1 = s
            }
            ring[f][pos] = (s1 * s1 + s2 * s2 - c * s1 * s2) / norm
        }
        for (m in 0 until modes) {
            val cur = max(ring[2 * m][pos], ring[2 * m + 1][pos])
            levels[m] = max(cur, levels[m] * 0.97)
        }
        pos = (pos + 1) % Modem.FRAME_CHUNKS
        if (filled < Modem.FRAME_CHUNKS) filled++
        if (filled < Modem.FRAME_CHUNKS) return
        for (m in 0 until modes) {
            val id = tryDecode(m)
            if (id >= 0) {
                filled = 0
                onMessage(id, m)
                return
            }
        }
    }

    private fun tryDecode(m: Int): Int {
        val e0 = ring[2 * m]
        val e1 = ring[2 * m + 1]
        var word = 0
        for (b in 0 until Modem.FRAME_BITS) {
            var s0 = 0.0
            var s1 = 0.0
            for (j in 0 until Modem.CHUNKS_PER_BIT) {
                val idx = (pos + b * Modem.CHUNKS_PER_BIT + j) % Modem.FRAME_CHUNKS
                s0 += e0[idx]
                s1 += e1[idx]
            }
            val bit = when {
                s1 > MARGIN * s0 && s1 > FLOOR -> 1
                s0 > MARGIN * s1 && s0 > FLOOR -> 0
                else -> return -1
            }
            word = (word shl 1) or bit
        }
        if ((word shr 8) != 0xAA) return -1
        val id = (word shr 4) and 0xF
        val check = word and 0xF
        return if (check == (id xor 0xF)) id else -1
    }

    companion object {
        private const val MARGIN = 3.0
        private const val FLOOR = 1e-8
    }
}
