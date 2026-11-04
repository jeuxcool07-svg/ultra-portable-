package com.ultrashow

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

object Modem {
    const val SAMPLE_RATE = 44100
    const val CHUNK_MS = 10
    const val CHUNK = SAMPLE_RATE * CHUNK_MS / 1000
    const val MAX_BYTES = 60
    const val HEADER_BITS = 24
    const val MAX_CPB = 12
    const val RING = HEADER_BITS * MAX_CPB
    const val REC = (8 * MAX_BYTES + 16) * MAX_CPB + 32

    // Duree d'un bit, en blocs de 10 ms : rapide = 60 ms, lente = 120 ms
    val SPEED_CHUNKS = intArrayOf(6, 12)
    val SPEED_NAMES = arrayOf("Rapide", "Lente (plus sûr)")

    val MODE_NAMES = arrayOf("Audible (test)", "Ultrason 17-18 kHz", "Ultrason 19-20 kHz")
    val FREQS = arrayOf(
        intArrayOf(2000, 3000),
        intArrayOf(17000, 18000),
        intArrayOf(19000, 20000)
    )

    // Code de controle : permet de savoir si le message est arrive intact
    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (i in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    // Trame : 10101010 | longueur | longueur inversee | octets du message | CRC16
    private fun frameBits(payload: ByteArray): IntArray {
        val bits = ArrayList<Int>()
        fun put(value: Int, width: Int) {
            for (i in width - 1 downTo 0) bits.add((value shr i) and 1)
        }
        val n = payload.size
        put(0xAA, 8)
        put(n, 8)
        put(n xor 0xFF, 8)
        for (b in payload) put(b.toInt() and 0xFF, 8)
        put(crc16(payload), 16)
        return bits.toIntArray()
    }

    // Fabrique le son a jouer pour ce message
    fun encode(mode: Int, speed: Int, payload: ByteArray): ShortArray {
        val f0 = FREQS[mode][0].toDouble()
        val f1 = FREQS[mode][1].toDouble()
        val bits = frameBits(payload)
        val bitSamples = CHUNK * SPEED_CHUNKS[speed]
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

// Ecoute le son du micro et previent quand un message complet est recu.
// onMessage(texte, mode, ok) : ok = false si le message est arrive abime.
class Decoder(private val onMessage: (String, Int, Boolean) -> Unit) {
    private val modes = Modem.FREQS.size
    private val nf = modes * 2
    private val coeff = DoubleArray(nf)
    private val ring = Array(nf) { DoubleArray(Modem.RING) }
    private val levels = DoubleArray(modes)
    private val chunk = DoubleArray(Modem.CHUNK)
    private var chunkFill = 0
    private var pos = 0
    private var filled = 0

    // reception en cours
    private var receiving = false
    private var lockMode = 0
    private var lockCpb = 0
    private var lockLen = 0
    private var need = 1
    private var recCount = 0
    private val rec0 = DoubleArray(Modem.REC)
    private val rec1 = DoubleArray(Modem.REC)

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
        receiving = false
        recCount = 0
        for (r in ring) r.fill(0.0)
        levels.fill(0.0)
    }

    // Niveau recu (en dB) pour un mode : plus c'est haut, mieux le micro entend
    @Synchronized
    fun levelDb(mode: Int): Double = 10.0 * log10(levels[mode] + 1e-12)

    // -1 si rien n'est en cours, sinon avancement de la reception en %
    @Synchronized
    fun progress(): Int = if (receiving) minOf(100, 100 * recCount / need) else -1

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
        if (receiving) {
            rec0[recCount] = ring[2 * lockMode][pos]
            rec1[recCount] = ring[2 * lockMode + 1][pos]
            recCount++
        }
        pos = (pos + 1) % Modem.RING
        if (filled < Modem.RING) filled++
        if (receiving) {
            if (recCount >= need) finish()
            return
        }
        scanHeaders()
    }

    private fun scanHeaders() {
        for (s in Modem.SPEED_CHUNKS.indices) {
            val cpb = Modem.SPEED_CHUNKS[s]
            for (m in 0 until modes) {
                val len = tryHeader(m, cpb)
                if (len > 0) {
                    startReceiving(m, cpb, len)
                    return
                }
            }
        }
    }

    // Cherche "10101010 + longueur + longueur inversee" dans les derniers sons
    private fun tryHeader(m: Int, cpb: Int): Int {
        val span = Modem.HEADER_BITS * cpb
        if (filled < span) return -1
        val e0 = ring[2 * m]
        val e1 = ring[2 * m + 1]
        val start = (pos - span + Modem.RING) % Modem.RING
        var word = 0
        for (b in 0 until Modem.HEADER_BITS) {
            var s0 = 0.0
            var s1 = 0.0
            for (j in 0 until cpb) {
                val idx = (start + b * cpb + j) % Modem.RING
                s0 += e0[idx]
                s1 += e1[idx]
            }
            val floor = FLOOR * cpb
            val bit = when {
                s1 > MARGIN * s0 && s1 > floor -> 1
                s0 > MARGIN * s1 && s0 > floor -> 0
                else -> return -1
            }
            word = (word shl 1) or bit
        }
        if ((word shr 16) != 0xAA) return -1
        val len = (word shr 8) and 0xFF
        val chk = word and 0xFF
        if (chk != (len xor 0xFF)) return -1
        if (len < 1 || len > Modem.MAX_BYTES) return -1
        return len
    }

    private fun startReceiving(m: Int, cpb: Int, len: Int) {
        lockMode = m
        lockCpb = cpb
        lockLen = len
        need = PRE + MAXOFF + (8 * len + 16) * cpb
        for (i in 0 until PRE) {
            val idx = (pos - PRE + i + Modem.RING) % Modem.RING
            rec0[i] = ring[2 * m][idx]
            rec1[i] = ring[2 * m + 1][idx]
        }
        recCount = PRE
        receiving = true
    }

    private fun readBits(start: Int, cpb: Int, count: Int): IntArray {
        val bits = IntArray(count)
        for (b in 0 until count) {
            var s0 = 0.0
            var s1 = 0.0
            val base = start + b * cpb
            for (j in 0 until cpb) {
                s0 += rec0[base + j]
                s1 += rec1[base + j]
            }
            bits[b] = if (s1 > s0) 1 else 0
        }
        return bits
    }

    private fun value(bits: IntArray, from: Int, count: Int): Int {
        var v = 0
        for (i in 0 until count) v = (v shl 1) or bits[from + i]
        return v
    }

    // Le message est entierement recu : on lit les bits (en essayant quelques
    // petits decalages) jusqu'a ce que le code de controle soit bon.
    private fun finish() {
        val cpb = lockCpb
        val n = lockLen
        val total = 8 * n + 16
        var good: ByteArray? = null
        var fallback: ByteArray? = null
        for (o in OFFSETS) {
            val bits = readBits(PRE + o, cpb, total)
            val data = ByteArray(n) { value(bits, it * 8, 8).toByte() }
            if (fallback == null) fallback = data
            if (value(bits, 8 * n, 16) == Modem.crc16(data)) {
                good = data
                break
            }
        }
        val bytes = good ?: fallback ?: ByteArray(0)
        val mode = lockMode
        receiving = false
        filled = 0
        onMessage(String(bytes, Charsets.UTF_8), mode, good != null)
    }

    companion object {
        private const val MARGIN = 3.0
        private const val FLOOR = 1e-8
        private const val PRE = 4
        private const val MAXOFF = 4
        private val OFFSETS = intArrayOf(0, 1, -1, 2, -2, 3, -3, 4, -4)
    }
}
