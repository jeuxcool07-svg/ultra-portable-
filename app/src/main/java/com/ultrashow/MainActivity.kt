package com.ultrashow

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var modeSpinner: Spinner
    private lateinit var speedSpinner: Spinner
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var sendBtn: Button
    private lateinit var listenBtn: Button
    private lateinit var status: TextView
    private lateinit var log: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var decoder: Decoder? = null
    private var logText = ""
    @Volatile private var listening = false
    @Volatile private var sending = false
    @Volatile private var sessionId = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun label(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(14), 0, dp(4))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        fun add(v: View) = root.addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        add(TextView(this).apply {
            text = "Ultra Show"
            textSize = 24f
        })

        add(label("Canal"))
        modeSpinner = Spinner(this)
        modeSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Modem.MODE_NAMES
        )
        add(modeSpinner)

        add(label("Vitesse"))
        speedSpinner = Spinner(this)
        speedSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Modem.SPEED_NAMES
        )
        add(speedSpinner)

        add(label("Ton message"))
        input = EditText(this).apply {
            hint = "Écris ton message ici"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setHorizontallyScrolling(false)
            maxLines = 3
            filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                val next = dest.subSequence(0, dstart).toString() +
                    source.subSequence(start, end) +
                    dest.subSequence(dend, dest.length)
                if (next.toByteArray(Charsets.UTF_8).size <= Modem.MAX_BYTES) null else ""
            })
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        add(input)

        counter = TextView(this).apply {
            text = "0 / ${Modem.MAX_BYTES}"
            gravity = Gravity.END
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val n = (s?.toString() ?: "").toByteArray(Charsets.UTF_8).size
                counter.text = "$n / ${Modem.MAX_BYTES}"
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        add(counter)

        sendBtn = Button(this).apply {
            text = "Envoyer"
            setOnClickListener { send() }
        }
        add(sendBtn)

        listenBtn = Button(this).apply {
            text = "Écouter"
            setOnClickListener {
                if (listening) {
                    stopListening()
                } else {
                    startListening()
                }
            }
        }
        add(listenBtn)

        status = TextView(this).apply {
            text = "Prêt. Monte le volume média au maximum avant d'envoyer."
            setPadding(0, dp(12), 0, dp(12))
        }
        add(status)

        add(label("Messages reçus"))
        log = TextView(this).apply {
            text = "Aucun message reçu pour l'instant."
            textSize = 16f
        }
        add(log)

        add(Button(this).apply {
            text = "Réglages d'accessibilité"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })
    }

    // ---------- Envoi ----------

    private fun send() {
        if (sending) return
        val bytes = input.text.toString().trim().toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            toast("Écris un message d'abord")
            return
        }
        val mode = modeSpinner.selectedItemPosition
        val speed = speedSpinner.selectedItemPosition
        sending = true
        sendBtn.isEnabled = false
        status.text = "Préparation du son…"
        Thread {
            var result = "Message envoyé ✔"
            try {
                val pcm = Modem.encode(mode, speed, bytes)
                val sec = pcm.size / Modem.SAMPLE_RATE
                ui.post { status.text = "Envoi en cours… environ $sec s" }
                playPcm(pcm)
            } catch (e: Exception) {
                result = "Erreur d'envoi : ${e.message}"
            }
            ui.post {
                sending = false
                sendBtn.isEnabled = true
                status.text = result
            }
        }.start()
    }

    private fun playPcm(pcm: ShortArray) {
        val track = AudioTrack.Builder()
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
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / Modem.SAMPLE_RATE + 300)
            track.stop()
        } finally {
            track.release()
        }
    }

    // ---------- Écoute ----------

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(Modem.SAMPLE_RATE, channel, encoding)
        val bufSize = maxOf(minBuf, Modem.CHUNK * 2 * 4)

        var rec = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED, Modem.SAMPLE_RATE, channel, encoding, bufSize
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, Modem.SAMPLE_RATE, channel, encoding, bufSize
            )
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            toast("Micro indisponible")
            return
        }

        val dec = Decoder { text, mode, ok ->
            ui.post { showReceived(text, mode, ok) }
        }
        decoder = dec
        listening = true
        val id = ++sessionId
        listenBtn.text = "Arrêter l'écoute"

        Thread {
            val buf = ShortArray(Modem.CHUNK)
            try {
                rec.startRecording()
                while (listening && sessionId == id) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        dec.feed(buf, n)
                    } else if (n < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                // on s'arrete simplement
            } finally {
                try {
                    rec.stop()
                } catch (e: Exception) {
                }
                rec.release()
            }
        }.start()

        ui.post(ticker)
    }

    private fun stopListening() {
        listening = false
        sessionId++
        decoder?.reset()
        decoder = null
        listenBtn.text = "Écouter"
        if (!sending) status.text = "Écoute arrêtée"
    }

    private val ticker = object : Runnable {
        override fun run() {
            val d = decoder
            if (!listening || d == null) return
            if (!sending) {
                val p = d.progress()
                val db = d.levelDb(modeSpinner.selectedItemPosition).toInt()
                val line = if (p >= 0) "Réception… $p %" else "À l'écoute — niveau $db dB"
                status.text = line
            }
            ui.postDelayed(this, 250)
        }
    }

    private fun showReceived(text: String, mode: Int, ok: Boolean) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val warn = if (ok) "" else " ⚠ message abîmé"
        logText = "[$time] ${Modem.MODE_NAMES[mode]}$warn\n$text\n\n$logText"
        log.text = logText
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == 1 && granted) {
            startListening()
        } else {
            toast("Il faut autoriser le micro pour écouter")
        }
    }

    override fun onStop() {
        super.onStop()
        if (listening) stopListening()
    }
}
