package com.lilbig.chessoverlay

import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.WindowManager.LayoutParams.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.*

enum class MoveClass(val label: String, val emoji: String, val argb: Int) {
    BRILLIANT("Brilliant", "!!", 0xFF00C8FF.toInt()),
    BEST("Best", "★", 0xFF4CB87A.toInt()),
    GREAT("Great", "!", 0xFF4CB87A.toInt()),
    GOOD("Good", "○", 0xFF90EE90.toInt()),
    INACCURACY("Inaccuracy", "?!", 0xFFFFD700.toInt()),
    MISTAKE("Mistake", "?", 0xFFFF8C00.toInt()),
    BLUNDER("Blunder", "??", 0xFFFF3030.toInt()),
}

fun classify(cpLoss: Int, isBest: Boolean, isSac: Boolean): MoveClass = when {
    isBest && isSac -> MoveClass.BRILLIANT
    isBest -> MoveClass.BEST
    cpLoss <= 10 -> MoveClass.GREAT
    cpLoss <= 25 -> MoveClass.GOOD
    cpLoss <= 50 -> MoveClass.INACCURACY
    cpLoss <= 150 -> MoveClass.MISTAKE
    else -> MoveClass.BLUNDER
}

class ArrowView(context: android.content.Context) : android.view.View(context) {
    private var boardRect: RectF? = null
    private var move: String? = null
    private var cls: MoveClass? = null
    private var flipped = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55000000; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun set(board: RectF, mv: String, c: MoveClass, fp: Boolean) { boardRect=board; move=mv; cls=c; flipped=fp; invalidate() }
    fun clear() { boardRect=null; move=null; cls=null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val b = boardRect ?: return
        val mv = move ?: return
        val cw = b.width() / 8f
        val ch = b.height() / 8f
        val from = center(mv.substring(0, 2), b, cw, ch)
        val to = center(mv.substring(2, 4), b, cw, ch)

        // Match the bold, translucent analysis arrow shown in the reference:
        // dark charcoal body, rounded shaft, and a wide triangular point.
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy)
        if (length < 1f) return
        val ux = dx / length
        val uy = dy / length
        val px = -uy
        val py = ux

        val shaftWidth = cw * 0.19f
        val headLength = cw * 0.95f
        val headHalfWidth = cw * 0.48f
        val baseX = to.x - ux * headLength
        val baseY = to.y - uy * headLength
        val shaftEndX = baseX
        val shaftEndY = baseY

        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA20242B.toInt()
            style = Paint.Style.STROKE
            strokeWidth = shaftWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawLine(from.x, from.y, shaftEndX, shaftEndY, arrowPaint)

        val head = Path().apply {
            moveTo(to.x, to.y)
            lineTo(baseX + px * headHalfWidth, baseY + py * headHalfWidth)
            lineTo(baseX - px * headHalfWidth, baseY - py * headHalfWidth)
            close()
        }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAA20242B.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawPath(head, headPaint)
    }

    private fun center(sq: String, b: RectF, cw: Float, ch: Float): PointF {
        val f = sq[0]-'a'; val r = sq[1]-'1'
        val col = if (flipped) 7-f else f
        val row = if (flipped) r else 7-r
        return PointF(b.left+col*cw+cw/2f, b.top+row*ch+ch/2f)
    }
    private fun squareRect(sq: String, b: RectF, cw: Float, ch: Float): RectF {
        val c = center(sq,b,cw,ch)
        return RectF(c.x-cw/2f,c.y-ch/2f,c.x+cw/2f,c.y+ch/2f)
    }
}

object BoardFinder {
    // Detect a centered 8x8 checkerboard independent of its palette.
    // Search the likely phone-board area instead of assuming the board crosses screen midpoint.
    fun find(bmp: Bitmap): RectF? {
        val w = bmp.width
        val h = bmp.height
        val maxSide = min(w, h - 80)
        val minSide = (w * 0.72f).toInt().coerceAtLeast(240)
        if (maxSide < minSide) return null

        var bestScore = 0.0
        var best: RectF? = null
        val step = 10
        var side = minSide
        while (side <= maxSide) {
            val xMin = ((w - side) / 2 - w * 0.10f).toInt().coerceAtLeast(0)
            val xMax = ((w - side) / 2 + w * 0.10f).toInt().coerceAtMost(w - side)
            var x = xMin
            while (x <= xMax) {
                var y = (h * 0.12f).toInt()
                val yMax = (h * 0.82f - side).toInt().coerceAtLeast(y)
                while (y <= yMax) {
                    val score = checkerScore(bmp, x, y, side)
                    if (score > bestScore) {
                        bestScore = score
                        best = RectF(x.toFloat(), y.toFloat(), (x + side).toFloat(), (y + side).toFloat())
                    }
                    y += step
                }
                x += step
            }
            side += 8
        }
        return if (bestScore >= 13.0) best else null
    }

