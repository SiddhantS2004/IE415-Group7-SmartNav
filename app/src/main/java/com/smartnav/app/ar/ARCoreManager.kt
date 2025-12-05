package com.smartnav.app.ar

import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.WindowManager
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

private const val TAG = "ARCoreManager"

/**
 * ARCore Manager - Implements Visual SLAM for indoor navigation
 * 
 * Features:
 * - Real-time feature point detection and visualization
 * - 3D map point accumulation for environment mapping
 * - Accurate 6-DOF pose estimation for localization
 * - Much more accurate than dead reckoning (no drift over time)
 */
class ARCoreManager(private val context: Context) : GLSurfaceView.Renderer {

    private var session: Session? = null
    private var isSessionStarted = false
    private var isSessionResumed = false
    
    var glSurfaceView: GLSurfaceView? = null
        private set
    
    // Camera texture
    private var cameraTextureId: Int = -1
    private var isGLReady = false
    
    // Shader programs
    private var cameraProgram: Int = 0
    private var cameraPositionAttrib: Int = 0
    private var cameraTexCoordAttrib: Int = 0
    private var cameraTextureUniform: Int = 0
    
    // Feature point shader (for green squares)
    private var featureProgram: Int = 0
    private var featurePositionAttrib: Int = 0
    private var featureColorUniform: Int = 0
    private var featureMvpUniform: Int = 0
    private var featurePointSizeUniform: Int = 0
    
    // Matrices
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    
    // Camera background quad
    private lateinit var quadVertices: FloatBuffer
    
    // Display info
    private var displayRotation: Int = 0
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var viewportChanged = true

    // Origin pose for relative positioning
    private var originPose: Pose? = null
    private var validTrackingFrames = 0
    
    // ============ SLAM Map Data ============
    // Accumulated map points for environment mapping
    private val mapPoints = mutableListOf<MapPoint>()
    private var lastKeyframePose: Pose? = null
    private val keyframeDistanceThreshold = 0.1f // Add keyframe every 10cm for faster updates
    
    // Current frame feature points (for visualization)
    private var currentFeaturePoints: FloatArray = FloatArray(0)
    private var numCurrentFeatures = 0
    
    // Map statistics
    private var totalMapPoints = 0
    private var keyframeCount = 0
    private var obstacleCount = 0

    // Position tracking
    private val _currentPosition = MutableStateFlow(Position3D(0f, 0f, 0f))
    val currentPosition: StateFlow<Position3D> = _currentPosition

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking
    
    private val _trackingStatus = MutableStateFlow("Not initialized")
    val trackingStatus: StateFlow<String> = _trackingStatus

    /**
     * Map point class for SLAM
     */
    data class MapPoint(
        val x: Float,
        val y: Float, 
        val z: Float,
        val confidence: Float,
        val timestamp: Long
    )

