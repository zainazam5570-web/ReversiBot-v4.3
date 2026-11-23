package com.reversibot.bot

import com.reversibot.engine.BLACK
import com.reversibot.engine.Level
import com.reversibot.engine.Rules
import com.reversibot.engine.Searcher
import com.reversibot.engine.WHITE
import com.reversibot.vision.BoardDetector
import com.reversibot.vision.BoardFinder
import com.reversibot.vision.BoardGeometry
import com.reversibot.vision.PixelSource
import com.reversibot.vision.Reading
import java.util.Random

/**
 * Bot ka dimagh. Android se alag rakha gaya hai taake PC par test ho sake.
 *
 * Har ~100ms: screen padho -> agar chaal chalne ke hints nazar aayen aur board 350ms se stable ho
 * -> hints se apna color pehchano -> engine se best move -> us square par tap.
 * autoPlay band ho to sirf dekhta aur sochta hai (tap nahi karta).
 */
class GameLoop(
    private val frames: () -> PixelSource?,
    /** Manual calibration (sirf fallback, agar auto-detect board na dhoond sake). */
    private val geometry: () -> BoardGeometry?,
    private val tap: (Float, Float) -> Boolean,
    private val level: () -> Level,
    private val listener: Listener,
    private val pollMs: Long = 100,
    private val stableMs: Long = 350,
    private val retryMs: Long = 2500
) {
    interface Listener {
        fun onStatus(text: String)
        fun onFrame(reading: Reading?, myColor: Int, bestMove: Int)
    }

    @Volatile var autoPlay = false
    @Volatile private var running = false
    private var thread: Thread? = null

    private val detector = BoardDetector()
    private val finder = BoardFinder(detector)

    /** Jo geometry abhi istemal ho rahi hai (auto ya manual). */
    @Volatile var activeGeometry: BoardGeometry? = null; private set
    private var autoGeo: BoardGeometry? = null
    private var lastFindAt = 0L
    private var badSince = 0L
    private var srcW = 0
    private var srcH = 0
    private val searcher = Searcher()
    private val rnd = Random()

    private var lastSig = ""
    private var sigSince = 0L
    private var lastThinkSig = ""
    private var lastBest = -1
    private var lastColor = 0
    private var lastActSig = ""
    private var lastActAt = 0L

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "reversi-bot-loop").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        searcher.cancel()
        thread?.interrupt()
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { /* stop() */ }
    }

    private fun loop() {
        while (running) {
            try {
                step()
            } catch (e: Exception) {
                listener.onStatus("Error: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
            sleep(pollMs)
        }
    }

    private fun step() {
        val src = frames()
        if (src == null) {
            listener.onStatus("Screen capture ka intezar...")
            return
        }
        // screen size badli (rotate) to board dobara dhoondo
        if (src.width != srcW || src.height != srcH) {
            srcW = src.width; srcH = src.height
            autoGeo = null; lastFindAt = 0L; badSince = 0L
        }
        val t0 = System.currentTimeMillis()
        if (autoGeo == null && t0 - lastFindAt >= 700) {
            lastFindAt = t0
            autoGeo = finder.find(src)
            badSince = 0L
        }
        val usingAuto = autoGeo != null
        val geo = autoGeo ?: geometry()
        activeGeometry = geo
        if (geo == null) {
            listener.onStatus("Board nahi mila - game ki board wali screen kholein (ya 📐 Manual)")
            listener.onFrame(null, 0, -1)
            return
        }
        if (geo.right > src.width + 2 || geo.bottom > src.height + 2) {
            listener.onStatus("Screen size badal gaya (rotate?) - board dobara dhoond raha hun")
            autoGeo = null
            return
        }

        val reading = detector.read(src, geo)
        val now = System.currentTimeMillis()
        val sig = reading.signature()
        if (sig != lastSig) { lastSig = sig; sigSince = now }

        // auto board ka check: beech ke 4 squares hamesha discs hote hain. 1.5s tak na hon
        // to board hat gaya (menu/naya screen) - dobara dhoondo.
        if (usingAuto) {
            if (BoardFinder.isPlausible(reading)) badSince = 0L
            else if (badSince == 0L) badSince = now
            else if (now - badSince > 1500) {
                autoGeo = null; badSince = 0L; lastFindAt = 0L
                listener.onStatus("Board hat gaya - dobara dhoond raha hun...")
                return
            }
        }

        if (reading.hintCount == 0) {
            listener.onFrame(reading, lastColor, -1)
            listener.onStatus(if (autoPlay) "Opponent ki baari / intezar..." else "Dekh raha hun (Auto band)")
            return
        }
        if (now - sigSince < stableMs) {
            listener.onFrame(reading, lastColor, -1)
            listener.onStatus("Board settle ho raha hai...")
            return
        }
        // Hints (peele glow) pulse karte hain, kabhi kuch hints nazar nahi aate. Sirf tab sochte hain jab
        // hints bilkul poore legal moves ke barabar hon, warna aadhe hints par soch kar chhoti list
        // mein se chaal chal di jati (HTML ki final recommendation se alag).
        val hs = reading.hintSquares()
        val legalB = Rules.moves(reading.board, BLACK)
        val legalW = Rules.moves(reading.board, WHITE)
        fun exact(l: IntArray) = l.isNotEmpty() && l.size == hs.size && hs.all { it in l }
        val okB = exact(legalB)
        val okW = exact(legalW)
        val color: Int? = when {
            okB && okW -> if (lastColor != 0) lastColor else BoardDetector.inferColor(reading)
            okB -> BLACK
            okW -> WHITE
            else -> null
        }
        if (color == null) {
            listener.onFrame(reading, lastColor, -1)
            val partial = hs.all { it in legalB } || hs.all { it in legalW }
            listener.onStatus(
                if (partial) "Hints poore nazar nahi aaye (glow) - intezar..."
                else "Hints board se match nahi hue - board detection check karein"
            )
            return
        }
        lastColor = color
        val colorName = if (color == BLACK) "Black" else "White"

        // naye board par sochna (ek position ek baar)
        if (sig != lastThinkSig) {
            listener.onFrame(reading, color, -1)
            listener.onStatus("Main $colorName hun - soch raha hun...")
            val lv = level()
            // poore legal moves par search (HTML jaisa); sirf final (mukammal depth) ki chaal li jati hai
            val res = searcher.run(reading.board, color, lv.budgetMs, lv.maxDepth, null)
            if (!running) return
            lastThinkSig = sig
            lastBest = res.move
            listener.onFrame(reading, color, lastBest)
            if (res.move >= 0) {
                listener.onStatus("Main $colorName | best: ${Rules.sqName(res.move)} (depth ${res.depth})")
            }
        }
        if (lastBest < 0 || !autoPlay) return
        if (sig == lastActSig && now - lastActAt < retryMs) return

        // thinking ke dauran screen badal to nahi gayi?
        val src2 = frames() ?: return
        val again = detector.read(src2, geo)
        if (again.signature() != sig) return

        sleep(120L + rnd.nextInt(180))
        val x = geo.centerX(lastBest and 7)
        val y = geo.centerY(lastBest shr 3)
        val ok = tap(x, y)
        lastActSig = sig
        lastActAt = System.currentTimeMillis()
        listener.onStatus(
            if (ok) "Main $colorName | chaal: ${Rules.sqName(lastBest)} ✔"
            else "Tap nahi hua - Accessibility service on karein"
        )
    }
}