    private fun checkerScore(bmp: Bitmap, x: Int, y: Int, side: Int): Double {
        val cell = side / 8f
        val parity = Array(2) { DoubleArray(3) }
        val parityCount = IntArray(2)
        val variance = DoubleArray(2)
        for (rank in 0..7) for (file in 0..7) {
            val group = (rank + file) and 1
            val cx = x + (file + 0.22f) * cell
            val cy = y + (rank + 0.22f) * cell
            val points = arrayOf(
                bmp.getPixel(cx.toInt().coerceIn(0,bmp.width-1), cy.toInt().coerceIn(0,bmp.height-1)),
                bmp.getPixel((x+(file+0.78f)*cell).toInt().coerceIn(0,bmp.width-1), cy.toInt().coerceIn(0,bmp.height-1)),
                bmp.getPixel(cx.toInt().coerceIn(0,bmp.width-1), (y+(rank+0.78f)*cell).toInt().coerceIn(0,bmp.height-1)),
                bmp.getPixel((x+(file+0.78f)*cell).toInt().coerceIn(0,bmp.width-1), (y+(rank+0.78f)*cell).toInt().coerceIn(0,bmp.height-1))
            )
            val rgb = DoubleArray(3)
            for (p in points) {
                rgb[0] += Color.red(p) / 4.0
                rgb[1] += Color.green(p) / 4.0
                rgb[2] += Color.blue(p) / 4.0
            }
            for (k in 0..2) parity[group][k] += rgb[k]
            parityCount[group]++
        }
        for (g in 0..1) for (k in 0..2) parity[g][k] = parity[g][k] / parityCount[g]
        val contrast = sqrt((0..2).sumOf { k ->
            val d = parity[0][k] - parity[1][k]; d*d
        })
        // Checkerboard contrast is useful; reject near-uniform screen regions.
        return contrast
    }

    fun isFlipped(bmp: Bitmap, board: RectF): Boolean {
        // Chess.com normally exposes coordinates around the board. Without reliable
        // orientation metadata, default to White at the bottom.
        return false
    }
}
data class EngineResult(val bestMove: String, val whiteScoreCp: Int, val mate: Int? = null)

class Engine {
    private lateinit var proc: java.lang.Process
    private lateinit var w: BufferedWriter
    private lateinit var r: BufferedReader

    fun start(path: String) {
        proc = ProcessBuilder(path).redirectErrorStream(true).start()
        w = BufferedWriter(OutputStreamWriter(proc.outputStream))
        r = BufferedReader(InputStreamReader(proc.inputStream))
        cmd("uci"); await("uciok")
        cmd("setoption name Hash value 32")
        cmd("setoption name Threads value 2")
        cmd("isready"); await("readyok")
    }

    fun analyze(fen: String, depth: Int = 15): EngineResult? {
        cmd("position fen $fen")
        cmd("go depth $depth")
        var best: String? = null
        var scoreCp: Int? = null
        var mate: Int? = null
        while (true) {
            val line = r.readLine() ?: break
            if (line.startsWith("info ") && (line.contains(" score cp ") || line.contains(" score mate "))) {
                val tokens = line.split(Regex("\\s+"))
                val scoreIndex = tokens.indexOf("score")
                if (scoreIndex >= 0 && scoreIndex + 2 < tokens.size) {
                    when (tokens[scoreIndex + 1]) {
                        "cp" -> { scoreCp = tokens[scoreIndex + 2].toIntOrNull(); mate = null }
                        "mate" -> { mate = tokens[scoreIndex + 2].toIntOrNull(); scoreCp = null }
                    }
                }
            }
            if (line.startsWith("bestmove ")) {
                best = line.split(" ").getOrNull(1)
                break
            }
        }
        val move = best?.takeIf { it != "(none)" } ?: return null
        val sideToMove = fen.split(" ").getOrNull(1) ?: "w"
        val rawCp = scoreCp ?: (mate?.let { if (it > 0) 30000 else -30000 } ?: 0)
        val whiteCp = if (sideToMove == "b") -rawCp else rawCp
        return EngineResult(move, whiteCp, mate?.let { if (sideToMove == "b") -it else it })
    }