    fun isARCoreSupported(): Boolean {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            Log.d(TAG, "ARCore availability: $availability")
            when (availability) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> true
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ARCore availability", e)
            false
        }
    }

    fun createGLSurfaceView(activity: Activity): GLSurfaceView {
        glSurfaceView = GLSurfaceView(activity).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@ARCoreManager)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        
        val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayRotation = windowManager.defaultDisplay.rotation
        
        Log.d(TAG, "GLSurfaceView created, rotation: $displayRotation")
        return glSurfaceView!!
    }

    fun initializeSession(activity: Activity): Boolean {
        Log.d(TAG, "Initializing ARCore session")
        _trackingStatus.value = "Initializing..."
        
        return try {
            val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
            Log.d(TAG, "ARCore install status: $installStatus")
            
            when (installStatus) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    _trackingStatus.value = "Installing ARCore..."
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    Log.d(TAG, "ARCore is installed")
                }
            }

            session = Session(context)
            Log.d(TAG, "ARCore session created")

            // Configure for best SLAM performance
            val config = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                focusMode = Config.FocusMode.AUTO
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                
                // Enable depth if supported for better mapping
                if (session?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true) {
                    depthMode = Config.DepthMode.AUTOMATIC
                    Log.d(TAG, "Depth mode enabled")
                }
            }
            session?.configure(config)
            
            isSessionStarted = true
            _trackingStatus.value = "Session ready"
            Log.d(TAG, "ARCore session configured for SLAM")
            true
            
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore unavailable", e)
            _trackingStatus.value = "ARCore unavailable"
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ARCore: ${e.message}", e)
            _trackingStatus.value = "Error: ${e.message}"
            false
        }
    }

    fun resume() {
        Log.d(TAG, "Resuming ARCore")
        if (session == null || !isSessionStarted) {
            Log.w(TAG, "Cannot resume: session not ready")
            return
        }
        
        try {
            session?.resume()
            isSessionResumed = true
            viewportChanged = true
            glSurfaceView?.onResume()
            _trackingStatus.value = "Scanning..."
            Log.d(TAG, "ARCore resumed")
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            _trackingStatus.value = "Camera unavailable"
            isSessionResumed = false
        } catch (e: Exception) {
            Log.e(TAG, "Resume error: ${e.message}", e)
            _trackingStatus.value = "Resume error"
            isSessionResumed = false
        }
    }

    fun pause() {
        Log.d(TAG, "Pausing ARCore")
        glSurfaceView?.onPause()
        session?.pause()
        isSessionResumed = false
        viewportChanged = true
    }

    fun destroy() {
        Log.d(TAG, "Destroying ARCore")
        session?.close()
        session = null
        isSessionStarted = false
        isSessionResumed = false
        mapPoints.clear()
    }

    // ==================== GLSurfaceView.Renderer ====================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "GL Surface created")
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1f)
        
        cameraTextureId = createExternalTexture()
        session?.setCameraTextureName(cameraTextureId)
        
        initCameraShader()
        initFeatureShader()
        initQuadBuffers()
        
        isGLReady = true
        Log.d(TAG, "Shaders initialized, texture ID: $cameraTextureId")
    }
    
    private fun initCameraShader() {
        val vertexShader = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()
        
        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES u_Texture;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """.trimIndent()
        
        cameraProgram = createProgram(vertexShader, fragmentShader)
        cameraPositionAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
        cameraTexCoordAttrib = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
        cameraTextureUniform = GLES20.glGetUniformLocation(cameraProgram, "u_Texture")
    }
    
    private fun initFeatureShader() {
        val vertexShader = """
            uniform mat4 u_MVP;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_MVP * a_Position;
                gl_PointSize = u_PointSize;
            }
        """.trimIndent()
        
        val fragmentShader = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """.trimIndent()
        
        featureProgram = createProgram(vertexShader, fragmentShader)
        featurePositionAttrib = GLES20.glGetAttribLocation(featureProgram, "a_Position")
        featureColorUniform = GLES20.glGetUniformLocation(featureProgram, "u_Color")
        featureMvpUniform = GLES20.glGetUniformLocation(featureProgram, "u_MVP")
        featurePointSizeUniform = GLES20.glGetUniformLocation(featureProgram, "u_PointSize")
    }
    
    private fun initQuadBuffers() {
        // Fullscreen quad vertices
        val coords = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        quadVertices = createFloatBuffer(coords)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
        displayWidth = width
        displayHeight = height
        GLES20.glViewport(0, 0, width, height)
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        if (!isGLReady || !isSessionResumed) return
        
        val currentSession = session ?: return
        
        try {
            // Update display geometry if changed
            if (viewportChanged) {
                currentSession.setDisplayGeometry(displayRotation, displayWidth, displayHeight)
                viewportChanged = false
            }
            
            val frame = currentSession.update()
            val camera = frame.camera
            
            // Draw camera background (properly scaled - no zoom)
            drawCameraBackground(frame)
            
            if (camera.trackingState == TrackingState.TRACKING) {
                // Get matrices for 3D rendering
                camera.getViewMatrix(viewMatrix, 0)
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
                
                // Process SLAM: extract features and update map
                processSLAM(frame, camera)
                
                // Draw feature points (green squares like in reference)
                drawFeaturePoints()
                
                // Draw map points (accumulated 3D map)
                drawMapPoints()
                
                // Update position
                updatePosition(camera)
            } else {
                handleTrackingLost(camera.trackingState)
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Frame error: ${e.javaClass.simpleName}")
        }
    }
    
    private fun drawCameraBackground(frame: Frame) {
        // Get the texture coordinates transformed for proper display (fixes zoom issue)
        val texCoordBuffer = createFloatBuffer(8)
        frame.transformCoordinates2d(
            com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            quadVertices,
            com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
            texCoordBuffer
        )
        
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        
        GLES20.glUseProgram(cameraProgram)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(cameraTextureUniform, 0)
        
        GLES20.glEnableVertexAttribArray(cameraPositionAttrib)
        quadVertices.position(0)
        GLES20.glVertexAttribPointer(cameraPositionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        
        GLES20.glEnableVertexAttribArray(cameraTexCoordAttrib)
        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(cameraTexCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glDisableVertexAttribArray(cameraPositionAttrib)
        GLES20.glDisableVertexAttribArray(cameraTexCoordAttrib)
        
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
    
    /**
     * Core SLAM processing - extract features, detect obstacles, and build map
     * Enhanced for better obstacle detection including moving objects
     */
    private fun processSLAM(frame: Frame, camera: Camera) {
        val pointCloud = frame.acquirePointCloud()
        val points = pointCloud.points
        val currentTime = System.currentTimeMillis()
        
        if (points.remaining() >= 4) {
            val numPoints = points.remaining() / 4
            
            // Extract current frame feature points
            currentFeaturePoints = FloatArray(numPoints * 4)
            points.position(0)
            points.get(currentFeaturePoints)
            numCurrentFeatures = numPoints
            
            // Get camera position for distance-based filtering
            val camPos = camera.pose
            
            // Add ALL points within detection range to map (not just keyframes)
            // This provides more accurate obstacle mapping
            val timestamp = currentTime
            var addedPoints = 0
            var closeObstacles = 0
            
            for (i in 0 until numPoints) {
                val x = currentFeaturePoints[i * 4]
                val y = currentFeaturePoints[i * 4 + 1]
                val z = currentFeaturePoints[i * 4 + 2]
                val confidence = currentFeaturePoints[i * 4 + 3]
                
                // Calculate distance from camera
                val distToCamera = sqrt(
                    (x - camPos.tx()) * (x - camPos.tx()) +
                    (y - camPos.ty()) * (y - camPos.ty()) +
                    (z - camPos.tz()) * (z - camPos.tz())
                )
                
                // Lower confidence threshold for close objects (better obstacle detection)
                // Close objects (< 3m): accept lower confidence (0.2)
                // Far objects (> 3m): need higher confidence (0.4)
                val confidenceThreshold = if (distToCamera < 3.0f) 0.2f else 0.4f
                
                if (confidence > confidenceThreshold) {
                    // Smaller deduplication threshold for close objects (more detail)
                    val dedupThreshold = if (distToCamera < 2.0f) 0.03f else 0.08f
                    
                    if (!isPointNearExisting(x, y, z, dedupThreshold)) {
                        mapPoints.add(MapPoint(x, y, z, confidence, timestamp))
                        addedPoints++
                        
                        if (distToCamera < 2.0f) closeObstacles++
                    }
                }
            }
            
            // Update keyframe tracking
            val currentPose = camera.pose
            if (lastKeyframePose == null || 
                poseDistance(currentPose, lastKeyframePose!!) > keyframeDistanceThreshold) {
                lastKeyframePose = currentPose
                keyframeCount++
            }
            
            totalMapPoints = mapPoints.size
            obstacleCount = closeObstacles
            
            // More aggressive map cleanup - remove old points faster
            cleanupOldMapPoints(currentTime)
            
            // Limit map size
            if (mapPoints.size > 15000) {
                val sorted = mapPoints.sortedByDescending { it.confidence }
                mapPoints.clear()
                mapPoints.addAll(sorted.take(10000))
            }
            
            _trackingStatus.value = "SLAM: ${numCurrentFeatures}pts, Near:$obstacleCount"
        }
        
        pointCloud.release()
        
        // Also try to use depth if available for better obstacle detection
        tryProcessDepth(frame, camera)
    }
    
    /**
     * Try to use depth data for better obstacle detection
     */
    private fun tryProcessDepth(frame: Frame, camera: Camera) {
        try {
            // ARCore depth API - if device supports it
            val depthImage = frame.acquireDepthImage16Bits()
            val width = depthImage.width
            val height = depthImage.height
            val depthBuffer = depthImage.planes[0].buffer
            
            // Sample depth at center and key points for obstacle detection
            val centerX = width / 2
            val centerY = height / 2
            
            // Check several points across the image
            val checkPoints = listOf(
                Pair(centerX, centerY),
                Pair(centerX - width/4, centerY),
                Pair(centerX + width/4, centerY),
                Pair(centerX, centerY - height/4),
                Pair(centerX, centerY + height/4)
            )
            
            val timestamp = System.currentTimeMillis()
            val camPose = camera.pose
            
            for ((px, py) in checkPoints) {
                if (px in 0 until width && py in 0 until height) {
                    val index = (py * width + px) * 2
                    if (index + 1 < depthBuffer.capacity()) {
                        val depthMm = (depthBuffer.get(index).toInt() and 0xFF) or
                                     ((depthBuffer.get(index + 1).toInt() and 0xFF) shl 8)
                        val depthM = depthMm / 1000.0f
                        
                        // If obstacle is close (< 2.5m), add to map
                        if (depthM > 0.1f && depthM < 2.5f) {
                            // Convert 2D + depth to 3D (approximate)
                            val fx = width / 2.0f
                            val fy = height / 2.0f
                            val x3d = (px - fx) / fx * depthM
                            val y3d = (py - fy) / fy * depthM
                            val z3d = depthM
                            
                            // Transform to world coordinates
                            val worldPoint = camPose.transformPoint(floatArrayOf(x3d, y3d, -z3d))
                            
                            if (!isPointNearExisting(worldPoint[0], worldPoint[1], worldPoint[2], 0.1f)) {
                                mapPoints.add(MapPoint(worldPoint[0], worldPoint[1], worldPoint[2], 0.8f, timestamp))
                            }
                        }
                    }
                }
            }
            
            depthImage.close()
        } catch (e: Exception) {
            // Depth not available on this device or frame - that's okay
        }
    }
    
    /**
     * Remove old map points to keep map current and detect changes
     */
    private fun cleanupOldMapPoints(currentTime: Long) {
        // Remove points older than 10 seconds for close range, 30 seconds for far
        mapPoints.removeAll { point ->
            val age = currentTime - point.timestamp
            // Keep far points longer, remove close points faster (they might be moving)
            val maxAge = if (isClosePoint(point)) 10000L else 30000L
            age > maxAge
        }
    }
    
    private fun isClosePoint(point: MapPoint): Boolean {
        val currentPos = _currentPosition.value
        val dist = sqrt(
            (point.x - currentPos.x) * (point.x - currentPos.x) +
            (point.z + currentPos.z) * (point.z + currentPos.z)
        )
        return dist < 2.0f
    }
    
    private fun isPointNearExisting(x: Float, y: Float, z: Float, threshold: Float): Boolean {
        for (mp in mapPoints) {
            val dist = sqrt((mp.x - x) * (mp.x - x) + (mp.y - y) * (mp.y - y) + (mp.z - z) * (mp.z - z))
            if (dist < threshold) return true
        }
        return false
    }
    
    private fun poseDistance(p1: Pose, p2: Pose): Float {
        val dx = p1.tx() - p2.tx()
        val dy = p1.ty() - p2.ty()
        val dz = p1.tz() - p2.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    
    /**
     * Draw current frame feature points as green squares (like reference image)
     */
    private fun drawFeaturePoints() {
        if (numCurrentFeatures == 0) return
        
        GLES20.glUseProgram(featureProgram)
        
        GLES20.glUniformMatrix4fv(featureMvpUniform, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(featurePointSizeUniform, 10.0f)
        // Green color like in reference image
        GLES20.glUniform4f(featureColorUniform, 0.0f, 1.0f, 0.0f, 1.0f)
        
        val buffer = createFloatBuffer(currentFeaturePoints)
        
        GLES20.glEnableVertexAttribArray(featurePositionAttrib)
        GLES20.glVertexAttribPointer(featurePositionAttrib, 4, GLES20.GL_FLOAT, false, 16, buffer)
        
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numCurrentFeatures)
        
        GLES20.glDisableVertexAttribArray(featurePositionAttrib)
    }
    
    /**
     * Draw accumulated map points (red for close obstacles, blue for environment)
     */
    private fun drawMapPoints() {
        if (mapPoints.isEmpty()) return
        
        GLES20.glUseProgram(featureProgram)
        GLES20.glUniformMatrix4fv(featureMvpUniform, 1, false, mvpMatrix, 0)
        
        // Prepare buffers for different distance categories
        val closePoints = mutableListOf<Float>()  // < 2m - obstacles (red)
        val farPoints = mutableListOf<Float>()    // > 2m - environment (blue)
        
        val currentPos = _currentPosition.value
        
        for (mp in mapPoints) {
            val dist = sqrt(
                (mp.x - currentPos.x) * (mp.x - currentPos.x) +
                (mp.z + currentPos.z) * (mp.z + currentPos.z)
            )
            
            if (dist < 2.0f) {
                closePoints.addAll(listOf(mp.x, mp.y, mp.z, 1f))
            } else {
                farPoints.addAll(listOf(mp.x, mp.y, mp.z, 1f))
            }
        }
        
        // Draw close obstacles in RED
        if (closePoints.isNotEmpty()) {
            GLES20.glUniform1f(featurePointSizeUniform, 8.0f)
            GLES20.glUniform4f(featureColorUniform, 1.0f, 0.2f, 0.0f, 0.9f)
            
            val buffer = createFloatBuffer(closePoints.toFloatArray())
            GLES20.glEnableVertexAttribArray(featurePositionAttrib)
            GLES20.glVertexAttribPointer(featurePositionAttrib, 4, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, closePoints.size / 4)
            GLES20.glDisableVertexAttribArray(featurePositionAttrib)
        }
        
        // Draw far environment in BLUE
        if (farPoints.isNotEmpty()) {
            GLES20.glUniform1f(featurePointSizeUniform, 5.0f)
            GLES20.glUniform4f(featureColorUniform, 0.3f, 0.6f, 1.0f, 0.7f)
            
            val buffer = createFloatBuffer(farPoints.toFloatArray())
            GLES20.glEnableVertexAttribArray(featurePositionAttrib)
            GLES20.glVertexAttribPointer(featurePositionAttrib, 4, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, farPoints.size / 4)
            GLES20.glDisableVertexAttribArray(featurePositionAttrib)
        }
    }
    
    private fun updatePosition(camera: Camera) {
        val pose = camera.pose
        
        if (originPose == null) {
            originPose = pose
            Log.d(TAG, "Origin set: ${pose.tx()}, ${pose.ty()}, ${pose.tz()}")
        }
        
        validTrackingFrames++
        
        if (validTrackingFrames >= 3) {
            _isTracking.value = true
            
            val position = Position3D(
                x = pose.tx() - originPose!!.tx(),
                y = pose.ty() - originPose!!.ty(),
                z = -(pose.tz() - originPose!!.tz())
            )
            _currentPosition.value = position
        }
    }
    
    private fun handleTrackingLost(state: TrackingState) {
        validTrackingFrames = 0
        _isTracking.value = false
        _trackingStatus.value = when (state) {
            TrackingState.PAUSED -> "Lost - move slowly"
            TrackingState.STOPPED -> "Stopped"
            else -> "Initializing..."
        }
    }

    // ==================== Helper Methods ====================
    
    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        
        return textureId
    }
    
    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        return program
    }
    
    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
    
    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .also { it.position(0) }
    }
    
    private fun createFloatBuffer(size: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    fun update(): Position3D? {
        return if (_isTracking.value) _currentPosition.value else null
    }

    fun resetTracking() {
        originPose = null
        validTrackingFrames = 0
        mapPoints.clear()
        totalMapPoints = 0
        keyframeCount = 0
        lastKeyframePose = null
        _currentPosition.value = Position3D(0f, 0f, 0f)
        Log.d(TAG, "SLAM reset")
    }
}
