package com.smartnav.app.sensors

import com.smartnav.app.model.Position3D
import com.smartnav.app.model.SensorData
import kotlin.math.*

/**
 * Improved Dead Reckoning Implementation
 * Uses step detection as primary method for more accurate indoor navigation.
 */
class DeadReckoning(
    private val onPositionUpdate: ((Position3D) -> Unit)? = null
) {

    private var currentPosition = Position3D(0f, 0f, 0f)
    private var yaw = 0f
    private var lastTimestamp: Long = 0L

    // Step detection - improved parameters
    private var stepCount = 0
    private var lastAccelMagnitude = 0f
    private var lastStepTime = 0L
    private val stepDetectionThresholdLow = 9.5f   // Lower threshold to detect step start
    private val stepDetectionThresholdHigh = 10.8f // Upper threshold for step peak
    private var isInStep = false                   // Hysteresis flag
    private val minStepInterval = 250L             // Minimum 250ms between steps (max 4 steps/sec)
    private val stepLength = 0.65f                 // Average step length in meters

    // Low-pass filter for smoothing
    private val alpha = 0.3f
    private var filteredAccelX = 0f
    private var filteredAccelY = 0f
    private var filteredAccelZ = 0f
    
    // Gyroscope bias correction
    private var gyroBiasZ = 0f
    private var gyroSampleCount = 0
    private val gyroBiasSamples = 50

    /**
     * Core update function for each IMU reading
     */
    fun updatePosition(sensorData: SensorData): Position3D {
        val currentTime = sensorData.timestamp
        if (lastTimestamp == 0L) {
            lastTimestamp = currentTime
            return currentPosition
        }

        val dt = (currentTime - lastTimestamp) / 1000f
        if (dt <= 0 || dt > 1f) {
            lastTimestamp = currentTime
            return currentPosition
        }

        // Calibrate gyro bias during first few samples
        if (gyroSampleCount < gyroBiasSamples) {
            gyroBiasZ += sensorData.gyroscopeZ
            gyroSampleCount++
            if (gyroSampleCount == gyroBiasSamples) {
                gyroBiasZ /= gyroBiasSamples
            }
        }

        // Update orientation using gyroscope (with bias correction)
        updateOrientation(sensorData, dt)

        // Apply low-pass filter to smooth acceleration
        filteredAccelX = alpha * sensorData.accelerometerX + (1 - alpha) * filteredAccelX
        filteredAccelY = alpha * sensorData.accelerometerY + (1 - alpha) * filteredAccelY
        filteredAccelZ = alpha * sensorData.accelerometerZ + (1 - alpha) * filteredAccelZ

        // Detect steps with hysteresis for better accuracy
        val stepDetected = detectStep(sensorData, currentTime)

        // Update position based on step detection (primary method)
        if (stepDetected) {
            // Move in the direction of current heading
            val deltaX = stepLength * cos(yaw)
            val deltaZ = stepLength * sin(yaw)
            
            currentPosition = Position3D(
                x = currentPosition.x + deltaX,
                y = currentPosition.y,  // Assume flat surface
                z = currentPosition.z + deltaZ
            )
        }

        lastTimestamp = currentTime
        onPositionUpdate?.invoke(currentPosition)

        return currentPosition
    }

    /**
     * Update orientation using gyroscope (yaw only)
     */
    private fun updateOrientation(sensorData: SensorData, dt: Float) {
        // Apply bias correction
        val correctedGyroZ = if (gyroSampleCount >= gyroBiasSamples) {
            sensorData.gyroscopeZ - gyroBiasZ
        } else {
            sensorData.gyroscopeZ
        }
        
        // Only update yaw if rotation is significant (reduces drift)
        if (abs(correctedGyroZ) > 0.02f) {
            yaw += correctedGyroZ * dt
            yaw = ((yaw + PI) % (2 * PI) - PI).toFloat()
        }
    }

    /**
     * Step detection with hysteresis for reliable counting
     */
    private fun detectStep(sensorData: SensorData, currentTime: Long): Boolean {
        val accelMagnitude = sqrt(
            sensorData.accelerometerX.pow(2) +
            sensorData.accelerometerY.pow(2) +
            sensorData.accelerometerZ.pow(2)
        )
        
        var stepDetected = false
        
        // Hysteresis-based step detection
        if (!isInStep && accelMagnitude > stepDetectionThresholdHigh) {
            // Rising edge - potential step start
            if (currentTime - lastStepTime > minStepInterval) {
                isInStep = true
                stepCount++
                stepDetected = true
                lastStepTime = currentTime
            }
        } else if (isInStep && accelMagnitude < stepDetectionThresholdLow) {
            // Falling edge - step complete
            isInStep = false
        }
        
        lastAccelMagnitude = accelMagnitude
        return stepDetected
    }

    fun reset() {
        currentPosition = Position3D(0f, 0f, 0f)
        yaw = 0f
        stepCount = 0
        lastTimestamp = 0L
        lastStepTime = 0L
        isInStep = false
        filteredAccelX = 0f
        filteredAccelY = 0f
        filteredAccelZ = 0f
        gyroBiasZ = 0f
        gyroSampleCount = 0
    }

    fun getStepCount(): Int = stepCount
    fun getYawDegrees(): Float = Math.toDegrees(yaw.toDouble()).toFloat()
}
