package com.steamcontroller.android

import android.os.Bundle
import android.widget.TextView
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
        fun chip(tv: TextView, mask: Int) {
            val active = s.isButtonPressed(mask)
            tv.setBackgroundResource(if (active) R.drawable.chip_bg_active else R.drawable.chip_bg)
            tv.setTextColor(getColor(if (active) android.R.color.black else R.color.chip_inactive))
        }
        chip(binding.btnA,         Buttons.A)
        chip(binding.btnB,         Buttons.B)
        chip(binding.btnX,         Buttons.X)
        chip(binding.btnY,         Buttons.Y)
        chip(binding.btnLB,        Buttons.LB)
        chip(binding.btnRB,        Buttons.RB)
        chip(binding.btnSelect,    Buttons.VIEW)
        chip(binding.btnSteam,     Buttons.STEAM)
        chip(binding.btnStart,     Buttons.MENU)
        chip(binding.btnQA,        Buttons.QUICK_ACCESS)
        chip(binding.btnDU,        Buttons.DPAD_UP)
        chip(binding.btnDD,        Buttons.DPAD_DOWN)
        chip(binding.btnDL,        Buttons.DPAD_LEFT)
        chip(binding.btnDR,        Buttons.DPAD_RIGHT)
        chip(binding.btnLS,        Buttons.LS)
        chip(binding.btnRS,        Buttons.RS)
        chip(binding.btnLGrip,     Buttons.GRIP_LT)
        chip(binding.btnRGrip,     Buttons.GRIP_RT)
        chip(binding.btnL4,        Buttons.L4)
        chip(binding.btnL5,        Buttons.L5)
        chip(binding.btnR4,        Buttons.R4)
        chip(binding.btnR5,        Buttons.R5)
    }

    private fun updateAxes(s: SteamControllerState) {
        binding.pbLT.progress = s.leftTrigger / 128  // 0-32767 → 0-255 for progress bar
        binding.tvLT.text = s.leftTrigger.toString()
        binding.pbRT.progress = s.rightTrigger / 128
        binding.tvRT.text = s.rightTrigger.toString()

        binding.tvLSX.text = "X: %6d".format(s.leftJoyX.toInt())
        binding.tvLSY.text = "Y: %6d".format(s.leftJoyY.toInt())
        binding.tvRSX.text = "X: %6d".format(s.rightJoyX.toInt())
        binding.tvRSY.text = "Y: %6d".format(s.rightJoyY.toInt())

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
