package com.steamcontroller.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.steamcontroller.android.databinding.ActivityCalibrationBinding
import com.steamcontroller.android.input.StickCalibration
import com.steamcontroller.android.service.ControllerService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding

    private var lastLeftRawX = 0
    private var lastLeftRawY = 0
    private var lastRightRawX = 0
    private var lastRightRawY = 0

    private var leftCal = StickCalibration()
    private var rightCal = StickCalibration()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reset) {
                resetAll()
                true
            } else false
        }

        leftCal = Prefs.getLeftCalibration(this)
        rightCal = Prefs.getRightCalibration(this)
        bindUiFromState()

        binding.btnTestRumble.setOnClickListener {
            if (ControllerService.modeFlow.value == ControllerService.InjectionMode.NONE) {
                Toast.makeText(this, "Start the service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, ControllerService::class.java).apply {
                action = ControllerService.ACTION_TEST_RUMBLE
            }
            startService(intent)
        }

        // Rumble intensity slider — applied live to all subsequent rumble events
        val savedIntensity = Prefs.getRumbleIntensity(this)
        binding.sliderRumbleIntensity.value = savedIntensity.toFloat()
        binding.tvRumbleIntensity.text = "$savedIntensity%"
        binding.sliderRumbleIntensity.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            val pct = value.toInt()
            binding.tvRumbleIntensity.text = "$pct%"
            Prefs.setRumbleIntensity(this, pct)
        })

        // Live preview from the running service
        lifecycleScope.launch {
            ControllerService.stateFlow.filterNotNull().collect { state ->
                lastLeftRawX  = state.leftJoyX.toInt()
                lastLeftRawY  = state.leftJoyY.toInt()
                lastRightRawX = state.rightJoyX.toInt()
                lastRightRawY = state.rightJoyY.toInt()

                val (lx, ly) = leftCal.apply(lastLeftRawX, lastLeftRawY)
                val (rx, ry) = rightCal.apply(lastRightRawX, lastRightRawY)

                binding.padLeft.setPosition(lx / 32767f, ly / 32767f)
                binding.padRight.setPosition(rx / 32767f, ry / 32767f)

                binding.tvLeftRaw.text  = "raw: %5d, %5d".format(lastLeftRawX, lastLeftRawY)
                binding.tvRightRaw.text = "raw: %5d, %5d".format(lastRightRawX, lastRightRawY)
            }
        }

        wireControls()
    }

    private fun bindUiFromState() {
        binding.sliderLeftDeadzone.value  = leftCal.deadzonePercent.toFloat()
        binding.sliderRightDeadzone.value = rightCal.deadzonePercent.toFloat()
        binding.tvLeftDeadzone.text  = "${leftCal.deadzonePercent}%"
        binding.tvRightDeadzone.text = "${rightCal.deadzonePercent}%"
        binding.switchLeftInvertY.isChecked  = leftCal.invertY
        binding.switchRightInvertY.isChecked = rightCal.invertY

        binding.padLeft.deadzoneFraction  = leftCal.deadzonePercent / 100f
        binding.padRight.deadzoneFraction = rightCal.deadzonePercent / 100f
    }

    private fun wireControls() {
        binding.sliderLeftDeadzone.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            leftCal = leftCal.copy(deadzonePercent = value.toInt())
            binding.tvLeftDeadzone.text = "${leftCal.deadzonePercent}%"
            binding.padLeft.deadzoneFraction = leftCal.deadzonePercent / 100f
            binding.padLeft.invalidate()
            saveLeft()
        })

        binding.sliderRightDeadzone.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            rightCal = rightCal.copy(deadzonePercent = value.toInt())
            binding.tvRightDeadzone.text = "${rightCal.deadzonePercent}%"
            binding.padRight.deadzoneFraction = rightCal.deadzonePercent / 100f
            binding.padRight.invalidate()
            saveRight()
        })

        binding.switchLeftInvertY.setOnCheckedChangeListener { _, checked ->
            leftCal = leftCal.copy(invertY = checked)
            saveLeft()
        }
        binding.switchRightInvertY.setOnCheckedChangeListener { _, checked ->
            rightCal = rightCal.copy(invertY = checked)
            saveRight()
        }

        binding.btnLeftSetCenter.setOnClickListener {
            leftCal = leftCal.copy(centerX = lastLeftRawX, centerY = lastLeftRawY)
            saveLeft()
        }
        binding.btnRightSetCenter.setOnClickListener {
            rightCal = rightCal.copy(centerX = lastRightRawX, centerY = lastRightRawY)
            saveRight()
        }
    }

    private fun saveLeft()  = Prefs.setLeftCalibration(this, leftCal)
    private fun saveRight() = Prefs.setRightCalibration(this, rightCal)

    private fun resetAll() {
        leftCal = StickCalibration()
        rightCal = StickCalibration()
        saveLeft(); saveRight()
        bindUiFromState()
    }
}
