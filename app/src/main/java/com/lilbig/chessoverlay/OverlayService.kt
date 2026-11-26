package com.lilbig.chessoverlay

import android.app.*
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import com.github.bhlangonijr.chesslib.*
import kotlinx.coroutines.*
import java.io.*
import java.util.Locale
import kotlin.math.*

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var panel: LinearLayout
    private lateinit var boardView: StudyBoardView
    private lateinit var status: TextView
    private lateinit var eval: TextView
    private lateinit var engineText: TextView
    private lateinit var logo: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var logoLp: WindowManager.LayoutParams
    private lateinit var colorButton: Button
    private lateinit var controlsLayout: LinearLayout
    private var autoThink = false
    private var boardOnly = false
    private var blackAtBottom = false
    private var scale = 0.78f
    private var ready = false
    private var busy = false
    private var proc: java.lang.Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var best = ""
    private val sf: java.io.File by lazy { java.io.File(applicationInfo.nativeLibraryDir, "libstockfish.so") }

    override fun onCreate() {
        super.onCreate()
        startForeground(7, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPanel()
        lp = WindowManager.LayoutParams(dp((300*scale).toInt()), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(58) }
        wm.addView(panel, lp)
        logo = TextView(this).apply {
            text = "♟"; textSize = 27f; gravity = Gravity.CENTER; setTextColor(0xFF63E477.toInt())
            background = GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(0xF2222633.toInt());setStroke(dp(2),0xFF63E477.toInt()) }
            elevation=dp(8).toFloat()
            setOnClickListener { expandPanel() }
            setOnTouchListener(LogoDrag())
        }
        logoLp = WindowManager.LayoutParams(dp(48),dp(48),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=dp(12); y=dp(180) }
        startEngine()
    }

    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(10),dp(10))
            background = GradientDrawable().apply { setColor(0xF2222633.toInt()); cornerRadius=dp(18).toFloat(); setStroke(dp(1),0xFF46536B.toInt()) }
        }
        val header = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val dragTitle=label("♟  Chess Helper",19f,0xFF50DC69.toInt(),true)
        header.addView(dragTitle,LinearLayout.LayoutParams(0,dp(38),1f))
        dragTitle.setOnTouchListener(PanelDrag())
        header.addView(label("▣",19f,Color.LTGRAY).apply { setPadding(dp(7),0,dp(7),0);setOnClickListener{setBoardOnly(!boardOnly)} })
        header.addView(label("−",25f,Color.LTGRAY).apply {
            setPadding(dp(9),0,dp(9),0);setOnClickListener{collapsePanel()}
        })
        header.addView(label("×",24f,Color.LTGRAY).apply {
            setPadding(dp(8),0,dp(4),0);setOnClickListener{stopSelf()}
        })
        panel.addView(header)
        status=label("Make moves on this offline study board.",13f,Color.WHITE)
        panel.addView(status)
        boardView=StudyBoardView(this) {
            best=""; eval.text="EVAL   —"; engineText.text="ENGINE   Position changed"
            status.text="Position updated • Tap THINK to analyze"
            if(autoThink && boardView.sideToMove()==playerSide()) analyze()
        }
        panel.addView(boardView,LinearLayout.LayoutParams(-1,dp((280*scale).toInt())).apply { topMargin=dp(4); bottomMargin=dp(5) })
        val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        controlsLayout=rows
        fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; rows.addView(this) }
        fun btn(r:LinearLayout,t:String,fn:(Button)->Unit):Button {
            val b=Button(this).apply { text=t; isAllCaps=false; textSize=11f; setTextColor(Color.WHITE)
                background=GradientDrawable().apply { setColor(0xFF374154.toInt()); cornerRadius=dp(10).toFloat() }
                setOnClickListener { fn(this) } }
            r.addView(b,LinearLayout.LayoutParams(0,dp(44),1f).apply { setMargins(dp(2),dp(2),dp(2),dp(2)) })
            return b
        }
        var r=row()
        btn(r,"Undo move"){boardView.undo();clear()}
        btn(r,"New game"){boardView.reset();clear()}
        r=row()
        colorButton=btn(r,"My pieces: White"){blackAtBottom=!blackAtBottom;boardView.flipped=blackAtBottom;boardView.invalidate();it.text=if(blackAtBottom)"My pieces: Black" else "My pieces: White"}
        btn(r,"Think"){analyze()}
        r=row()
        val sizeLabel=label("Board size",11f,Color.LTGRAY)
        r.addView(sizeLabel,LinearLayout.LayoutParams(dp(72),dp(42)))
        val sizeSlider=SeekBar(this).apply {
            max=100;progress=51
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean){changeScale(.55f+(progress/100f)*.45f)}
                override fun onStartTrackingTouch(seekBar:SeekBar?){}
                override fun onStopTrackingTouch(seekBar:SeekBar?){}
            })
        }
        r.addView(sizeSlider,LinearLayout.LayoutParams(0,dp(42),1f))
        r=row()
        btn(r,"Auto think: OFF"){autoThink=!autoThink;it.text=if(autoThink)"Auto think: ON" else "Auto think: OFF";status.text=if(autoThink)"Auto-think enabled for your turns on this study board" else "Auto-think disabled"}
        btn(r,"Board only"){setBoardOnly(!boardOnly)}
        r=row()
        btn(r,"Best arrow: ON"){boardView.showArrow=!boardView.showArrow;boardView.bestMove=if(boardView.showArrow)best else "";boardView.invalidate();it.text=if(boardView.showArrow)"Best arrow: ON" else "Best arrow: OFF"}
        btn(r,"Reset view"){lp.x=0;lp.y=dp(58);wm.updateViewLayout(panel,lp);changeScale(.78f)}
        panel.addView(rows)
        eval=label("EVAL   —",14f,0xFFB4E1CC.toInt());panel.addView(eval)
        engineText=label("ENGINE   Starting Stockfish…",12f,Color.WHITE);panel.addView(engineText)
        panel.addView(label("Offline study board",10f,Color.LTGRAY).apply{gravity=Gravity.CENTER})
    }

    private fun playerSide()=if(blackAtBottom) Side.BLACK else Side.WHITE
    private fun setBoardOnly(enabled:Boolean){
        boardOnly=enabled
        controlsLayout.visibility=if(enabled)View.GONE else View.VISIBLE
        status.visibility=if(enabled)View.GONE else View.VISIBLE
        eval.visibility=if(enabled)View.GONE else View.VISIBLE
        engineText.visibility=if(enabled)View.GONE else View.VISIBLE
        panel.setPadding(if(enabled)dp(3) else dp(8),if(enabled)dp(3) else dp(6),if(enabled)dp(3) else dp(8),if(enabled)dp(3) else dp(8))
        if(panel.isAttachedToWindow)wm.updateViewLayout(panel,lp)
    }
    private fun changeScale(value:Float) {
        scale=value.coerceIn(.55f,1.0f)
        lp.width=dp((300*scale).toInt())
        boardView.layoutParams=boardView.layoutParams.apply { height=dp((280*scale).toInt()) }
        panel.setPadding(dp(8),dp(6),dp(8),dp(8))
        if(panel.isAttachedToWindow)wm.updateViewLayout(panel,lp)
    }
    private fun collapsePanel() {
        if(panel.isAttachedToWindow)wm.removeView(panel)
        if(!logo.isAttachedToWindow)wm.addView(logo,logoLp)
    }
    private fun expandPanel() {
        if(logo.isAttachedToWindow)wm.removeView(logo)
        if(!panel.isAttachedToWindow)wm.addView(panel,lp)
    }
    private fun label(s:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply {
        text=s;textSize=size;setTextColor(color);setPadding(dp(4),dp(4),dp(4),dp(4))
        if(bold)typeface=Typeface.DEFAULT_BOLD
    }
    private fun clear(){best="";eval.text="EVAL   —";engineText.text="ENGINE   Ready when you are";boardView.bestMove="";boardView.invalidate()}
    private fun startEngine(){scope.launch{
        try {
            val trio=withContext(Dispatchers.IO){
                if(!sf.exists()||!sf.canExecute())throw IOException("Stockfish missing; reinstall APK")
                val p=ProcessBuilder(sf.absolutePath).redirectErrorStream(true).start()
                val w=BufferedWriter(OutputStreamWriter(p.outputStream));val r=BufferedReader(InputStreamReader(p.inputStream))
                fun send(s:String){w.write(s);w.newLine();w.flush()}
                send("uci");while(r.readLine()?.startsWith("uciok")!=true){}
                send("setoption name Threads value 2");send("setoption name Hash value 64")
                send("isready");while(r.readLine()?.startsWith("readyok")!=true){}
                Triple(p,w,r)
            }
            proc=trio.first;writer=trio.second;reader=trio.third;ready=true;engineText.text="ENGINE   Stockfish ready"
        }catch(e:Exception){engineText.text="ENGINE   "+(e.message?: "Failed to start")}
    }}
    private fun analyze(){
        if(!ready){status.text="Stockfish is still starting…";return};if(busy)return
        busy=true;status.text="Analyzing study position…";val fen=boardView.fen()
        scope.launch{
            try{
                val result=withContext(Dispatchers.IO){synchronized(this@OverlayService){
                    val w=writer?:error("Engine unavailable");val r=reader?:error("Engine unavailable")
                    w.write("position fen "+fen+"\ngo depth 14\n");w.flush()
                    var bm="";var score="—"
                    while(true){val line=r.readLine()?:break
                        if(line.startsWith("info ")&&line.contains(" score cp ")){
                            val t=line.split(Regex("\\s+"));val ix=t.indexOf("score")
                            if(ix>=0&&ix+2<t.size){val cp=t[ix+2].toIntOrNull();if(cp!=null){
                                val white=if(fen.split(" ").getOrNull(1)=="b")-cp else cp
                                score=String.format(Locale.US,"%+.2f",white/100.0)
                            }}
                        }
                        if(line.startsWith("bestmove ")){bm=line.split(" ").getOrNull(1)?:"";break}
                    };Pair(bm,score)
                }}
                best=result.first;boardView.bestMove=if(boardView.showArrow)best else "";boardView.invalidate()
                eval.text="EVAL   "+result.second;engineText.text="ENGINE   Best move: "+best.ifBlank{"none"}
                status.text=if(best.isBlank()||best=="(none)")"No legal move." else "Analysis complete • arrow remains visible"
            }catch(e:Exception){status.text="Analysis failed: "+(e.message?:"unknown")}
            finally{busy=false}
        }
    }
    private inner class PanelDrag:View.OnTouchListener{
        var sx=0f;var sy=0f;var ox=0;var oy=0
        override fun onTouch(v:View,e:MotionEvent):Boolean{
            when(e.action){MotionEvent.ACTION_DOWN->{sx=e.rawX;sy=e.rawY;ox=lp.x;oy=lp.y;return true}
                MotionEvent.ACTION_MOVE->{lp.x=ox+(e.rawX-sx).toInt();lp.y=oy+(e.rawY-sy).toInt();runCatching{wm.updateViewLayout(panel,lp)};return true}}
            return false
        }
    }
    private inner class LogoDrag:View.OnTouchListener{
        var sx=0f;var sy=0f;var ox=0;var oy=0;var moved=false
        override fun onTouch(v:View,e:MotionEvent):Boolean{
            when(e.action){MotionEvent.ACTION_DOWN->{sx=e.rawX;sy=e.rawY;ox=logoLp.x;oy=logoLp.y;moved=false;return true}
                MotionEvent.ACTION_MOVE->{if(abs(e.rawX-sx)>dp(6)||abs(e.rawY-sy)>dp(6))moved=true
                    logoLp.x=ox+(e.rawX-sx).toInt();logoLp.y=oy+(e.rawY-sy).toInt();runCatching{wm.updateViewLayout(logo,logoLp)};return true}
                MotionEvent.ACTION_UP->{if(!moved)expandPanel();return true}}
            return true
        }
    }
    private fun notification():Notification{
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("study_overlay","Study overlay",NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this,"study_overlay").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Chess study overlay active").setContentText("Manual offline analysis board").setOngoing(true).build()
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onDestroy(){
        runCatching{writer?.write("quit\n");writer?.flush()};proc?.destroy()
        if(::panel.isInitialized&&panel.isAttachedToWindow)wm.removeView(panel)
        if(::logo.isInitialized&&logo.isAttachedToWindow)wm.removeView(logo)
        scope.cancel();super.onDestroy()
    }
    override fun onBind(intent:Intent?)=null
}

private class StudyBoardView(context:android.content.Context,private val changed:(String)->Unit):View(context){
    private val game=Board()
    private val history=mutableListOf<String>()
    var flipped=false;var showArrow=true;var bestMove=""
    private var selected:Square?=null
    private var targets=emptySet<Square>()
    // Match the screenshot's sage-green / pale-cream board. a1 is pale, b1 is green.
    private val light=0xFFEEF0D2.toInt()
    private val dark=0xFF789653.toInt()
    // Use solid chess silhouettes for both sides; paint white pieces white rather than using hollow white-piece glyphs.
    private val glyphs=mapOf(
        Piece.WHITE_KING to "♚",Piece.BLACK_KING to "♚",
        Piece.WHITE_QUEEN to "♛",Piece.BLACK_QUEEN to "♛",
        Piece.WHITE_ROOK to "♜",Piece.BLACK_ROOK to "♜",
        Piece.WHITE_BISHOP to "♝",Piece.BLACK_BISHOP to "♝",
        Piece.WHITE_KNIGHT to "♞",Piece.BLACK_KNIGHT to "♞",
        Piece.WHITE_PAWN to "♟",Piece.BLACK_PAWN to "♟"
    )
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
    private val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;typeface=Typeface.create("serif",Typeface.BOLD);style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
    fun fen()=game.fen
    fun sideToMove()=game.sideToMove
    fun reset(){game.loadFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");history.clear();selected=null;targets=emptySet();bestMove="";invalidate();changed(game.fen)}
    fun undo(){if(history.isNotEmpty()){game.undoMove();history.removeAt(history.lastIndex);selected=null;targets=emptySet();bestMove="";invalidate();changed(game.fen)}}
    private fun square(file:Int,rank:Int)=Square.valueOf(("" + ('a'+file) + rank).uppercase())
    override fun onDraw(c:Canvas){
        super.onDraw(c);val size=min(width.toFloat(),height.toFloat());val l=(width-size)/2;val t=(height-size)/2;val cell=size/8
        for(vy in 0..7)for(vx in 0..7){
            val f=if(flipped)7-vx else vx;val r=if(flipped)vy+1 else 8-vy;val sq=square(f,r)
            val rect=RectF(l+vx*cell,t+vy*cell,l+(vx+1)*cell,t+(vy+1)*cell)
            val pale=((f+r)and 1)==1;c.drawRect(rect,Paint().apply{color=if(pale)light else dark})
            if(sq==selected)c.drawRect(rect,Paint().apply{color=0x6675B7F2})
            val p=game.getPiece(sq)
            if(p!=Piece.NONE){
                val symbol=glyphs[p]?:p.fanSymbol
                val base=cell*.83f;val y=rect.centerY()+base*.34f
                outline.textSize=base;outline.strokeWidth=cell*.045f
                fill.textSize=base
                if(p.pieceSide==Side.WHITE){outline.color=0xFF6C716A.toInt();fill.color=0xFFFFFEF7.toInt()}
                else{outline.color=0xFF171B16.toInt();fill.color=0xFF45494B.toInt()}
                c.drawText(symbol,rect.centerX(),y,fill);c.drawText(symbol,rect.centerX(),y,outline)
            }
            if(targets.contains(sq)){val mark=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0x88302D26.toInt();style=if(p==Piece.NONE)Paint.Style.FILL else Paint.Style.STROKE;strokeWidth=cell*.07f}
                c.drawCircle(rect.centerX(),rect.centerY(),if(p==Piece.NONE)cell*.12f else cell*.43f,mark)}
            val coord=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(pale)dark else light;textSize=cell*.15f;typeface=Typeface.DEFAULT_BOLD}
            if(vx==0)c.drawText(r.toString(),rect.left+cell*.12f,rect.top+cell*.18f,coord)
            if(vy==7)c.drawText(('a'+f).toString(),rect.right-cell*.12f,rect.bottom-cell*.04f,coord)
        }
        if(showArrow&&bestMove.length>=4)drawArrow(c,l,t,cell)
    }
    private fun drawArrow(c:Canvas,l:Float,t:Float,cell:Float){
        fun center(s:String):PointF{val f=s[0]-'a';val r=s[1]-'1';val x=if(flipped)7-f else f;val y=if(flipped)r else 7-r;return PointF(l+(x+.5f)*cell,t+(y+.5f)*cell)}
        val a=center(bestMove.substring(0,2));val b=center(bestMove.substring(2,4));val dx=b.x-a.x;val dy=b.y-a.y;val len=hypot(dx,dy);if(len<1)return
        val ux=dx/len;val uy=dy/len;val px=-uy;val py=ux;val bx=b.x-ux*cell*.8f;val by=b.y-uy*cell*.8f
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=0xAA20242B.toInt();strokeWidth=cell*.18f;strokeCap=Paint.Cap.ROUND}
        c.drawLine(a.x,a.y,bx,by,paint);val path=Path().apply{moveTo(b.x,b.y);lineTo(bx+px*cell*.38f,by+py*cell*.38f);lineTo(bx-px*cell*.38f,by-py*cell*.38f);close()}
        c.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=paint.color})
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(e.action!=MotionEvent.ACTION_UP)return true
        val size=min(width.toFloat(),height.toFloat());val l=(width-size)/2;val t=(height-size)/2;val cell=size/8
        val vx=((e.x-l)/cell).toInt();val vy=((e.y-t)/cell).toInt();if(vx !in 0..7||vy !in 0..7)return true
        val f=if(flipped)7-vx else vx;val r=if(flipped)vy+1 else 8-vy;val to=square(f,r);val p=game.getPiece(to);val moves=game.legalMoves()
        if(selected==null){if(p!=Piece.NONE&&p.pieceSide==game.sideToMove){selected=to;targets=moves.filter{it.from==to}.map{it.to}.toSet()}}
        else{
            val from=selected!!;val options=moves.filter{it.from==from&&it.to==to}
            if(options.isNotEmpty()){
                val chosen=options.firstOrNull{it.promotion==Piece.WHITE_QUEEN||it.promotion==Piece.BLACK_QUEEN}?:options.first()
                if(game.doMove(chosen)){history.add(chosen.toString());selected=null;targets=emptySet();bestMove="";invalidate();changed(game.fen);return true}
            }
            if(p!=Piece.NONE&&p.pieceSide==game.sideToMove){selected=to;targets=moves.filter{it.from==to}.map{it.to}.toSet()}else{selected=null;targets=emptySet()}
        };invalidate();return true
    }
}
