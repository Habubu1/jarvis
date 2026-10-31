package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
        const val MODEL = "openai/gpt-oss-120b"
        const val SYSTEM_PROMPT =
            "You are Jarvis, a voice assistant that controls the user's Android phone. " +
            "The user speaks English, Hindi or Hinglish. Reply in 1-2 short sentences. " +
            "If the user speaks Hindi or Hinglish, reply in Hindi (Devanagari). " +
            "For any phone action use the tools. To only open an app call open_app " +
            "with the app name; the phone decides whether it is installed. " +
            "To send a WhatsApp or SMS message call send_message with the contact name and the message text. " +
            "For anything INSIDE an app (search in Instagram, play a song in Spotify, scroll, like, " +
            "open a chat, settings inside an app) call phone_task with a clear goal sentence in English, " +
            "for example: In Spotify search for Chanda Mere and play the first song result. " +
            "Never use tools for payments, passwords or OTPs. Never say you cannot open apps."

        const val AGENT_PROMPT = """You control an Android phone through its accessibility tree.
You get the GOAL, your ACTIONS SO FAR and the current SCREEN as lines: index|flags|text.
Reply with ONE JSON object only, no other text. Allowed actions:
{"action":"open_app","name":"Instagram"}
{"action":"click","index":3}
{"action":"type","index":2,"text":"hello"}
{"action":"scroll","dir":"down"}
{"action":"back"}
{"action":"wait"}
{"action":"done","result":"one short sentence for the user"}
Rules: if the app of the goal is not on screen, use open_app first. To search: click the search icon or tab, then click the search field, type the query, then click the matching result or suggestion. To play a song: search it, then click the first song result. Finish with done as soon as the goal is achieved or impossible. Never enter passwords, OTPs or payment details, never send money, and never delete or post anything unless the goal clearly asks for it; in that case finish with done and tell the user."""
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var keyRow: LinearLayout
    private lateinit var keyInput: EditText
    private lateinit var cmdInput: EditText
    private lateinit var micBtn: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var busy = false
    private val history = JSONArray()

    private val aliases = mapOf(
        "insta" to "instagram", "ig" to "instagram", "yt" to "youtube",
        "fb" to "facebook", "whatsap" to "whatsapp", "gpay" to "googlepay"
    )

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("jarvis", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(dp(16), dp(40), dp(16), dp(16))
        }

        val title = TextView(this).apply {
            text = "JARVIS"
            textSize = 26f
            setTextColor(Color.parseColor("#4FC3F7"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(title)

        keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyInput = EditText(this).apply {
            hint = "Groq API key paste karo"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val saveBtn = Button(this).apply { text = "Save" }
        keyRow.addView(keyInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        keyRow.addView(saveBtn)
        root.addView(keyRow)
        keyRow.visibility = if (savedKey().isBlank()) View.VISIBLE else View.GONE

        saveBtn.setOnClickListener {
            val k = keyInput.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("key", k).apply()
                keyInput.setText("")
                keyRow.visibility = View.GONE
                log("Key save ho gayi. Ab 'Bolo' dabao.")
            }
        }
        // tap on the title to change the key later
        title.setOnClickListener { keyRow.visibility = View.VISIBLE }

        logView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val cmdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cmdInput = EditText(this).apply {
            hint = "Ya yahan type karo"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        val sendBtn = Button(this).apply { text = "Send" }
        cmdRow.addView(cmdInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cmdRow.addView(sendBtn)
        root.addView(cmdRow)

        sendBtn.setOnClickListener {
            val t = cmdInput.text.toString().trim()
            if (t.isNotEmpty()) {
                cmdInput.setText("")
                handle(t)
            }
        }

        val accBtn = Button(this).apply { text = "Accessibility settings kholo" }
        root.addView(accBtn)
        accBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        micBtn = Button(this).apply {
            text = "🎤 Bolo"
            textSize = 20f
        }
        root.addView(
            micBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70))
        )
        micBtn.setOnClickListener {
            if (!busy) {
                tts?.stop()
                startListening()
            }
        }

        setContentView(root)

        history.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }

        val need = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)

        log("Jarvis ready. Mic dabao aur bolo.")
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun savedKey(): String = prefs.getString("key", "") ?: ""

    private fun log(text: String) {
        runOnUiThread {
            logView.append(text + "\n\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setMic(text: String) {
        runOnUiThread { micBtn.text = text }
    }

    // ------------------------------------------------------- voice in / out

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("Is phone mein speech recognition available nahi hai.")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setMic("Sun raha hoon...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setMic("🎤 Bolo") }
            override fun onError(error: Int) {
                setMic("🎤 Bolo")
                log("Sun nahi paya (error $error). Dobara try karo.")
            }
            override fun onResults(results: Bundle?) {
                setMic("🎤 Bolo")
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) handle(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        }
        r.startListening(i)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val hindi = text.any { it in 'ऀ'..'ॿ' }
        tts?.language = if (hindi) Locale("hi", "IN") else Locale("en", "IN")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    // ------------------------------------------------------------ the brain

    private fun handle(text: String) {
        val key = savedKey()
        if (key.isBlank()) {
            keyRow.visibility = View.VISIBLE
            log("Pehle upar Groq API key daalkar Save dabao.")
            return
        }
        if (busy) return
        busy = true
        log("Tum: $text")
        thread {
            val answer = try {
                think(text, key)
            } catch (e: Exception) {
                "Internet ya Groq mein dikkat: " + (e.message ?: "unknown")
            }
            busy = false
            log("Jarvis: $answer")
            speak(answer)
        }
    }

    private fun think(text: String, key: String): String {
        history.put(JSONObject().put("role", "user").put("content", text))
        while (history.length() > 13) history.remove(1)

        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", history)
            .put("tools", toolsJson)
        val res = post(body, key)
        if (!res.has("choices")) {
            val m = res.optJSONObject("error")?.optString("message") ?: res.toString()
            return "Groq error: $m"
        }
        val msg = res.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val calls = msg.optJSONArray("tool_calls")

        val out: String = if (calls != null && calls.length() > 0) {
            val parts = ArrayList<String>()
            for (i in 0 until calls.length()) {
                val f = calls.getJSONObject(i).getJSONObject("function")
                val raw = f.optString("arguments", "{}")
                val args = JSONObject(if (raw.isBlank()) "{}" else raw)
                parts.add(runTool(f.getString("name"), args))
            }
            parts.joinToString(". ")
        } else {
            if (msg.isNull("content")) "Samajh nahi aaya" else msg.getString("content")
        }
        history.put(JSONObject().put("role", "assistant").put("content", out))
        return out
    }

    private fun post(body: JSONObject, key: String): JSONObject {
        val c = URL(GROQ_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return JSONObject(txt)
    }

    // ---------------------------------------------------------------- tools

    private fun p(type: String) = JSONObject().put("type", type)

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>) =
        JSONObject()
            .put("type", "function")
            .put(
                "function", JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put(
                        "parameters", JSONObject()
                            .put("type", "object")
                            .put("properties", props)
                            .put("required", JSONArray(required))
                    )
            )

    private val toolsJson: JSONArray by lazy {
        JSONArray()
            .put(
                tool(
                    "open_app",
                    "Open an app by its name (any installed app). Falls back to a website if the app is not installed.",
                    JSONObject().put("name", p("string")), listOf("name")
                )
            )
            .put(
                tool(
                    "flashlight", "Turn the flashlight on or off",
                    JSONObject().put("state", p("string").put("enum", JSONArray(listOf("on", "off")))),
                    listOf("state")
                )
            )
            .put(tool("battery", "Get the battery percentage", JSONObject(), emptyList()))
            .put(
                tool(
                    "volume", "Set media volume from 0 to 15",
                    JSONObject().put("level", p("integer")), listOf("level")
                )
            )
            .put(
                tool(
                    "call", "Call a phone number or a saved contact name",
                    JSONObject().put("target", p("string")), listOf("target")
                )
            )
            .put(
                tool(
                    "alarm", "Set an alarm using 24-hour time",
                    JSONObject().put("hour", p("integer")).put("minute", p("integer")),
                    listOf("hour", "minute")
                )
            )
            .put(
                tool(
                    "search",
                    "Open a search page for youtube, maps or Google only. For searching inside Instagram, Spotify or other apps use phone_task instead.",
                    JSONObject().put("app", p("string")).put("query", p("string")),
                    listOf("app", "query")
                )
            )
            .put(
                tool(
                    "play_music",
                    "Fallback only: ask the music app to play a song. Prefer phone_task for Spotify or YouTube.",
                    JSONObject().put("query", p("string")).put("app", p("string")),
                    listOf("query")
                )
            )
            .put(
                tool(
                    "send_message",
                    "Send a WhatsApp message (default) or SMS to a saved contact name or phone number.",
                    JSONObject()
                        .put("to", p("string"))
                        .put("text", p("string"))
                        .put("app", p("string")),
                    listOf("to", "text")
                )
            )
            .put(
                tool(
                    "phone_task",
                    "Do something inside any app by looking at the screen and tapping/typing, for example: In Instagram search for priyankachopra; In Spotify search for a song and play the first result.",
                    JSONObject().put("goal", p("string")),
                    listOf("goal")
                )
            )
    }

    private fun runTool(name: String, a: JSONObject): String {
        return try {
            when (name) {
                "open_app" -> openApp(a.optString("name"))
                "flashlight" -> flashlight(a.optString("state") == "on")
                "battery" -> battery()
                "volume" -> setVolume(a.optInt("level", 8))
                "call" -> call(a.optString("target"))
                "alarm" -> alarm(a.optInt("hour"), a.optInt("minute"))
                "search" -> search(a.optString("app"), a.optString("query"))
                "play_music" -> playMusic(a.optString("query"), a.optString("app"))
                "send_message" -> sendMessage(a.optString("to"), a.optString("text"), a.optString("app"))
                "phone_task" -> phoneTask(a.optString("goal"))
                else -> "Ye kaam abhi nahi aata"
            }
        } catch (e: Exception) {
            "Dikkat aayi: " + (e.message ?: name)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun openUrl(url: String, appName: String = "") {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appName.isNotBlank()) {
            val r = findApp(appName)
            if (r != null) {
                i.setPackage(r.activityInfo.packageName)
                try {
                    startActivity(i)
                    return
                } catch (e: Exception) {
                    i.setPackage(null)
                }
            }
        }
        startActivity(i)
    }

    // --- app list knowledge: the phone's own list of installed apps

    private fun norm(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun lev(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private fun findApp(query: String): ResolveInfo? {
        val pm = packageManager
        val list = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        var q = norm(query)
        q = aliases[q] ?: q
        if (q.isEmpty()) return null
        var best: ResolveInfo? = null
        var bestScore = 0
        for (r in list) {
            val label = norm(r.loadLabel(pm).toString())
            if (label.isEmpty()) continue
            val score = when {
                label == q -> 100
                q.length >= 3 && label.startsWith(q) -> 80
                q.length >= 4 && label.contains(q) -> 60
                label.length >= 4 && q.contains(label) -> 55
                label.length >= 4 && lev(label, q) <= 2 -> 50
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = r
            }
        }
        return best
    }

    private fun openApp(name: String): String {
        val r = findApp(name)
        if (r != null) {
            val label = r.loadLabel(packageManager).toString()
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "$label khol raha hoon"
        }
        openUrl("https://www.google.com/search?q=" + enc(name))
        return "$name app nahi mila, website khol raha hoon"
    }

    // --- other tools

    private fun flashlight(on: Boolean): String {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Is phone mein flash nahi mila"
        cm.setTorchMode(id, on)
        return if (on) "Torch on" else "Torch off"
    }

    private fun battery(): String {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery $level percent hai" + if (bm.isCharging) ", charging chal rahi hai" else ""
    }

    private fun setVolume(level: Int): String {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val l = level.coerceIn(0, 15)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, l * max / 15, AudioManager.FLAG_SHOW_UI)
        return "Volume $l"
    }

    private fun contactNumber(name: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        return contentResolver.query(
            phone,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$name%"),
            null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun call(target: String): String {
        val digits = target.filter { it.isDigit() || it == '+' }
        val number = if (digits.count { it.isDigit() } >= 5) digits else contactNumber(target)
            ?: return "$target naam ka contact nahi mila"
        val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(action, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return if (canCall) "Call laga raha hoon" else "Dialer khol diya"
    }

    private fun alarm(hour: Int, minute: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return "Alarm set: %02d:%02d".format(hour, minute)
    }

    private fun search(app: String, query: String): String {
        val a = norm(app)
        val q = enc(query)
        val link = when {
            a.contains("youtube") -> "https://www.youtube.com/results?search_query=$q"
            a.contains("spotify") -> "spotify:search:$q"
            a.contains("instagram") -> "instagram://user?username=" + query.replace(" ", "").lowercase(Locale.ROOT)
            a.contains("map") -> "geo:0,0?q=$q"
            else -> "https://www.google.com/search?q=$q"
        }
        openUrl(link, app)
        return "$app par $query khol raha hoon"
    }

    private fun playMusic(query: String, app: String): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (app.isNotBlank()) {
            findApp(app)?.let { i.setPackage(it.activityInfo.packageName) }
        }
        return try {
            startActivity(i)
            "$query chala raha hoon"
        } catch (e: Exception) {
            openUrl("https://www.youtube.com/results?search_query=" + enc(query))
            "Music app nahi mila, YouTube par $query khol raha hoon"
        }
    }

    // --- messages: WhatsApp / SMS

    private fun sendMessage(to: String, text: String, app: String): String {
        val raw = if (to.count { it.isDigit() } >= 7) to else contactNumber(to)
            ?: return "$to naam ka contact nahi mila"
        var num = raw.filter { it.isDigit() }.trimStart('0')
        if (num.length == 10) num = "91$num"
        val a = norm(app)
        val sms = a == "sms" || a == "messages" || a == "text"

        if (sms) {
            startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num"))
                    .putExtra("sms_body", text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            openUrl("https://wa.me/$num?text=" + enc(text), "whatsapp")
        }

        val svc = JarvisAccessibilityService.instance
            ?: return "Message likh diya hai, bas Send dabao. Auto-send ke liye Accessibility ON karo."
        repeat(10) {
            Thread.sleep(1000)
            if (svc.clickSend()) return "$to ko message bhej diya"
        }
        return "Message taiyar hai par Send button nahi mila, khud dabao"
    }

    // --- general agent: look at the screen, tap / type / scroll inside any app

    private fun phoneTask(goal: String): String {
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return "Pehle Accessibility mein Jarvis ko ON karo, maine settings khol di hain"
        }
        val key = savedKey()
        val done = ArrayList<String>()
        repeat(15) {
            Thread.sleep(1800)
            val screen = svc.snapshot()
            val prompt = "GOAL: $goal\nACTIONS SO FAR: " + done.takeLast(8).joinToString("; ") +
                "\nSCREEN:\n" + screen
            val msgs = JSONArray()
                .put(JSONObject().put("role", "system").put("content", AGENT_PROMPT))
                .put(JSONObject().put("role", "user").put("content", prompt))
            val res = post(
                JSONObject().put("model", MODEL).put("messages", msgs).put("temperature", 0.2),
                key
            )
            if (!res.has("choices")) return "Groq error aa gaya"
            val c = res.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
            val s = c.indexOf('{')
            val e = c.lastIndexOf('}')
            if (s < 0 || e <= s) {
                done.add("bad reply")
                return@repeat
            }
            val act = try {
                JSONObject(c.substring(s, e + 1))
            } catch (ex: Exception) {
                done.add("bad json")
                return@repeat
            }
            when (act.optString("action")) {
                "done" -> return act.optString("result", "Ho gaya")
                "open_app" -> {
                    val n = act.optString("name")
                    done.add("open $n")
                    openApp(n)
                }
                "click" -> {
                    val i = act.optInt("index", -1)
                    done.add("click $i " + svc.label(i))
                    svc.clickIndex(i)
                }
                "type" -> {
                    val i = act.optInt("index", -1)
                    val t = act.optString("text")
                    done.add("type '$t' in $i")
                    svc.setText(i, t)
                }
                "scroll" -> {
                    val down = act.optString("dir") != "up"
                    done.add("scroll " + (if (down) "down" else "up"))
                    svc.scroll(down)
                }
                "back" -> {
                    done.add("back")
                    svc.back()
                }
                else -> done.add("wait")
            }
        }
        return "Kaam poora nahi ho paya, 15 steps ho gaye"
    }
}
