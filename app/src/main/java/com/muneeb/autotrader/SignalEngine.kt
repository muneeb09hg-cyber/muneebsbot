package com.muneeb.autotrader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * चार्ट के स्क्रीनशॉट से हरी/लाल कैंडल पहचानता है और सिग्नल बनाता है।
 * कीमत यहाँ पिक्सल की ऊँचाई से निकाली गई अनुमानित कीमत है, असली कीमत नहीं।
 */
object SignalEngine {

    data class Candle(
        val green: Boolean,
        val open: Float,
        val close: Float,
        val high: Float,
        val low: Float
    )

    /** dir: 1 = UP, -1 = DOWN, 0 = कोई ट्रेड नहीं */
    data class Signal(val dir: Int, val strength: Int, val price: Float, val note: String)

    private fun isGreen(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return g - r > 50 && g - b > 20
    }

    private fun isRed(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r - g > 60 && r - b > 40
    }

    fun candles(bmp: Bitmap, rc: Rect): List<Candle> {
        val l = rc.left.coerceIn(0, bmp.width - 2)
        val t = rc.top.coerceIn(0, bmp.height - 2)
        val r = rc.right.coerceIn(l + 1, bmp.width)
        val b = rc.bottom.coerceIn(t + 1, bmp.height)
        val w = r - l
        val h = b - t
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, l, t, w, h)

        val cls = IntArray(w)
        val top = IntArray(w) { h }
        val bot = IntArray(w) { -1 }

        for (x in 0 until w) {
            var gc = 0
            var rcnt = 0
            var gTop = h
            var gBot = -1
            var rTop = h
            var rBot = -1
            for (y in 0 until h) {
                val c = px[y * w + x]
                if (isGreen(c)) {
                    gc++
                    if (y < gTop) gTop = y
                    if (y > gBot) gBot = y
                } else if (isRed(c)) {
                    rcnt++
                    if (y < rTop) rTop = y
                    if (y > rBot) rBot = y
                }
            }
            if (gc >= 2 && gc >= rcnt) {
                cls[x] = 1
                top[x] = gTop
                bot[x] = gBot
            } else if (rcnt >= 2) {
                cls[x] = -1
                top[x] = rTop
                bot[x] = rBot
            }
        }

        val out = mutableListOf<Candle>()
        var x = 0
        while (x < w) {
            if (cls[x] == 0) {
                x++
                continue
            }
            val c = cls[x]
            var e = x
            var tMin = top[x]
            var bMax = bot[x]
            while (e + 1 < w && cls[e + 1] == c) {
                e++
                tMin = min(tMin, top[e])
                bMax = max(bMax, bot[e])
            }
            val width = e - x + 1
            if (width >= 2 && width <= max(6, w / 6)) {
                val hi = (h - tMin).toFloat()
                val lo = (h - bMax).toFloat()
                val green = c == 1
                out.add(
                    Candle(
                        green = green,
                        open = if (green) lo else hi,
                        close = if (green) hi else lo,
                        high = hi,
                        low = lo
                    )
                )
            }
            x = e + 1
        }
        return out
    }

    fun lastClose(bmp: Bitmap, rc: Rect): Float? = candles(bmp, rc).lastOrNull()?.close

    private fun ema(v: List<Float>, n: Int): Float {
        val k = 2f / (n + 1)
        var e = v.first()
        for (i in 1 until v.size) e = v[i] * k + e * (1 - k)
        return e
    }

    fun analyze(bmp: Bitmap, rc: Rect): Signal {
        val cs = candles(bmp, rc).takeLast(30)
        if (cs.size < 14) {
            return Signal(0, 0, 0f, "चार्ट पर कैंडल कम मिलीं (${cs.size})")
        }
        val closes = cs.map { it.close }
        val emaFast = ema(closes, 5)
        val emaSlow = ema(closes, 13)
        val sma = closes.takeLast(20).average().toFloat()
        val last = cs.last()

        val recent = cs.takeLast(8)
        val bull = recent.filter { it.green }.sumOf { abs(it.close - it.open).toDouble() }.toFloat()
        val bear = recent.filter { !it.green }.sumOf { abs(it.close - it.open).toDouble() }.toFloat()
        val total = bull + bear
        val pressure = if (total > 0f) bull / total else 0.5f

        val avgBody = cs.map { abs(it.close - it.open) }.average().toFloat()
        val gap = abs(emaFast - emaSlow)
        val strength = (50 + abs(pressure - 0.5f) * 100).toInt().coerceAtMost(95)

        if (gap < 0.15f * avgBody) {
            return Signal(0, strength, last.close, "बाज़ार सपाट है, ट्रेड नहीं")
        }
        if (emaFast > emaSlow && last.close > sma && pressure >= 0.6f && last.green) {
            return Signal(1, strength, last.close, "बुल प्रेशर + EMA ऊपर")
        }
        if (emaFast < emaSlow && last.close < sma && pressure <= 0.4f && !last.green) {
            return Signal(-1, strength, last.close, "बेयर प्रेशर + EMA नीचे")
        }
        return Signal(0, strength, last.close, "सिग्नल साफ़ नहीं")
    }
}
