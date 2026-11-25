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
        val c = cls ?: return
        val cw = b.width()/8f; val ch = b.height()/8f
        val from = center(mv.substring(0,2),b,cw,ch)
        val to = center(mv.substring(2,4),b,cw,ch)
        val col = c.argb

        fill.color = Color.argb(100,Color.red(col),Color.green(col),Color.blue(col))
        canvas.drawRect(squareRect(mv.substring(0,2),b,cw,ch),fill)
        fill.color = Color.argb(180,Color.red(col),Color.green(col),Color.blue(col))
        canvas.drawRect(squareRect(mv.substring(2,4),b,cw,ch),fill)

        val thick = cw*0.18f
        val angle = atan2((to.y-from.y).toDouble(),(to.x-from.x).toDouble()).toFloat()
        val hLen = thick*2.8f; val hWid = thick*1.6f
        val ex = cos(angle); val ey = sin(angle)
        val se = PointF(to.x-ex*hLen, to.y-ey*hLen)

        shadow.strokeWidth = thick+6f
        canvas.drawLine(from.x+3,from.y+3,se.x+3,se.y+3,shadow)
        stroke.color = col; stroke.strokeWidth = thick
        canvas.drawLine(from.x,from.y,se.x,se.y,stroke)

        val path = Path().apply {
            moveTo(to.x,to.y)
            lineTo(se.x+cos(angle+PI.toFloat()/2)*hWid, se.y+sin(angle+PI.toFloat()/2)*hWid)
            lineTo(se.x+cos(angle-PI.toFloat()/2)*hWid, se.y+sin(angle-PI.toFloat()/2)*hWid)
            close()
        }
        fill.color = col; canvas.drawPath(path,fill)

        val br = cw*0.36f; val bx = to.x+cw*0.3f; val by = to.y-ch*0.3f
        fill.color = 0xBB000000.toInt(); canvas.drawCircle(bx+3,by+3,br,fill)
        fill.color = col; canvas.drawCircle(bx,by,br,fill)
        txt.textSize = br*0.85f; canvas.drawText(c.emoji,bx,by+txt.textSize/3f,txt)
        txt.textSize = br*0.55f; txt.color = col
        canvas.drawText(c.label,bx,by+br+br*0.6f,txt)
        txt.color = Color.WHITE
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
    private val LIGHT = intArrayOf(0xFFEEDFB5.toInt(), 0xFFF0D9B5.toInt())
    private val DARK = intArrayOf(0xFFB58863.toInt(), 0xFF8CA456.toInt())

    fun find(bmp: Bitmap): RectF? {
        val w = bmp.width; val h = bmp.height; val y = h/2
        var bestRun = 0; var bestStart = -1; var runLen = 0; var lastLight = false
        for (x in 0 until w) {
            val px = bmp.getPixel(x,y)
            val isL = isClose(px,LIGHT); val isD = isClose(px,DARK)
            if (!isL && !isD) { runLen=0; continue }
            if (runLen==0 || isL!=lastLight) runLen++ else runLen++
            if (runLen>bestRun) { bestRun=runLen; bestStart=x-runLen+1 }
            lastLight=isL
        }
        if (bestRun < 50) return null
        val bw = bestRun; val bx = bestStart
        val by = (h/2-bw/2).coerceAtLeast(0)
        return RectF(bx.toFloat(),by.toFloat(),(bx+bw).toFloat(),(by+bw).toFloat())
    }

    fun isFlipped(bmp: Bitmap, board: RectF): Boolean {
        val cw = board.width()/8f; val ch = board.height()/8f
        var lightTop = 0
        for (file in 0..7) {
            val cx = (board.left+file*cw+cw/2f).toInt().coerceIn(0,bmp.width-1)
            val cy = (board.top+ch/2f).toInt().coerceIn(0,bmp.height-1)
            val px = bmp.getPixel(cx,cy)
            if ((Color.red(px)+Color.green(px)+Color.blue(px))/3 > 180) lightTop++
        }
        return lightTop > 4
    }

    private fun isClose(px: Int, targets: IntArray) = targets.any { t ->
        val dr=Color.red(px)-Color.red(t); val dg=Color.green(px)-Color.green(t); val db=Color.blue(px)-Color.blue(t)
        dr*dr+dg*dg+db*db < 2500
    }
}

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

    fun bestMove(fen: String, depth: Int = 15): String? {
        cmd("position fen $fen")
        cmd("go depth $depth")
        return readLines().lastOrNull { it.startsWith("bestmove") }
            ?.split(" ")?.getOrNull(1)?.takeIf { it != "(none)" }
    }

    fun score(fen: String, depth: Int = 13): Int {
        cmd("position fen $fen")
        cmd("go depth $depth")
        val lines = readLines()
        val info = lines.lastOrNull { it.contains("score cp") || it.contains("score mate") } ?: return 0
        return when {
            info.contains("score cp") -> info.substringAfter("score cp ").trim().split(" ")[0].toIntOrNull() ?: 0
            info.contains("score mate") -> { val m = info.substringAfter("score mate ").trim().split(" ")[0].toIntOrNull() ?: 1; if (m>0) 30000 else -30000 }
            else -> 0
        }
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
    private lateinit var engineToggle: TextView
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

        // Separate touchable floating switch; the transparent arrow layer remains non-touchable.
        engineToggle = TextView(this).apply {
            text = "ENGINE: OFF"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(22, 12, 22, 12)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE20283A.toInt())
                cornerRadius = 28f
                setStroke(2, 0xFF00C8FF.toInt())
            }
            setOnClickListener {
                engineEnabled = !engineEnabled
                text = if (engineEnabled) "ENGINE: ON" else "ENGINE: OFF"
                if (!engineEnabled) arrow.clear()
            }
        }
        wm.addView(engineToggle, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 120
        })
        startForeground(1, buildNotif())
        scope.launch { val sf = ensureSf(); if (sf != null) engine.start(sf) }
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
        if (!engineEnabled) {
            withContext(Dispatchers.Main) { arrow.clear() }
            return
        }
        val fen = buildFen(bmp,board)
        if (fen == lastFen) return
        lastFen = fen
        val best = engine.bestMove(fen) ?: return
        val mc = classify(0, isBest=true, isSac=false)
        withContext(Dispatchers.Main) { arrow.set(board,best,mc,flipped) }
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
        val dest = File(filesDir,"stockfish")
        if (dest.exists() && dest.length() > 500_000) { dest.setExecutable(true); return dest.absolutePath }
        return try {
            val abi = Build.SUPPORTED_ABIS[0]
            val url = if (abi.startsWith("x86"))
                "https://github.com/official-stockfish/Stockfish/releases/download/sf_16/stockfish-android-x86-64.zip"
            else
                "https://github.com/official-stockfish/Stockfish/releases/download/sf_16/stockfish-android-armv8.zip"
            ZipInputStream(URL(url).openStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) { if (!e.isDirectory && !e.name.contains(".")) { FileOutputStream(dest).use { zis.copyTo(it) }; break }; e = zis.nextEntry }
            }
            dest.setExecutable(true); dest.absolutePath
        } catch (ex: Exception) { null }
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
        if (::engineToggle.isInitialized && engineToggle.isAttachedToWindow) wm.removeView(engineToggle)
        engine.stop(); super.onDestroy()
    }
    override fun onBind(i: Intent?) = null
}
