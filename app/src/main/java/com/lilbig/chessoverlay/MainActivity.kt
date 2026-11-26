package com.lilbig.chessoverlay

import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 16)
            setBackgroundColor(0xFF111722.toInt())
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "♞  CHESS ANALYSIS"; textSize = 21f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Button(this).apply { text = "✕"; setOnClickListener { finishAndRemoveTask() } })
        root.addView(top)
        root.addView(TextView(this).apply {
            text = "Interactive analysis board"; setTextColor(0xFF9BA8BD.toInt())
        })
        val board = ChessBoard(this)
        root.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        val side = Spinner(this)
        side.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Play as White", "Play as Black"))
        side.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                board.flipped = pos == 1; board.invalidate()
            }
        }
        root.addView(side)
        val status = TextView(this).apply { text = "Tap a piece, then its destination."; setTextColor(Color.WHITE) }
        root.addView(status)
        val row = LinearLayout(this)
        fun add(label: String, fn: () -> Unit) {
            row.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { fn() } },
                LinearLayout.LayoutParams(0, 48, 1f))
        }
        add("Undo") { board.undo(); status.text = "Move undone" }
        add("New game") { board.reset(); status.text = "Starting position" }
        add("Flip") { board.flipped = !board.flipped; board.invalidate() }
        root.addView(row)
        root.addView(Button(this).apply {
            text = "CLOSE / STOP"; isAllCaps = false
            setOnClickListener { finishAndRemoveTask() }
        })
        setContentView(root)
    }
}

private class ChessBoard(context: android.content.Context) : View(context) {
    private val initial = arrayOf("rnbqkbnr","pppppppp","........","........","........","........","PPPPPPPP","RNBQKBNR")
    private var board = Array(8) { initial[it].toCharArray() }
    private val undo = mutableListOf<Array<CharArray>>()
    var flipped = false
    private var selected: Pair<Int,Int>? = null
    private val light = Paint().apply { color = 0xFFE8E3D5.toInt() }
    private val dark = Paint().apply { color = 0xFF78917D.toInt() }
    private val mark = Paint().apply { color = 0x8874D6A0.toInt() }
    private val piece = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("serif", 0) }
    private val glyph = mapOf('K' to "♔",'Q' to "♕",'R' to "♖",'B' to "♗",'N' to "♘",'P' to "♙",'k' to "♚",'q' to "♛",'r' to "♜",'b' to "♝",'n' to "♞",'p' to "♟")
    fun reset() { board = Array(8) { initial[it].toCharArray() }; undo.clear(); selected = null; invalidate() }
    fun undo() { if (undo.isNotEmpty()) { board = undo.removeAt(undo.lastIndex); selected = null; invalidate() } }
    override fun onDraw(c: Canvas) {
        val size = minOf(width.toFloat(), height.toFloat()); val l = (width-size)/2; val t = (height-size)/2; val d = size/8
        for (vy in 0..7) for (vx in 0..7) {
            val x = if (flipped) 7-vx else vx; val y = if (flipped) 7-vy else vy
            val r = RectF(l+vx*d,t+vy*d,l+(vx+1)*d,t+(vy+1)*d)
            c.drawRect(r, if ((x+y)%2==0) light else dark)
            if (selected == Pair(y,x)) c.drawRect(r, mark)
            val p = board[y][x]
            if (p != '.') {
                piece.textSize = d*.76f; piece.color = if (p.isUpperCase()) Color.WHITE else 0xFF20242A.toInt()
                c.drawText(glyph[p] ?: p.toString(), r.centerX(), r.centerY()+d*.27f, piece)
            }
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val size=minOf(width.toFloat(),height.toFloat()); val l=(width-size)/2; val t=(height-size)/2; val d=size/8
        val vx=((e.x-l)/d).toInt(); val vy=((e.y-t)/d).toInt()
        if (vx !in 0..7 || vy !in 0..7) return true
        val x=if(flipped)7-vx else vx; val y=if(flipped)7-vy else vy
        val from=selected
        if(from==null) { if(board[y][x]!='.') selected=Pair(y,x) }
        else if(from.first==y && from.second==x) selected=null
        else { undo.add(Array(8){board[it].clone()}); board[y][x]=board[from.first][from.second]; board[from.first][from.second]='.'; selected=null }
        invalidate(); return true
    }
}