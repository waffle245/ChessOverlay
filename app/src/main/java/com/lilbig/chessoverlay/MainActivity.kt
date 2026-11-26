package com.lilbig.chessoverlay

import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var board: ChessBoard
    private lateinit var status: TextView
    private lateinit var eval: TextView
    private lateinit var moveLabel: TextView
    private val engineFile by lazy { File(applicationInfo.nativeLibraryDir, "libstockfish.so") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF101722.toInt()
        window.navigationBarColor = 0xFF101722.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 14)
            setBackgroundColor(0xFF101722.toInt())
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "♞  CHESS LAB"; textSize = 21f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "×"; contentDescription = "Close"; setOnClickListener { stopOverlayAndClose() }
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Manual analysis • enter positions and review"; textSize = 12f
            setTextColor(0xFF9BA8BD.toInt()); setPadding(2, 0, 2, 8)
        })
        val side = Spinner(this)
        side.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("White at bottom", "Black at bottom"))
        root.addView(side)
        board = ChessBoard(this)
        root.addView(board, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 4; bottomMargin = 4 })
        side.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                board.flipped = position == 1
                board.invalidate()
            }
        }
        eval = TextView(this).apply {
            text = "EVAL   —"; textSize = 14f; setTextColor(0xFFB9D8CB.toInt())
            setPadding(8, 7, 8, 3)
        }
        root.addView(eval)
        moveLabel = TextView(this).apply {
            text = "ENGINE   Ready when you are"; textSize = 12f
            setTextColor(0xFFD4DCEC.toInt()); setPadding(8, 2, 8, 6)
        }
        root.addView(moveLabel)
        status = TextView(this).apply {
            text = "Tap a piece, then its destination. This is a manual study board."
            textSize = 11f; setTextColor(0xFF8996AA.toInt()); setPadding(5, 2, 5, 6)
        }
        root.addView(status)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun addAction(label: String, action: () -> Unit) {
            row.addView(Button(this@MainActivity).apply {
                text = label; isAllCaps = false; textSize = 11f
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, 46, 1f).apply { setMargins(2, 2, 2, 2) })
        }
        addAction("Undo") { board.undo(); status.text = "Last edit undone"; clearAnalysis() }
        addAction("Reset") { board.reset(); status.text = "Starting position"; clearAnalysis() }
        addAction("Flip") { board.flipped = !board.flipped; board.invalidate() }
        root.addView(row)
        root.addView(Button(this).apply {
            text = "ANALYZE POSITION"; isAllCaps = false; textSize = 14f
            setTextColor(Color.WHITE); setBackgroundColor(0xFF176B59.toInt())
            setOnClickListener { analyzePosition() }
        }, LinearLayout.LayoutParams(-1, 50).apply { topMargin = 5 })
        root.addView(Button(this).apply {
            text = "STOP OVERLAY & CLOSE"; isAllCaps = false; textSize = 11f
            setOnClickListener { stopOverlayAndClose() }
        }, LinearLayout.LayoutParams(-1, 42).apply { topMargin = 4 })
        setContentView(root)
    }

    private fun clearAnalysis() {
        eval.text = "EVAL   —"
        moveLabel.text = "ENGINE   Ready when you are"
        board.bestMove = ""
        board.invalidate()
    }

    private fun stopOverlayAndClose() {
        stopService(Intent(this, OverlayService::class.java))
        finishAndRemoveTask()
    }

    private fun analyzePosition() {
        if (!engineFile.exists() || !engineFile.canExecute()) {
            status.text = "Stockfish executable is missing from this build."
            return
        }
        status.text = "Stockfish is analyzing this study position…"
        val fen = board.toFen()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runEngine(fen) }
            if (result.error != null) {
                status.text = "Engine error: " + result.error
                return@launch
            }
            eval.text = "EVAL   " + result.eval
            moveLabel.text = "ENGINE   Best continuation: " + result.best
            board.bestMove = result.best.take(4)
            board.invalidate()
            status.text = "Analysis complete. Use this board for study and post-game review."
        }
    }

    private data class EngineResult(val best: String, val eval: String, val error: String? = null)

    private fun runEngine(fen: String): EngineResult {
        return try {
            val process = ProcessBuilder(engineFile.absolutePath).redirectErrorStream(true).start()
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            fun send(command: String) { writer.write(command); writer.newLine(); writer.flush() }
            fun waitFor(prefix: String) {
                while (true) {
                    val line = reader.readLine() ?: throw IllegalStateException("Stockfish stopped")
                    if (line.startsWith(prefix)) return
                }
            }
            send("uci"); waitFor("uciok")
            send("isready"); waitFor("readyok")
            send("setoption name Threads value 2")
            send("setoption name Hash value 64")
            send("position fen $fen")
            send("go depth 13")
            var best = "—"
            var scoreText = "—"
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("info ") && line.contains(" score ")) {
                    val tokens = line.trim().split(Regex("\\s+"))
                    val scoreIndex = tokens.indexOf("score")
                    if (scoreIndex >= 0 && scoreIndex + 2 < tokens.size) {
                        val kind = tokens[scoreIndex + 1]
                        val value = tokens[scoreIndex + 2].toIntOrNull()
                        if (value != null) {
                            val whiteToMove = fen.split(" ").getOrNull(1) == "w"
                            val whiteScore = if (kind == "mate") {
                                val mate = if (whiteToMove) value else -value
                                "Mate " + (if (mate > 0) "+" else "") + mate
                            } else {
                                val cp = if (whiteToMove) value else -value
                                (if (cp >= 0) "+" else "") + String.format("%.2f", cp / 100.0)
                            }
                            scoreText = whiteScore + " (White POV)"
                        }
                    }
                }
                if (line.startsWith("bestmove ")) {
                    best = line.split(" ").getOrNull(1) ?: "—"
                    break
                }
            }
            send("quit")
            process.destroy()
            EngineResult(best, scoreText)
        } catch (e: Exception) {
            EngineResult("—", "—", e.message ?: "unknown failure")
        }
    }
}

