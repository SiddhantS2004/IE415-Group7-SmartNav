package com.smartnav.app.ui

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartnav.app.ar.ARCoreManager
import com.smartnav.app.model.NavigationState
import com.smartnav.app.model.Position3D
import com.smartnav.app.model.SensorData
import com.smartnav.app.sensors.DeadReckoning
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "SensorViewModel"

/**
 * Main ViewModel for SmartNav
 * Coordinates sensor data, dead reckoning, and SLAM
 *
 * Architecture:
 * 1. Sensors → SensorManager → SensorEventListener
 * 2. Raw sensor data → DeadReckoning → DR Path
 * 3. Camera frames → ARCore → SLAM Path
 * 4. Both paths → UI State → Compose Canvas
 */
class SensorViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Dead Reckoning engine
    private val deadReckoning = DeadReckoning()

    // ARCore manager (nullable - will be set from Activity)
    var arCoreManager: ARCoreManager? = null
        set(value) {
            field = value
            Log.d(TAG, "ARCore manager set: ${value != null}")
        }

    // UI State
    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    // Current sensor readings
    private var currentAccelX = 0f
    private var currentAccelY = 0f
    private var currentAccelZ = 0f
    private var currentGyroX = 0f
    private var currentGyroY = 0f
    private var currentGyroZ = 0f

    // Sensor update job
    private var sensorUpdateJob: Job? = null
    private var arCoreUpdateJob: Job? = null

    /**
     * Sensor event listener - receives raw IMU data
     * This is called at high frequency (~100-200 Hz)
     */
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    // Accelerometer measures linear acceleration (m/s²)
                    // Includes gravity!
                    currentAccelX = event.values[0]
                    currentAccelY = event.values[1]
                    currentAccelZ = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    // Gyroscope measures angular velocity (rad/s)
                    currentGyroX = event.values[0]
                    currentGyroY = event.values[1]
                    currentGyroZ = event.values[2]
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            // Handle accuracy changes if needed
        }
    }

    /**
     * Start tracking both DR and SLAM
     */
    fun startTracking() {
        if (_navigationState.value.isTracking) return

        // Register sensor listeners
        accelerometer?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_GAME  // ~50ms delay, good balance
            )
        }

        gyroscope?.let {
            sensorManager.registerListener(
                sensorListener,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        // Start Dead Reckoning update loop
        startDeadReckoningUpdates()

        // Start ARCore update loop
        startARCoreUpdates()

        _navigationState.value = _navigationState.value.copy(isTracking = true)
    }

    /**
     * Stop tracking
     */
    fun stopTracking() {
        sensorManager.unregisterListener(sensorListener)
        sensorUpdateJob?.cancel()
        arCoreUpdateJob?.cancel()

        _navigationState.value = _navigationState.value.copy(isTracking = false)
    }

    /**
     * Reset both paths to origin
     */
    fun resetPaths() {
        deadReckoning.reset()
        arCoreManager?.resetTracking()

        _navigationState.value = _navigationState.value.copy(
            drPath = _navigationState.value.drPath.apply { clear() },
            slamPath = _navigationState.value.slamPath.apply { clear() },
            drDistance = 0f,
            slamDistance = 0f,
            driftError = 0f,
            stepCount = 0
        )
    }

    /**
     * Dead Reckoning update loop
     * Runs at ~20 Hz (every 50ms)
     */
    private fun startDeadReckoningUpdates() {
        sensorUpdateJob = viewModelScope.launch {
            while (isActive) {
                // Create sensor data snapshot
                val sensorData = SensorData(
                    accelerometerX = currentAccelX,
                    accelerometerY = currentAccelY,
                    accelerometerZ = currentAccelZ,
                    gyroscopeX = currentGyroX,
                    gyroscopeY = currentGyroY,
                    gyroscopeZ = currentGyroZ,
                    timestamp = System.currentTimeMillis()
                )

                // Update dead reckoning position
                val drPosition = deadReckoning.updatePosition(sensorData)

                // Update UI state
                val currentState = _navigationState.value
                currentState.drPath.addPosition(drPosition)

                // Calculate drift error (distance between DR and SLAM current positions)
                val slamPosition = currentState.slamPath.getCurrentPosition()
                val driftError = if (slamPosition != null) {
                    drPosition.distanceTo(slamPosition)
                } else {
                    0f
                }

                _navigationState.value = currentState.copy(
                    drDistance = currentState.drPath.getTotalDistance(),
                    stepCount = deadReckoning.getStepCount(),
                    driftError = driftError,
                    currentSensorData = sensorData
                )

                delay(50)  // 20 Hz update rate
            }
        }
    }

    /**
     * ARCore SLAM update loop
     * Runs at ~30 Hz (every 33ms)
     */
    private fun startARCoreUpdates() {
        Log.d(TAG, "Starting ARCore updates, arCoreManager=${arCoreManager != null}")
        
        arCoreUpdateJob = viewModelScope.launch {
            // Give ARCore a moment to fully initialize the camera
            delay(500)
            
            var frameCount = 0
            var successfulFrames = 0
            var lastLogTime = System.currentTimeMillis()
            
            while (isActive) {
                try {
                    // Update ARCore and get SLAM position
                    val slamPosition = arCoreManager?.update()
                    frameCount++

                    if (slamPosition != null) {
                        successfulFrames++
                        
                        // Add to SLAM path
                        val currentState = _navigationState.value
                        currentState.slamPath.addPosition(slamPosition)

                        // Calculate drift error
                        val drPosition = currentState.drPath.getCurrentPosition()
                        val driftError = if (drPosition != null) {
                            drPosition.distanceTo(slamPosition)
                        } else {
                            0f
                        }

                        _navigationState.value = currentState.copy(
                            slamDistance = currentState.slamPath.getTotalDistance(),
                            driftError = driftError
                        )
                    }

                    // Log periodically (every 3 seconds)
                    val now = System.currentTimeMillis()
                    if (now - lastLogTime > 3000) {
                        val trackingStatus = arCoreManager?.trackingStatus?.value ?: "N/A"
                        Log.d(TAG, "ARCore: $successfulFrames/$frameCount successful frames. Status: $trackingStatus")
                        lastLogTime = now
                        frameCount = 0
                        successfulFrames = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception in ARCore update loop", e)
                }

                delay(33)  // ~30 Hz update rate
            }
        }
    }

    /**
     * Save session data (optional feature)
     * You can implement this to export path data to CSV/JSON
     */
    fun saveSession(): String {
        val state = _navigationState.value

        // Create a simple text summary
        val summary = buildString {
            appendLine("SmartNav Session Summary")
            appendLine("=" .repeat(40))
            appendLine("Dead Reckoning:")
            appendLine("  - Distance: ${"%.2f".format(state.drDistance)} m")
            appendLine("  - Steps: ${state.stepCount}")
            appendLine("  - Points: ${state.drPath.positions.size}")
            appendLine()
            appendLine("SLAM:")
            appendLine("  - Distance: ${"%.2f".format(state.slamDistance)} m")
            appendLine("  - Points: ${state.slamPath.positions.size}")
            appendLine()
            appendLine("Analysis:")
            appendLine("  - Drift Error: ${"%.2f".format(state.driftError)} m")
            appendLine("  - Accuracy: ${"%.1f".format((1 - state.driftError / state.drDistance.coerceAtLeast(0.1f)) * 100)}%")
        }

        return summary
    }

    /**
     * Clean up resources
     */
    override fun onCleared() {
        super.onCleared()
        stopTracking()
        arCoreManager?.destroy()
    }
}