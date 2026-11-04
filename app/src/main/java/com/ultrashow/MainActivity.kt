package com.ultrashow

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val messages = arrayOf(
        "Tout va bien",
        "J'arrive",
        "Ça va pas du tout",
        "Au secours",
        "Danger imminent"
    )

    private var mode = 0
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Decoder { id, m -> onReceived(id, m) }

    @Volatile private var sending = false
    @Volatile private var listening = false
    private var recordThread: Thread? = null

    private lateinit var modeButton: Button
    private lateinit var listenButton: Button
    private lateinit var statusText: TextView
    private lateinit var receivedText: TextView
    private lateinit var levelText: TextView

    private val refresher = object : Runnable {
        override fun run() {
            if (!listening) return
            val sb = StringBuilder("Niveau du son reçu :\n")
            for (m in 0 until Modem.FREQS.size) {
                val db = decoder.levelDb(m)
                val blocks = ((db + 100.0) / 8.0).toInt().coerceIn(0, 12)
                sb.append(Modem.MODE_NAMES[m].padEnd(19))
                sb.append("█".repeat(blocks))
                sb.append("░".repeat(12 - blocks))
                sb.append('\n')
            }
            levelText.text = sb.toString()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))

        root.addView(label("Ultra Show", 28f, "#FFFFFF"), params(0))
        root.addView(
            label(
                "Messages par le son, sans réseau. Mets le volume média au maximum sur le téléphone qui envoie.",
                14f, "#94A3B8"
            ),
            params(4)
        )

        root.addView(label("ENVOYER", 14f, "#38BDF8"), params(24))
        modeButton = makeButton(modeLabel(), "#475569") {
            mode = (mode + 1) % Modem.FREQS.size
            modeButton.text = modeLabel()
        }
        root.addView(modeButton, params(8))
        for (i in messages.indices) {
            root.addView(makeButton(messages[i], "#1D4ED8") { send(i) }, params(8))
        }

        root.addView(label("RECEVOIR", 14f, "#38BDF8"), params(28))
        listenButton = makeButton("Écouter", "#15803D") { toggleListen() }
        root.addView(listenButton, params(8))

        receivedText = label("Rien reçu pour l'instant", 22f, "#FACC15")
        root.addView(receivedText, params(16))

        levelText = label("", 13f, "#94A3B8")
        levelText.typeface = Typeface.MONOSPACE
        root.addView(levelText, params(12))

        statusText = label("Prêt.", 14f, "#CBD5E1")
        root.addView(statusText, params(12))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0F172A"))
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }

    // ---------- petits outils d'affichage ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun params(top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(top)
        return p
    }

    private fun label(text: String, size: Float, color: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(Color.parseColor(color))
        return t
    }

    private fun makeButton(text: String, color: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setAllCaps(false)
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(Color.parseColor(color))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun modeLabel(): String = "Mode d'envoi : ${Modem.MODE_NAMES[mode]}  (touche pour changer)"

    private fun status(s: String) {
        runOnUiThread { statusText.text = s }
    }

    // ---------- envoyer ----------

    private fun send(id: Int) {
        if (sending) return
        sending = true
        val samples = Modem.encode(mode, id)
        val name = Modem.MODE_NAMES[mode]
        status("Envoi de « ${messages[id]} » — $name")
        Thread {
            var track: AudioTrack? = null
            var error: String? = null
            try {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Modem.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track = t
                t.write(samples, 0, samples.size)
                t.play()
                Thread.sleep(samples.size * 1000L / Modem.SAMPLE_RATE + 300L)
            } catch (e: Exception) {
                error = e.message ?: "inconnue"
            } finally {
                try {
                    track?.stop()
                } catch (e: Exception) {
                }
                track?.release()
                decoder.reset()
                sending = false
            }
            status(if (error == null) "Message envoyé." else "Erreur d'envoi : $error")
        }.start()
    }

    // ---------- recevoir ----------

    private fun toggleListen() {
        if (listening) {
            stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            status("Permission du micro refusée : impossible d'écouter.")
        }
    }

    private fun startListening() {
        if (listening) return
        val channel = AudioFormat.CHANNEL_IN_MONO
        val enc = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(Modem.SAMPLE_RATE, channel, enc)
        val bufSize = maxOf(minBuf, Modem.SAMPLE_RATE)

        var found: AudioRecord? = null
        val sources = intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
        for (src in sources) {
            if (found != null) break
            try {
                val r = AudioRecord(src, Modem.SAMPLE_RATE, channel, enc, bufSize)
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    found = r
                } else {
                    r.release()
                }
            } catch (e: Exception) {
            }
        }
        val rec: AudioRecord = found ?: run {
            status("Impossible d'ouvrir le micro.")
            return
        }

        decoder.reset()
        rec.startRecording()
        listening = true
        listenButton.text = "Arrêter l'écoute"
        receivedText.text = "J'écoute…"
        status("Écoute en cours. L'autre téléphone peut envoyer.")
        handler.post(refresher)

        val t = Thread {
            val buf = ShortArray(2048)
            while (listening) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n > 0 && !sending) decoder.feed(buf, n)
            }
            try {
                rec.stop()
            } catch (e: Exception) {
            }
            rec.release()
        }
        recordThread = t
        t.start()
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(refresher)
        recordThread?.join(500)
        recordThread = null
        listenButton.text = "Écouter"
        levelText.text = ""
        status("Écoute arrêtée.")
    }

    private fun onReceived(id: Int, m: Int) {
        runOnUiThread {
            val text = if (id in messages.indices) messages[id] else "Message inconnu ($id)"
            receivedText.text = "Reçu : $text"
            status("Message reçu en mode : ${Modem.MODE_NAMES[m]}")
            vibrate()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