    fun stop() { try { cmd("quit"); proc.waitFor() } catch (_: Exception) {}; proc.destroy() }
    private fun cmd(c: String) { w.write(c); w.newLine(); w.flush() }
    private fun await(tok: String) { var l: String?; do { l=r.readLine() } while (l!=null && !l.startsWith(tok)) }
    private fun readLines(): List<String> {
        val out = mutableListOf<String>(); var l: String?
        do { l=r.readLine(); if (l!=null) out.add(l) } while (l!=null && !l.startsWith("bestmove"))
        return out
    }
}

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wm: WindowManager
    private lateinit var arrow: ArrowView
    private var mp: MediaProjection? = null
    private var vd: android.hardware.display.VirtualDisplay? = null
    private var ir: ImageReader? = null
    private val engine = Engine()
    @Volatile private var engineEnabled = false
    @Volatile private var engineReady = false
    @Volatile private var engineStatus = "Engine: preparing Stockfish…"
    @Volatile private var arrowsEnabled = true
    @Volatile private var showEvaluation = true
    @Volatile private var overlayVisible = true
    @Volatile private var engineDepth = 15
    private lateinit var launcher: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelContainer: ScrollView
    private lateinit var panelStatus: TextView
    private lateinit var evaluationView: TextView
    private var lastFen = ""
    private var sw = 0; private var sh = 0; private var dpi = 0

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels; dpi = dm.densityDpi
        arrow = ArrowView(this)
        wm.addView(arrow, WindowManager.LayoutParams(
            sw, sh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START })
        evaluationView = TextView(this).apply {
            text = "Eval: waiting"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(18, 10, 18, 10)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xDD20242B.toInt()); cornerRadius = 18f
            }
            visibility = View.GONE
        }
        wm.addView(evaluationView, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 18; y = 70 })

        // Compact floating launcher remains available when the full overlay is hidden.
        launcher = TextView(this).apply {
            text = "♟"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 10, 18, 10)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE20283A.toInt()); cornerRadius = 48f
                setStroke(2, 0xFF00C8FF.toInt())
            }
            setOnClickListener { togglePanel() }
        }
        wm.addView(launcher, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 120 })

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xF2222633.toInt()); cornerRadius = 24f
                setStroke(2, 0xFF46536B.toInt())
            }
        }
        panelContainer = ScrollView(this).apply { addView(panel) }
        addPanelLabel("CHESS OVERLAY")
        panelStatus = addPanelLabel("Board: waiting for screen capture")
        addPanelButton("ENGINE  •  OFF") {
            engineEnabled = !engineEnabled
            updatePanel()
            if (!engineEnabled) arrow.clear()
        }
        addPanelButton("ARROWS  •  ON") {
            arrowsEnabled = !arrowsEnabled
            if (!arrowsEnabled) arrow.clear()
            updatePanel()
        }
        addPanelButton("EVALUATION  •  ON") {
            showEvaluation = !showEvaluation
            updatePanel()
        }
        addPanelButton("ENGINE DEPTH  •  15") {
            engineDepth = when (engineDepth) { 10 -> 15; 15 -> 18; else -> 10 }
            updatePanel()
        }
        addPanelButton("HIDE FULL OVERLAY") {
            overlayVisible = false
            arrow.clear()
            panelContainer.visibility = View.GONE
            launcher.text = "♟"
            launcher.contentDescription = "Restore ChessOverlay"
        }
        addPanelButton("CLOSE MENU") { panelContainer.visibility = View.GONE }
        wm.addView(panelContainer, WindowManager.LayoutParams(
            (sw * 0.72f).toInt().coerceAtMost(620),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 190 })
        panelContainer.visibility = View.GONE
        startForeground(1, buildNotif())
        scope.launch {
            try {
                engineStatus = "Engine: downloading Stockfish…"
                val sf = ensureSf() ?: throw IOException("No compatible Stockfish binary found")
                engineStatus = "Engine: starting Stockfish…"
                engine.start(sf)
                engineReady = true
                engineStatus = "Engine: ready"
            } catch (e: Exception) {
                engineReady = false
                engineStatus = "Engine error: ${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) { if (::panelStatus.isInitialized) updatePanel() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code",-1) ?: return START_NOT_STICKY
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        data ?: return START_NOT_STICKY
        mp = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code,data)
        mp?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, Handler(Looper.getMainLooper()))
        ir = ImageReader.newInstance(sw,sh,PixelFormat.RGBA_8888,2)
        vd = mp!!.createVirtualDisplay("cc",sw,sh,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,ir!!.surface,null,null)
        scope.launch { while (isActive) { runCatching { tick() }; delay(300) } }
        return START_NOT_STICKY
    }

    private suspend fun tick() {
        val img = ir?.acquireLatestImage() ?: return
        val bmp = try {
            val pl = img.planes[0]
            val b = Bitmap.createBitmap(pl.rowStride/pl.pixelStride,sh,Bitmap.Config.ARGB_8888)
            b.copyPixelsFromBuffer(pl.buffer)
            if (b.width != sw) Bitmap.createBitmap(b,0,0,sw,sh) else b
        } finally { img.close() }
        val board = BoardFinder.find(bmp) ?: run { withContext(Dispatchers.Main) { arrow.clear() }; return }
        val flipped = BoardFinder.isFlipped(bmp,board)
        if (!engineEnabled || !overlayVisible || !engineReady) {
            withContext(Dispatchers.Main) {
                arrow.clear()
                evaluationView.visibility = View.GONE
            }
            return
        }
        val fen = buildFen(bmp,board)
        if (fen == lastFen) return
        lastFen = fen
        val result = engine.analyze(fen, engineDepth)
        withContext(Dispatchers.Main) {
            if (result == null) {
                arrow.clear()
                evaluationView.text = "Eval: unavailable"
            } else {
                if (arrowsEnabled) arrow.set(board,result.bestMove,MoveClass.BEST,flipped)
                else arrow.clear()
                val score = result.whiteScoreCp / 100.0
                evaluationView.text = result.mate?.let { "Mate: ${if (it > 0) "+" else ""}$it" }
                    ?: "Eval: ${if (score > 0) "+" else ""}%.2f".format(score)
                evaluationView.visibility = if (showEvaluation) View.VISIBLE else View.GONE
            }
        }
    }

    private fun buildFen(bmp: Bitmap, board: RectF): String {
        val cw = board.width()/8f; val ch = board.height()/8f
        val sb = StringBuilder()
        for (rank in 0..7) {
            var empty = 0
            for (file in 0..7) {
                val cx = (board.left+file*cw+cw/2f).toInt().coerceIn(0,bmp.width-1)
                val cy = (board.top+rank*ch+ch/2f).toInt().coerceIn(0,bmp.height-1)
                var tot = 0; var cnt = 0
                for (dx in -2..2) for (dy in -2..2) {
                    val px = bmp.getPixel((cx+dx).coerceIn(0,bmp.width-1),(cy+dy).coerceIn(0,bmp.height-1))
                    tot += (Color.red(px)+Color.green(px)+Color.blue(px))/3; cnt++
                }
                val avg = tot/cnt
                val pc = when { avg > 220 -> 'Q'; avg < 60 -> 'q'; else -> null }
                if (pc == null) empty++ else { if (empty>0) { sb.append(empty); empty=0 }; sb.append(pc) }
            }
            if (empty>0) sb.append(empty)
            if (rank<7) sb.append('/')
        }
        sb.append(" w - - 0 1")
        return sb.toString()
    }

    private fun ensureSf(): String? {
        // Android blocks execution of downloaded binaries from writable app-data
        // directories on many modern devices (SELinux / noexec). The build
        // packages Stockfish as a native library so PackageManager extracts it
        // into nativeLibraryDir, which is the supported executable location.
        val binary = File(applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!binary.isFile || binary.length() < 500_000) {
            throw IOException("Packaged Stockfish is missing for this CPU. Reinstall the latest APK.")
        }
        if (!binary.canExecute()) {
            throw IOException("Android did not mark the packaged Stockfish executable. Reinstall the latest APK.")
        }
        return binary.absolutePath
    }

    private fun addPanelLabel(value: String): TextView {
        return TextView(this).apply {
            text = value; textSize = 15f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; setPadding(8, 10, 8, 10)
            panel.addView(this)
        }
    }

    private fun addPanelButton(label: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f
            setOnClickListener { action() }
        }
        panel.addView(b, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun togglePanel() {
        if (!overlayVisible) {
            overlayVisible = true
            launcher.text = "♟"
        }
        panelContainer.visibility = if (panelContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updatePanel()
    }

    private fun updatePanel() {
        if (!::panel.isInitialized) return
        val buttons = (0 until panel.childCount).mapNotNull { panel.getChildAt(it) as? Button }
        if (buttons.size >= 5) {
            buttons[0].text = "ENGINE  •  " + if (engineEnabled) "ON" else "OFF"
            buttons[1].text = "ARROWS  •  " + if (arrowsEnabled) "ON" else "OFF"
            buttons[2].text = "EVALUATION  •  " + if (showEvaluation) "ON" else "OFF"
            buttons[3].text = "ENGINE DEPTH  •  $engineDepth"
        }
        if (::panelStatus.isInitialized) {
            panelStatus.text = if (engineReady) "Engine: ready • Board recognition: experimental" else engineStatus
        }
    }

    private fun buildNotif(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel("co","Chess Overlay",NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this,"co")
            .setContentTitle("ChessOverlay active")
            .setContentText("Analyzing chess.com...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true).build()
    }

    override fun onDestroy() {
        scope.cancel(); vd?.release(); ir?.close(); mp?.stop()
        if (arrow.isAttachedToWindow) wm.removeView(arrow)
        if (::evaluationView.isInitialized && evaluationView.isAttachedToWindow) wm.removeView(evaluationView)
        if (::launcher.isInitialized && launcher.isAttachedToWindow) wm.removeView(launcher)
        if (::panelContainer.isInitialized && panelContainer.isAttachedToWindow) wm.removeView(panelContainer)
        engine.stop(); super.onDestroy()
    }
    override fun onBind(i: Intent?) = null
}
