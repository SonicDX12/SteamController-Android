package com.steamcontroller.android

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.steamcontroller.android.databinding.ActivityDebugBinding
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.service.ControllerService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    // ms timestamps for Hz calculation
    private var lastReportTime = 0L
    private var reportCount = 0
    private var hzAccum = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_log_to_file) {
                Toast.makeText(this, "Log to File coming in V1.2.x", Toast.LENGTH_SHORT).show()
                true
            } else false
        }

        lifecycleScope.launch {
            ControllerService.stateFlow.filterNotNull().collect { state ->
                updateButtons(state)
                updateAxes(state)
                updateHz()
            }
        }

        lifecycleScope.launch {
            ControllerService.rawReportFlow.filterNotNull().collect { raw ->
                binding.tvRawHex.text = formatHex(raw)
            }
        }
    }

    private fun updateButtons(s: SteamControllerState) {
        /**
         * Toggles a chip's background + text colour to reflect button state.
         * V1.2 chips come in two shapes — rectangular pill (system/grips/back) and
         * circular (face buttons, DPAD, stick clicks). We pick the right drawable
         * pair based on `circular`.
         */
        fun chip(tv: TextView, mask: Int, circular: Boolean = false) {
            val active = s.isButtonPressed(mask)
            val bg = when {
                circular && active   -> R.drawable.chip_circle_bg_active
                circular              -> R.drawable.chip_circle_bg
                !circular && active   -> R.drawable.chip_bg_active
                else                  -> R.drawable.chip_bg
            }
            tv.setBackgroundResource(bg)
            tv.setTextColor(getColor(if (active) android.R.color.black else R.color.chip_inactive))
        }
        chip(binding.btnA,         Buttons.A,         circular = true)
        chip(binding.btnB,         Buttons.B,         circular = true)
        chip(binding.btnX,         Buttons.X,         circular = true)
        chip(binding.btnY,         Buttons.Y,         circular = true)
        chip(binding.btnLB,        Buttons.LB)
        chip(binding.btnRB,        Buttons.RB)
        chip(binding.btnSelect,    Buttons.VIEW)
        chip(binding.btnSteam,     Buttons.STEAM)
        chip(binding.btnStart,     Buttons.MENU)
        chip(binding.btnQA,        Buttons.QUICK_ACCESS)
        chip(binding.btnDU,        Buttons.DPAD_UP,    circular = true)
        chip(binding.btnDD,        Buttons.DPAD_DOWN,  circular = true)
        chip(binding.btnDL,        Buttons.DPAD_LEFT,  circular = true)
        chip(binding.btnDR,        Buttons.DPAD_RIGHT, circular = true)
        chip(binding.btnLS,        Buttons.LS,         circular = true)
        chip(binding.btnRS,        Buttons.RS,         circular = true)
        chip(binding.btnLGrip,     Buttons.GRIP_LT)
        chip(binding.btnRGrip,     Buttons.GRIP_RT)
        chip(binding.btnL4,        Buttons.L4)
        chip(binding.btnL5,        Buttons.L5)
        chip(binding.btnR4,        Buttons.R4)
        chip(binding.btnR5,        Buttons.R5)
    }

    private fun updateAxes(s: SteamControllerState) {
        // Triggers — already 0-32767, scale to 0-255 for ProgressBar.
        binding.pbLT.progress = s.leftTrigger / 128
        binding.tvLT.text = s.leftTrigger.toString()
        binding.pbRT.progress = s.rightTrigger / 128
        binding.tvRT.text = s.rightTrigger.toString()

        // Sticks — raw is ±32767 (Int16). Centre the bar by offsetting +32768 against max=65535.
        // ProgressBars are phone-layout-only; sw600dp / TV variants don't have them yet, so
        // the binding fields are nullable — use safe calls.
        fun setSignedBar(progressView: android.widget.ProgressBar?, raw: Int) {
            progressView?.progress = (raw + 32768).coerceIn(0, 65535)
        }
        setSignedBar(binding.pbLSX, s.leftJoyX.toInt())
        setSignedBar(binding.pbLSY, s.leftJoyY.toInt())
        setSignedBar(binding.pbRSX, s.rightJoyX.toInt())
        setSignedBar(binding.pbRSY, s.rightJoyY.toInt())
        binding.tvLSX.text = "X: %6d".format(s.leftJoyX.toInt())
        binding.tvLSY.text = "Y: %6d".format(s.leftJoyY.toInt())
        binding.tvRSX.text = "X: %6d".format(s.rightJoyX.toInt())
        binding.tvRSY.text = "Y: %6d".format(s.rightJoyY.toInt())

        // Trackpads — same signed range, same bar trick.
        setSignedBar(binding.pbLPX, s.leftPadX.toInt())
        setSignedBar(binding.pbLPY, s.leftPadY.toInt())
        setSignedBar(binding.pbRPX, s.rightPadX.toInt())
        setSignedBar(binding.pbRPY, s.rightPadY.toInt())
        binding.tvLPX.text = "X: %6d".format(s.leftPadX.toInt())
        binding.tvLPY.text = "Y: %6d".format(s.leftPadY.toInt())
        binding.tvRPX.text = "X: %6d".format(s.rightPadX.toInt())
        binding.tvRPY.text = "Y: %6d".format(s.rightPadY.toInt())

        binding.tvQW.text = "qW: %5d".format(s.quatW.toInt())
        binding.tvQX.text = "qX: %5d".format(s.quatX.toInt())
        binding.tvQY.text = "qY: %5d".format(s.quatY.toInt())
        binding.tvQZ.text = "qZ: %5d".format(s.quatZ.toInt())
    }

    private fun updateHz() {
        val now = System.currentTimeMillis()
        if (lastReportTime != 0L) {
            hzAccum += now - lastReportTime
            reportCount++
            if (reportCount >= 30) {
                val avgMs = hzAccum / reportCount
                val hz = if (avgMs > 0) 1000 / avgMs else 0
                binding.tvReportRate.text = "$hz Hz"
                reportCount = 0
                hzAccum = 0
            }
        }
        lastReportTime = now
    }

    private fun formatHex(buf: ByteArray): String {
        val sb = StringBuilder()
        buf.forEachIndexed { i, b ->
            sb.append("%02X ".format(b.toInt() and 0xFF))
            if (i % 16 == 15) sb.append("\n")
        }
        return sb.toString().trimEnd()
    }
}
