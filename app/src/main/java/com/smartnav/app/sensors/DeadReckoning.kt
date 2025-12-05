package com.smartnav.app.sensors

import com.smartnav.app.model.Position3D
import com.smartnav.app.model.SensorData
import kotlin.math.*

/**
 * Improved Dead Reckoning Implementation
 * Provides more stable and visible trajectory for on-device visualization.
 */
class DeadReckoning(
    private val onPositionUpdate: ((Position3D) -> Unit)? = null // callback to update UI or map
) {

    private var currentPosition = Position3D(0f, 0f, 0f)
    private var velocityX = 0f
    private var velocityY = 0f
    private var velocityZ = 0f
    private var yaw = 0f
    private var lastTimestamp: Long = 0L

    // Step detection
    private var stepCount = 0
    private var lastAccelMagnitude = 0f
    private val stepDetectionThreshold = 11.5f
    private val stepLength = 0.3f  // slightly longer step for better visual scale

    // Low-pass filter
    private val alpha = 0.6f // less filtering → more responsive motion
    private var filteredAccelX = 0f
    private var filteredAccelY = 0f
    private var filteredAccelZ = 0f

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

        // Use correct gyroscope axis for yaw (most devices: Z)
        updateOrientation(sensorData, dt)

        // Apply low-pass filter to smooth acceleration
        filteredAccelX = alpha * sensorData.accelerometerX + (1 - alpha) * filteredAccelX
        filteredAccelY = alpha * sensorData.accelerometerY + (1 - alpha) * filteredAccelY
        filteredAccelZ = alpha * sensorData.accelerometerZ + (1 - alpha) * filteredAccelZ

        // Remove gravity using magnitude normalization
        val gravityMagnitude = 9.81f
        val totalAccel = sqrt(filteredAccelX * filteredAccelX + filteredAccelY * filteredAccelY + filteredAccelZ * filteredAccelZ)
        val scale = if (totalAccel > 1e-6f) (totalAccel - gravityMagnitude) / totalAccel else 0f
        val accelX = filteredAccelX * scale
        val accelY = filteredAccelY * scale
        val accelZ = filteredAccelZ * scale

        detectStep(sensorData)

        // Integrate acceleration to velocity
        velocityX += accelX * dt
        velocityY += accelY * dt
        velocityZ += accelZ * dt

        // Light damping (prevents unbounded drift but keeps motion)
        velocityX *= 0.98f
        velocityY *= 0.98f
        velocityZ *= 0.98f

        var deltaX = velocityX * dt
        var deltaY = velocityY * dt
        var deltaZ = velocityZ * dt

        // Step-based correction (dominates when walking)
        // Combine continuous motion + step-based correction
        if (lastAccelMagnitude > stepDetectionThreshold) {
            // Step detected → use stable step-based correction
            deltaX += stepLength * cos(yaw)
            deltaZ += stepLength * sin(yaw)
        } else {
            // Smooth walking or phone motion → rely on integrated velocity
            deltaX += velocityX * dt
            deltaZ += velocityZ * dt
        }
        deltaY = velocityY * dt // still minimal, assume flat surface


        // Update position
        currentPosition = Position3D(
            x = currentPosition.x + deltaX,
            y = currentPosition.y + deltaY,
            z = currentPosition.z + deltaZ
        )

        lastTimestamp = currentTime

        // Notify UI or map visualizer
        onPositionUpdate?.invoke(currentPosition)

        return currentPosition
    }

    /**
     * Update orientation using gyroscope (yaw only)
     */
    private fun updateOrientation(sensorData: SensorData, dt: Float) {
        // Use Z-axis for horizontal rotation (most phones flat on a table)
        yaw += sensorData.gyroscopeZ * dt
        yaw = ((yaw + Math.PI) % (2 * Math.PI) - Math.PI).toFloat()
    }

    /**
     * Step detection via peak acceleration magnitude
     */
    private fun detectStep(sensorData: SensorData) {
        val accelMagnitude = sqrt(
            sensorData.accelerometerX.pow(2) +
                    sensorData.accelerometerY.pow(2) +
                    sensorData.accelerometerZ.pow(2)
        )
        if (accelMagnitude > stepDetectionThreshold &&
            lastAccelMagnitude < stepDetectionThreshold) {
            stepCount++
        }
        lastAccelMagnitude = accelMagnitude
    }

    fun reset() {
        currentPosition = Position3D(0f, 0f, 0f)
        velocityX = 0f
        velocityY = 0f
        velocityZ = 0f
        yaw = 0f
        stepCount = 0
        lastTimestamp = 0L
        filteredAccelX = 0f
        filteredAccelY = 0f
        filteredAccelZ = 0f
    }

    fun getStepCount(): Int = stepCount
    fun getYawDegrees(): Float = Math.toDegrees(yaw.toDouble()).toFloat()
}