private class ChessBoard(context: android.content.Context) : View(context) {
    private val initial = arrayOf("rnbqkbnr", "pppppppp", "........", "........", "........", "........", "PPPPPPPP", "RNBQKBNR")
    private var cells = Array(8) { initial[it].toCharArray() }
    private val history = mutableListOf<Array<CharArray>>()
    private var whiteToMove = true
    var flipped = false
    var bestMove = ""
    private var selected: Pair<Int, Int>? = null
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8E3D5.toInt() }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF78917D.toInt() }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8874D6A0.toInt() }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88404F45.toInt() }
    private val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("serif", Typeface.NORMAL)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC173B32.toInt(); strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
    }
    private val glyph = mapOf(
        'K' to "♔", 'Q' to "♕", 'R' to "♖", 'B' to "♗", 'N' to "♘", 'P' to "♙",
        'k' to "♚", 'q' to "♛", 'r' to "♜", 'b' to "♝", 'n' to "♞", 'p' to "♟"
    )

    fun reset() {
        cells = Array(8) { initial[it].toCharArray() }
        history.clear(); selected = null; bestMove = ""; whiteToMove = true
        invalidate()
    }

    fun undo() {
        if (history.isNotEmpty()) {
            cells = history.removeAt(history.lastIndex)
            whiteToMove = !whiteToMove; selected = null; bestMove = ""; invalidate()
        }
    }

    fun toFen(): String {
        val placement = (0..7).joinToString("/") { y ->
            var empty = 0
            val row = StringBuilder()
            for (x in 0..7) {
                val p = cells[y][x]
                if (p == '.') empty++ else {
                    if (empty > 0) { row.append(empty); empty = 0 }
                    row.append(p)
                }
            }
            if (empty > 0) row.append(empty)
            row.toString()
        }
        return "$placement ${if (whiteToMove) "w" else "b"} - - 0 1"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width.toFloat(), height.toFloat())
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val cell = size / 8f
        for (vy in 0..7) for (vx in 0..7) {
            val x = if (flipped) 7 - vx else vx
            val y = if (flipped) 7 - vy else vy
            val rect = RectF(left + vx * cell, top + vy * cell, left + (vx + 1) * cell, top + (vy + 1) * cell)
            canvas.drawRect(rect, if ((x + y) % 2 == 0) light else dark)
            if (selected == Pair(y, x)) canvas.drawRect(rect, selectedPaint)
            val p = cells[y][x]
            if (p != '.') {
                piecePaint.textSize = cell * .76f
                piecePaint.color = if (p.isUpperCase()) Color.WHITE else 0xFF20242A.toInt()
                piecePaint.setShadowLayer(2f, 0f, 1f, 0x66000000)
                canvas.drawText(glyph[p] ?: p.toString(), rect.centerX(), rect.centerY() + cell * .27f, piecePaint)
                piecePaint.clearShadowLayer()
            }
        }
        if (bestMove.length >= 4) {
            val a = squarePoint(bestMove.substring(0, 2), left, top, cell)
            val b = squarePoint(bestMove.substring(2, 4), left, top, cell)
            canvas.drawLine(a.first, a.second, b.first, b.second, arrowPaint)
            val angle = Math.atan2((b.second - a.second).toDouble(), (b.first - a.first).toDouble()).toFloat()
            val head = Path().apply {
                moveTo(b.first, b.second)
                lineTo(b.first - 18f * kotlin.math.cos(angle - .55f), b.second - 18f * kotlin.math.sin(angle - .55f))
                lineTo(b.first - 18f * kotlin.math.cos(angle + .55f), b.second - 18f * kotlin.math.sin(angle + .55f))
                close()
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = arrowPaint.color; style = Paint.Style.FILL }
            canvas.drawPath(head, fill)
        }
    }

    private fun squarePoint(square: String, left: Float, top: Float, cell: Float): Pair<Float, Float> {
        val file = square[0] - 'a'
        val rank = 8 - (square[1] - '0')
        val x = if (flipped) 7 - file else file
        val y = if (flipped) 7 - rank else rank
        return Pair(left + (x + .5f) * cell, top + (y + .5f) * cell)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val size = minOf(width.toFloat(), height.toFloat())
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val cell = size / 8f
        val vx = ((event.x - left) / cell).toInt()
        val vy = ((event.y - top) / cell).toInt()
        if (vx !in 0..7 || vy !in 0..7) return true
        val x = if (flipped) 7 - vx else vx
        val y = if (flipped) 7 - vy else vy
        val from = selected
        if (from == null) {
            if (cells[y][x] != '.') selected = Pair(y, x)
        } else if (from.first == y && from.second == x) {
            selected = null
        } else {
            val moving = cells[from.first][from.second]
            if (cells[y][x] != '.' && cells[y][x].isUpperCase() == moving.isUpperCase()) {
                selected = Pair(y, x)
            } else {
                history.add(Array(8) { cells[it].clone() })
                cells[y][x] = moving
                cells[from.first][from.second] = '.'
                if (moving.lowercaseChar() == 'p' && (y == 0 || y == 7)) {
                    cells[y][x] = if (moving.isUpperCase()) 'Q' else 'q'
                }
                whiteToMove = !whiteToMove
                selected = null
                bestMove = ""
            }
        }
        invalidate()
        return true
    }
}