package com.example.a6300test

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var expEdit: EditText
    private lateinit var framesEdit: EditText
    private lateinit var gapEdit: EditText
    private lateinit var delayEdit: EditText
    private lateinit var isoEdit: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var shownVersion = -1
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("a6300", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 96, 24, 24)
        }

        fun field(label: String, key: String, default: String): EditText {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val e = EditText(this).apply {
                setText(prefs.getString(key, default))
                inputType = InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(e)
            root.addView(row)
            return e
        }

        expEdit = field("Exposure (s)", "exp", "120")
        framesEdit = field("Frames", "frames", "10")
        gapEdit = field("Gap between frames (s)", "gap", "3")
        delayEdit = field("Start delay (s)", "delay", "5")
        isoEdit = field("ISO", "iso", "1600")

        val startBtn = Button(this).apply { text = "START" }
        val abortBtn = Button(this).apply { text = "ABORT (closes the shutter)" }
        root.addView(startBtn)
        root.addView(abortBtn)

        statusView = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 16, 0, 16)
        }
        root.addView(statusView)

        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        startBtn.setOnClickListener {
            if (Session.running) {
                statusView.text = "A sequence is already running."
                return@setOnClickListener
            }
            val exp = (expEdit.text.toString().toIntOrNull() ?: 120).coerceIn(1, 300)
            val frames = (framesEdit.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99)
            val gap = (gapEdit.text.toString().toIntOrNull() ?: 3).coerceIn(0, 600)
            val delay = (delayEdit.text.toString().toIntOrNull() ?: 0).coerceIn(0, 600)
            val iso = (isoEdit.text.toString().toIntOrNull() ?: 1600).toString()
            prefs.edit()
                .putString("exp", exp.toString()).putString("frames", frames.toString())
                .putString("gap", gap.toString()).putString("delay", delay.toString())
                .putString("iso", iso).apply()
            val i = Intent(this, SequenceService::class.java).apply {
                action = SequenceService.ACTION_START
                putExtra(SequenceService.EXTRA_EXP, exp)
                putExtra(SequenceService.EXTRA_FRAMES, frames)
                putExtra(SequenceService.EXTRA_GAP, gap)
                putExtra(SequenceService.EXTRA_DELAY, delay)
                putExtra(SequenceService.EXTRA_ISO, iso)
            }
            startForegroundService(i)
        }

        abortBtn.setOnClickListener {
            startService(Intent(this, SequenceService::class.java).apply {
                action = SequenceService.ACTION_ABORT
            })
        }
    }

    override fun onResume() {
        super.onResume()
        shownVersion = -1
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun refresh() {
        statusView.text = (if (Session.running) "RUNNING: " else "") + Session.status
        if (Session.version != shownVersion) {
            shownVersion = Session.version
            logView.text = Session.text()
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
