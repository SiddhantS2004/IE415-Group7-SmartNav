package com.smartnav.app.ar

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.smartnav.app.model.Position3D
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.google.ar.core.Trackable

private const val TAG = "ARCoreManager"

/**
 * ARCore Manager - Handles SLAM-based localization
 *
 * SLAM (Simultaneous Localization and Mapping) uses the camera to:
 * 1. Detect visual features in the environment
 * 2. Track these features across frames
 * 3. Estimate camera pose (position + orientation)
 * 4. Build a map of the environment
 *
 * ARCore does all this heavy lifting for us!
 */
class ARCoreManager(private val context: Context) {

    private var session: Session? = null
    private var isSessionStarted = false
    private var isSessionResumed = false

    // Origin pose - set when tracking starts
    private var originPose: Pose? = null

    // Current position flow
    private val _currentPosition = MutableStateFlow(Position3D(0f, 0f, 0f))
    val currentPosition: StateFlow<Position3D> = _currentPosition

    // Tracking state flow
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking
    
    // Tracking state message for debugging
    private val _trackingStatus = MutableStateFlow("Not initialized")
    val trackingStatus: StateFlow<String> = _trackingStatus

    /**
     * Check if ARCore is supported on this device
     */
    fun isARCoreSupported(): Boolean {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            Log.d(TAG, "ARCore availability: $availability")
            
            when (availability) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    Log.d(TAG, "ARCore is supported and installed")
                    true
                }
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    Log.d(TAG, "ARCore is supported but needs installation/update")
                    true
                }
                else -> {
                    Log.d(TAG, "ARCore is not supported")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ARCore availability", e)
            false
        }
    }

    /**
     * Initialize ARCore session
     * This sets up the camera and SLAM system
     */
    fun initializeSession(activity: Activity): Boolean {
        Log.d(TAG, "Initializing ARCore session")
        _trackingStatus.value = "Initializing..."
        
        return try {
            // Check and request ARCore installation if needed
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
            Log.d(TAG, "ARCore install status: $installStatus")
            
            when (installStatus) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    Log.d(TAG, "ARCore installation requested")
                    _trackingStatus.value = "Installing ARCore..."
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    Log.d(TAG, "ARCore is installed, proceeding with session creation")
                }
            }

            // Create ARCore session
            session = Session(context)
            Log.d(TAG, "ARCore session created successfully")

            // Configure session for best performance
            val config = Config(session).apply {
                // Use LATEST_CAMERA_IMAGE for best tracking
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

                // Enable plane detection (helps with SLAM accuracy)
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

                // Enable depth if supported (better pose estimation)
                depthMode = Config.DepthMode.AUTOMATIC
                
                // Focus mode for better image quality
                focusMode = Config.FocusMode.AUTO
            }

            session?.configure(config)
            Log.d(TAG, "ARCore session configured")
            
            isSessionStarted = true
            _trackingStatus.value = "Session ready"
            true
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore unavailable", e)
            _trackingStatus.value = "ARCore unavailable: ${e.message}"
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ARCore session", e)
            _trackingStatus.value = "Error: ${e.message}"
            false
        }
    }

    /**
     * Resume ARCore session (call in onResume)
     */
    fun resume() {
        Log.d(TAG, "Resuming ARCore session")
        try {
            if (session != null && isSessionStarted) {
                session?.resume()
                isSessionResumed = true
                _trackingStatus.value = "Session resumed"
                Log.d(TAG, "ARCore session resumed successfully")
            } else {
                Log.w(TAG, "Cannot resume: session is null or not started")
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            _trackingStatus.value = "Camera not available"
            isSessionResumed = false
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming session", e)
            _trackingStatus.value = "Resume error: ${e.message}"
            isSessionResumed = false
        }
    }

    /**
     * Pause ARCore session (call in onPause)
     */
    fun pause() {
        Log.d(TAG, "Pausing ARCore session")
        try {
            session?.pause()
            isSessionResumed = false
            _trackingStatus.value = "Session paused"
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing session", e)
        }
    }

    /**
     * Update ARCore and get current pose
     * Call this in your render loop or at regular intervals
     *
     * Returns: Current position estimated by SLAM, or null if tracking lost
     */
    fun update(): Position3D? {
        val currentSession = session
        if (currentSession == null) {
            Log.v(TAG, "Session is null")
            return null
        }
        
        if (!isSessionResumed) {
            Log.v(TAG, "Session not resumed yet")
            return null
        }

        return try {
            // Update ARCore - this runs SLAM algorithms
            val frame: Frame = currentSession.update()
            val camera: Camera = frame.camera

            // Check if we have good tracking
            when (camera.trackingState) {
                TrackingState.TRACKING -> {
                    _isTracking.value = true
                    _trackingStatus.value = "Tracking"

                    // Get current camera pose from SLAM
                    val pose: Pose = camera.pose

                    // Set origin on first successful tracking
                    if (originPose == null) {
                        originPose = pose
                        Log.d(TAG, "Origin pose set")
                    }

                    // Calculate position relative to origin
                    val position = calculateRelativePosition(pose, originPose!!)
                    _currentPosition.value = position
                    position
                }
                TrackingState.PAUSED -> {
                    _isTracking.value = false
                    _trackingStatus.value = "Tracking paused - move device slowly"
                    Log.d(TAG, "Tracking paused")
                    // Keep last known position
                    _currentPosition.value
                }
                TrackingState.STOPPED -> {
                    _isTracking.value = false
                    _trackingStatus.value = "Tracking stopped"
                    Log.d(TAG, "Tracking stopped")
                    null
                }
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during update", e)
            _isTracking.value = false
            _trackingStatus.value = "Camera not available"
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error during ARCore update", e)
            _isTracking.value = false
            _trackingStatus.value = "Update error"
            null
        }
    }

    /**
     * Calculate position relative to origin
     *
     * ARCore Pose contains:
     * - tx(), ty(), tz(): translation (position) in meters
     * - qx(), qy(), qz(), qw(): rotation as quaternion
     *
     * Coordinate system:
     * - X: right
     * - Y: up
     * - Z: backward (so we negate it for forward)
     */
    private fun calculateRelativePosition(currentPose: Pose, origin: Pose): Position3D {
        // Get translation vectors
        val currentTx = currentPose.tx()
        val currentTy = currentPose.ty()
        val currentTz = currentPose.tz()

        val originTx = origin.tx()
        val originTy = origin.ty()
        val originTz = origin.tz()

        // Calculate relative position
        return Position3D(
            x = currentTx - originTx,           // Right/Left movement
            y = currentTy - originTy,           // Up/Down movement
            z = -(currentTz - originTz)         // Forward/Backward (negated)
        )
    }

    /**
     * Reset tracking - set new origin at current position
     */
    fun resetTracking() {
        originPose = null
        _currentPosition.value = Position3D(0f, 0f, 0f)
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        session?.close()
        session = null
        isSessionStarted = false
    }

    /**
     * Get tracking quality as a percentage
     * Based on number of tracked feature points
     */
     // Make sure this import is added at the top of your file

// ... inside your ARCoreManager class

    /**
     * Get tracking quality as a percentage
     * Based on number of tracked feature points
     */
    fun getTrackingQuality(): Float {
        return try {
            val frame = session?.update() ?: return 0f
            val camera = frame.camera

            if (camera.trackingState != TrackingState.TRACKING) {
                return 0f
            }

            // Get the list of updated trackables (the new way)
            val trackables = frame.getUpdatedTrackables(Trackable::class.java)

            // A simple way to estimate quality is by the number of trackables found.
            // You can adjust the denominator (e.g., 50.0f) based on what feels right
            // for your application's environment. More trackables generally means
            // the environment is richer in features and tracking is more stable.
            val quality = (trackables.size / 50.0f).coerceIn(0f, 1f)
            quality
        } catch (e: Exception) {
            e.printStackTrace()
            0f
        }
    }

}