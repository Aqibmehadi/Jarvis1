package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.text.InputType
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {

    private val defaultVoice = "pNInz6obpgDQGcFmaJgB" // Adam
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TextToSpeech
    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        tts = TextToSpeech(this) { tts.language = Locale("en", "IN") }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }
        val title = TextView(this).apply {
            text = "JARVIS"; textSize = 32f; gravity = Gravity.CENTER
        }
        status = TextView(this).apply { text = "Ready"; gravity = Gravity.CENTER }
        val mic = Button(this).apply { text = "🎤  Bolo"; textSize = 22f }
        input = EditText(this).apply { hint = "ya type karo, e.g. open whatsapp" }
        val go = Button(this).apply { text = "Run" }

        val keyBox = EditText(this).apply {
            hint = "ElevenLabs API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val voiceBox = EditText(this).apply {
            hint = "ElevenLabs Voice ID"
            setText(prefs.getString("voice", defaultVoice))
        }
        val save = Button(this).apply { text = "Save voice settings" }

        log = TextView(this).apply {
            textSize = 16f
            text = "Examples:\n• open whatsapp / whatsapp kholo\n• call mummy / call 9876543210\n• message mummy kal milte hain\n• whatsapp rahul hello bhai\n• torch on / torch off\n• volume up / volume down\n• alarm 7 30 am\n• timer 5 minute\n• navigate to railway station\n• search best pizza\n• youtube arijit singh\n• battery / time"
        }

        root.addView(title); root.addView(status); root.addView(mic)
        root.addView(input); root.addView(go)
        root.addView(keyBox); root.addView(voiceBox); root.addView(save)
        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)

        mic.setOnClickListener { listen() }
        go.setOnClickListener { handle(input.text.toString()); input.setText("") }
        save.setOnClickListener {
            prefs.edit()
                .putString("key", keyBox.text.toString().trim())
                .putString("voice", voiceBox.text.toString().trim().ifEmpty { defaultVoice })
                .apply()
            reply("Settings save ho gayi")
        }

        askPermissions()
    }

    // ---------- Permissions ----------
    private fun askPermissions() {
        val need = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 100)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------- Listening ----------
    private fun listen() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { askPermissions(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            reply("Speech recognition available nahi hai"); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { status.text = "Sun raha hoon..." }
            override fun onResults(b: Bundle?) {
                status.text = "Ready"
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) handle(t)
            }
            override fun onError(e: Int) { status.text = "Samajh nahi aaya (error $e), dobara dabao" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }
        recognizer!!.startListening(i)
    }

    // ---------- Speaking (ElevenLabs, fallback Android TTS) ----------
    private fun reply(msg: String) {
        log.append("\n\n🤖 $msg")
        speak(msg)
    }

    private fun fallbackTts(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j")
    }

    private fun speak(text: String) {
        val key = prefs.getString("key", "") ?: ""
        val voice = prefs.getString("voice", defaultVoice) ?: defaultVoice
        if (key.isBlank()) { fallbackTts(text); return }
        Thread {
            try {
                val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128")
                val con = url.openConnection() as HttpURLConnection
                con.requestMethod = "POST"
                con.setRequestProperty("xi-api-key", key)
                con.setRequestProperty("Content-Type", "application/json")
                con.doOutput = true
                val body = JSONObject()
                    .put("text", text)
                    .put("model_id", "eleven_multilingual_v2")
                    .toString()
                con.outputStream.use { it.write(body.toByteArray()) }
                val code = con.responseCode
                if (code != 200) {
                    runOnUiThread {
                        log.append("\n⚠ ElevenLabs error $code (key/voice check karo)")
                        fallbackTts(text)
                    }
                    return@Thread
                }
                val f = File(cacheDir, "voice.mp3")
                con.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                runOnUiThread { play(f) }
            } catch (e: Exception) {
                runOnUiThread { fallbackTts(text) }
            }
        }.start()
    }

    private fun play(f: File) {
        try {
            try { player?.release() } catch (_: Exception) {}
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            log.append("\n⚠ Audio play error")
        }
    }

    // ---------- Command handling ----------
    private fun has(s: String, vararg words: String) = words.any { s.contains(it) }

    private fun handle(raw: String) {
        val cmd = raw.trim().lowercase(Locale.ROOT)
        if (cmd.isEmpty()) return
        log.append("\n\n🗣 $raw")
        try {
            when {
                has(cmd, "whatsapp") && has(cmd, "message", "msg", "bhej", "send") -> whatsapp(cmd)

                has(cmd, "call", "dial") -> call(cmd)

                cmd.startsWith("message") || cmd.startsWith("msg") ||
                    cmd.startsWith("sms") || cmd.startsWith("text") -> sms(cmd)

                has(cmd, "torch", "flashlight", "flash light") -> {
                    val off = has(cmd, "off", "band", "bandh")
                    torch(!off)
                    reply(if (off) "Torch band" else "Torch chalu")
                }

                has(cmd, "volume", "awaaz", "awaz") -> volume(cmd)

                has(cmd, "timer") -> timer(cmd)
                has(cmd, "alarm") -> alarm(cmd)

                has(cmd, "navigate", "map", "rasta", "raasta") -> {
                    val q = cmd.replace(Regex("\\b(navigate|to|map|maps|rasta|raasta|dikhao|batao)\\b"), " ").trim()
                    reply("Maps khol raha hoon")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
                }

                has(cmd, "wifi", "wi-fi") -> {
                    reply("Wifi settings khol raha hoon")
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                has(cmd, "bluetooth") -> {
                    reply("Bluetooth settings khol raha hoon")
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }

                has(cmd, "battery") -> {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    reply("Battery $p percent hai")
                }

                cmd.startsWith("youtube") -> {
                    val q = cmd.removePrefix("youtube").trim()
                    reply("YouTube pe dhundh raha hoon")
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
                }

                cmd.startsWith("search") || cmd.startsWith("google") -> {
                    val q = cmd.replace(Regex("^(search|google)"), "").trim()
                    reply("Search kar raha hoon")
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                }

                has(cmd, "time", "samay") -> {
                    val t = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date())
                    reply("Abhi time hai $t")
                }

                cmd.startsWith("open") || has(cmd, "kholo", "khol") -> {
                    val name = cmd.replace(Regex("\\b(open|kholo|khol|karo|do|app)\\b"), "").trim()
                    openApp(name)
                }

                else -> reply("Samajh nahi aaya. Dobara bolo.")
            }
        } catch (e: Exception) {
            reply("Kuch gadbad ho gayi: ${e.message}")
        }
    }

    // ---------- Helpers ----------
    private fun splitTarget(rest: String): Pair<String, String> {
        val t = rest.split(" ").filter { it.isNotBlank() }.toMutableList()
        while (t.isNotEmpty() && (t[0] == "to" || t[0] == "ko")) t.removeAt(0)
        if (t.isEmpty()) return "" to ""
        if (t[0].all { it.isDigit() }) {
            val num = StringBuilder()
            while (t.isNotEmpty() && t[0].all { it.isDigit() }) num.append(t.removeAt(0))
            return num.toString() to t.joinToString(" ")
        }
        val name = t.removeAt(0)
        return name to t.joinToString(" ")
    }

    private fun findContact(name: String): String? {
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        val c = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"), null
        ) ?: return null
        c.use { return if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun resolveNumber(target: String): String? {
        val d = target.filter { it.isDigit() }
        return if (d.length >= 5 && target.all { it.isDigit() || it == ' ' || it == '+' }) d
        else findContact(target)?.filter { it.isDigit() || it == '+' }
    }

    // ---------- Actions ----------
    private fun call(cmd: String) {
        val rest = cmd.replace(Regex("\\b(call|dial|karo|kar|do|lagao|laga|ko|to|phone)\\b"), " ").trim()
        val num = resolveNumber(rest)
        if (num == null) { reply("$rest ka number nahi mila"); return }
        reply("$rest ko call laga raha hoon")
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(action, Uri.parse("tel:$num")))
    }

    private fun sms(cmd: String) {
        val rest = cmd.replace(Regex("^(message|msg|sms|text)"), "").trim()
        val (target, body) = splitTarget(rest)
        val num = resolveNumber(target)
        if (num == null || body.isBlank()) { reply("Naam ya number aur message dono batao"); return }
        if (granted(Manifest.permission.SEND_SMS)) {
            val sm = if (android.os.Build.VERSION.SDK_INT >= 31)
                getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            sm.sendMultipartTextMessage(num, null, sm.divideMessage(body), null, null)
            reply("$target ko message bhej diya")
        } else {
            reply("Message ready hai, send dabao")
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).apply {
                putExtra("sms_body", body)
            })
        }
    }

    private fun whatsapp(cmd: String) {
        val rest = cmd.replace(Regex("\\b(whatsapp|message|msg|bhejo|bhej|send|on|par|pe)\\b"), " ").trim()
        val (target, body) = splitTarget(rest)
        var num = resolveNumber(target)?.removePrefix("+")
        if (num == null) { reply("$target ka number nahi mila"); return }
        if (num.length == 10) num = "91$num"
        reply("WhatsApp khol raha hoon, send dabao")
        startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$num?text=" + Uri.encode(body))))
    }

    private fun torch(on: Boolean) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull() ?: return
        cm.setTorchMode(id, on)
        torchOn = on
    }

    private fun volume(cmd: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = when {
            has(cmd, "mute") -> AudioManager.ADJUST_MUTE
            has(cmd, "down", "kam", "ghata", "decrease", "dheere") -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_RAISE
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        reply("Volume adjust kar diya")
    }

    private fun alarm(cmd: String) {
        val nums = Regex("\\d+").findAll(cmd).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) { reply("Alarm ka time batao"); return }
        var h = nums[0]
        val m = if (nums.size > 1) nums[1] else 0
        if (has(cmd, "pm", "p.m") && h < 12) h += 12
        if (has(cmd, "am", "a.m") && h == 12) h = 0
        startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
        reply("Alarm set ho gaya")
    }

    private fun timer(cmd: String) {
        val n = Regex("\\d+").find(cmd)?.value?.toInt()
        if (n == null) { reply("Kitne minute ka timer?"); return }
        val secs = when {
            has(cmd, "second", "sec") -> n
            has(cmd, "hour", "ghant") -> n * 3600
            else -> n * 60
        }
        startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, secs)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
        reply("Timer chalu")
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(name)
        }
        if (match != null && name.isNotEmpty()) {
            reply("${match.loadLabel(pm)} khol raha hoon")
            startActivity(pm.getLaunchIntentForPackage(match.activityInfo.packageName))
        } else reply("$name naam ka app nahi mila")
    }

    override fun onDestroy() {
        tts.shutdown()
        recognizer?.destroy()
        try { player?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
