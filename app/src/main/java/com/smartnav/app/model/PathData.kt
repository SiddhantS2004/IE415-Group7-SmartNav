package com.smartnav.app.model

import androidx.compose.ui.geometry.Offset

/**
 * Represents a 3D position in space (x, y, z coordinates)
 * Used for both Dead Reckoning and SLAM pose tracking
 */
data class Position3D(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
) {
    /**
     * Convert 3D position to 2D screen coordinates for visualization
     * We project the x-z plane (bird's eye view) onto the canvas
     */
    fun to2DOffset(scale: Float = 100f, centerX: Float = 0f, centerY: Float = 0f): Offset {
        return Offset(
            x = centerX + (x * scale),
            y = centerY + (z * scale)  // Use z for vertical axis in top-down view
        )
    }

    /**
     * Calculate distance to another position
     */
    fun distanceTo(other: Position3D): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * Represents a complete trajectory path
 * Contains list of positions with timestamps
 */
data class PathData(
    val positions: MutableList<Position3D> = mutableListOf(),
    val timestamps: MutableList<Long> = mutableListOf()
) {
    /**
     * Add a new position to the path
     */
    fun addPosition(position: Position3D, timestamp: Long = System.currentTimeMillis()) {
        positions.add(position)
        timestamps.add(timestamp)
    }

    /**
     * Clear all path data
     */
    fun clear() {
        positions.clear()
        timestamps.clear()
    }

    /**
     * Get the current (most recent) position
     */
    fun getCurrentPosition(): Position3D? {
        return positions.lastOrNull()
    }

    /**
     * Get total path length traveled
     */
    fun getTotalDistance(): Float {
        if (positions.size < 2) return 0f

        var distance = 0f
        for (i in 1 until positions.size) {
            distance += positions[i - 1].distanceTo(positions[i])
        }
        return distance
    }
}

/**
 * Sensor data from accelerometer and gyroscope
 */
data class SensorData(
    val accelerometerX: Float = 0f,
    val accelerometerY: Float = 0f,
    val accelerometerZ: Float = 0f,
    val gyroscopeX: Float = 0f,
    val gyroscopeY: Float = 0f,
    val gyroscopeZ: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * UI state for the main screen
 */
data class NavigationState(
    val drPath: PathData = PathData(),
    val slamPath: PathData = PathData(),
    val isTracking: Boolean = false,
    val drDistance: Float = 0f,
    val slamDistance: Float = 0f,
    val driftError: Float = 0f,  // Difference between DR and SLAM
    val stepCount: Int = 0,
    val currentSensorData: SensorData = SensorData(),
    val obstaclePoints: List<Position3D> = emptyList()  // Detected obstacles from SLAM
)