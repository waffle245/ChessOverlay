package com.lilbig.chessoverlay

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity:AppCompatActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,36,28,28);setBackgroundColor(0xFF101722.toInt())}
  root.addView(TextView(this).apply{text="CHESS HELPER";textSize=24f;setTextColor(-1)})
  root.addView(TextView(this).apply{text="Floating offline study board. Make legal moves, then tap Think for Stockfish.";textSize=15f;setTextColor(0xFFCBD5E1.toInt());setPadding(0,12,0,12)})
  root.addView(Button(this).apply{text="START FLOATING PANEL";isAllCaps=false;setOnClickListener{startOverlay()}})
  root.addView(Button(this).apply{text="STOP FLOATING PANEL";isAllCaps=false;setOnClickListener{stopService(Intent(this@MainActivity,OverlayService::class.java))}})
  setContentView(root)
 }
 private fun startOverlay(){
  if(!Settings.canDrawOverlays(this)){AlertDialog.Builder(this).setTitle("Allow display over other apps").setMessage("Android needs this permission for the floating panel.")
   .setPositiveButton("Open settings"){_,_->startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}.setNegativeButton("Cancel",null).show();return}
  val i=Intent(this,OverlayService::class.java);if(android.os.Build.VERSION.SDK_INT>=26)startForegroundService(i)else startService(i);finish()
 }
}
