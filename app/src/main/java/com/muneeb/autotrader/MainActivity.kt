package com.muneeb.autotrader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val reqCapture = 1001
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(text: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.BLACK)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }

        root.addView(label("Auto Trader", 26f, true))
        root.addView(label("चेतावनी: इसमें कोई जीत की गारंटी नहीं है। पहले डेमो अकाउंट पर चलाकर असली जीत का प्रतिशत देखें।"))

        root.addView(label("1. Accessibility चालू करें", 18f, true))
        root.addView(Button(this).apply {
            text = "Accessibility सेटिंग खोलें"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(label("2. स्क्रीन कैप्चर की इजाज़त दें", 18f, true))
        root.addView(label("ऐप चार्ट को स्क्रीनशॉट से पढ़ता है। ऐप बंद या फ़ोन रीस्टार्ट होने पर इसे दोबारा चालू करना पड़ता है।"))
        root.addView(Button(this).apply {
            text = "स्क्रीन कैप्चर चालू करें"
            setOnClickListener {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
            }
        })

        statusView = label("")
        root.addView(statusView)

        root.addView(label("3. इस्तेमाल का तरीका", 18f, true))
        root.addView(
            label(
                "• ट्रेडिंग ऐप खोलें और चार्ट पर 1 मिनट की कैंडल लगाएँ।\n" +
                        "• स्क्रीन पर \"AT\" बटन दिखेगा, उस पर टैप करें।\n" +
                        "• पहली बार \"जगह सेट करें\" दबाएँ: UP और DN निशान ट्रेडिंग ऐप के UP/DOWN बटनों पर रखें। TL और BR निशान चार्ट के ऊपर-बाएँ और नीचे-दाएँ कोने पर रखें (दाईं तरफ़ की क़ीमत वाली पट्टी शामिल न करें)। $ निशान रकम वाले बॉक्स पर रखें। फिर सेव दबाएँ।\n" +
                        "• \"ट्रेड सेटअप\" में रकम, ट्रेड की संख्या और समय चुनकर शुरू करें।\n" +
                        "• ट्रेडिंग ऐप में खुद भी वही समय (जैसे 5 सेकंड) चुनकर रखें, यह ऐप समय नहीं बदल पाता।\n" +
                        "• रोकने के लिए ऊपर की पट्टी पर टैप करें।"
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        statusView.text = "स्क्रीन कैप्चर: " + if (CaptureHolder.running) "चालू ✓" else "बंद ✗"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            ContextCompat.startForegroundService(this, i)
        }
    }
}
