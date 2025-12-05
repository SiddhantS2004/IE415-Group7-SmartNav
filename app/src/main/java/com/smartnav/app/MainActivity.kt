package com.smartnav.app

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartnav.app.ar.ARCoreManager
import com.smartnav.app.model.Position3D
import com.smartnav.app.ui.SensorViewModel
import kotlinx.coroutines.delay

private const val TAG = "MainActivity"

/**
 * Main Activity - Entry point of the app
 * Handles permissions, lifecycle, and Compose UI
 */
class MainActivity : ComponentActivity() {

    // ARCore manager - lateinit because it requires camera permission
    internal lateinit var arCoreManager: ARCoreManager
    
    // GL Surface View for ARCore
    private var glSurfaceView: GLSurfaceView? = null

    // State to track ARCore readiness (session created)
    private val arCoreReady = mutableStateOf(false)
    
    // State to track if SLAM camera view is active (user clicked SLAM button)
    internal val slamViewActive = mutableStateOf(false)
    
    // State to track permission status
    private val cameraPermissionGranted = mutableStateOf(false)
    
    // State to track initialization status message
    private val statusMessage = mutableStateOf("Checking camera permission...")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            Log.d(TAG, "Camera permission result: $isGranted")
            if (isGranted) {
                cameraPermissionGranted.value = true
                statusMessage.value = "Ready"
                // Just mark permission granted, don't initialize ARCore yet
                arCoreReady.value = true
            } else {
                statusMessage.value = "Camera permission denied. AR features unavailable."
                Toast.makeText(
                    this,
                    "Camera permission is required for AR/SLAM functionality.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        setContent {
            SmartNavTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        arCoreReady.value -> {
                            // Permission granted - show main app
                            SmartNavApp()
                        }
                        else -> {
                            // Show loading/status screen
                            LoadingScreen(statusMessage.value)
                        }
                    }
                }
            }
        }

        // Check and request camera permission
        checkAndRequestCameraPermission()
    }

    private fun checkAndRequestCameraPermission() {
        Log.d(TAG, "Checking camera permission")
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Camera permission already granted")
                cameraPermissionGranted.value = true
                statusMessage.value = "Ready"
                arCoreReady.value = true
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d(TAG, "Should show permission rationale")
                statusMessage.value = "Camera access is needed for SLAM tracking..."
                Toast.makeText(
                    this,
                    "Camera is required for AR-based indoor navigation",
                    Toast.LENGTH_LONG
                ).show()
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                Log.d(TAG, "Requesting camera permission")
                statusMessage.value = "Requesting camera permission..."
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /**
     * Initialize and start ARCore when user clicks SLAM button
     */
    internal fun startSLAM() {
        Log.d(TAG, "Starting SLAM")

        try {
            if (!::arCoreManager.isInitialized) {
                arCoreManager = ARCoreManager(this)

                if (!arCoreManager.isARCoreSupported()) {
                    Log.e(TAG, "ARCore not supported on this device")
                    Toast.makeText(this, "ARCore not supported on this device", Toast.LENGTH_LONG).show()
                    return
                }

                Log.d(TAG, "ARCore supported, creating GL surface")
                glSurfaceView = arCoreManager.createGLSurfaceView(this)

                Log.d(TAG, "Initializing ARCore session")
                if (!arCoreManager.initializeSession(this)) {
                    Log.e(TAG, "Failed to initialize ARCore session")
                    Toast.makeText(this, "Failed to initialize ARCore", Toast.LENGTH_LONG).show()
                    return
                }
            }

            Log.d(TAG, "ARCore session initialized successfully")
            arCoreManager.resume()
            slamViewActive.value = true
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception during ARCore initialization", e)
            Toast.makeText(this, "ARCore error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Check if ARCore manager is initialized
     */
    internal fun isArCoreInitialized(): Boolean {
        return ::arCoreManager.isInitialized
    }
    
    /**
     * Stop SLAM and close camera view
     */
    internal fun stopSLAM() {
        Log.d(TAG, "Stopping SLAM")
        slamViewActive.value = false
        if (::arCoreManager.isInitialized) {
            arCoreManager.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, slamViewActive=${slamViewActive.value}")
        
        // Only resume if SLAM view is active
        if (slamViewActive.value && ::arCoreManager.isInitialized) {
            try {
                arCoreManager.resume()
                Log.d(TAG, "ARCore session resumed")
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming ARCore session", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        
        if (::arCoreManager.isInitialized) {
            try {
                arCoreManager.pause()
                Log.d(TAG, "ARCore session paused")
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing ARCore session", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        
        if (::arCoreManager.isInitialized) {
            arCoreManager.destroy()
        }
    }
}

/**
 * Loading screen shown while waiting for permissions/ARCore
 */
@Composable
fun LoadingScreen(statusMessage: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "SmartNav",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --- SmartNavApp and other composables remain the same ---
// They will now only be composed *after* arCoreManager is ready.


/**
 * Main App Composable
 */
@Composable
fun SmartNavApp(viewModel: SensorViewModel = viewModel()) {
    // Get ARCore manager from activity
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    
    // Track if SLAM view is active
    val slamViewActive by activity?.slamViewActive ?: remember { mutableStateOf(false) }
    
    // Check if ARCore is initialized
    val isArCoreReady = activity?.isArCoreInitialized() == true
    
    // Set up ARCore manager in ViewModel when SLAM is started
    LaunchedEffect(slamViewActive) {
        if (slamViewActive) {
            activity?.let { mainActivity ->
                if (mainActivity.isArCoreInitialized()) {
                    viewModel.arCoreManager = mainActivity.arCoreManager
                }
            }
        }
    }

    // Collect tracking status from ARCore manager (only when initialized)
    val arCoreTrackingStatus by if (slamViewActive && isArCoreReady) {
        activity!!.arCoreManager.trackingStatus.collectAsState()
    } else {
        remember { mutableStateOf("Not started") }
    }
    
    val isARTracking by if (slamViewActive && isArCoreReady) {
        activity!!.arCoreManager.isTracking.collectAsState()
    } else {
        remember { mutableStateOf(false) }
    }

    val navigationState by viewModel.navigationState.collectAsState()

    // Show fullscreen SLAM camera view when active
    if (slamViewActive && isArCoreReady) {
        SlamFullscreenView(
            activity = activity!!,
            trackingStatus = arCoreTrackingStatus,
            onClose = { activity.stopSLAM() }
        )
    } else {
        // Normal app view
        Scaffold(
            topBar = {
                SmartNavTopBar(
                    drDistance = navigationState.drDistance,
                    slamDistance = navigationState.slamDistance,
                    driftError = navigationState.driftError,
                    arTrackingStatus = arCoreTrackingStatus,
                    isARTracking = isARTracking
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Main content area with path visualization
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // Path visualization (full area)
                    PathVisualization(
                        modifier = Modifier.fillMaxSize(),
                        drPath = navigationState.drPath.positions,
                        slamPath = navigationState.slamPath.positions,
                        obstaclePoints = navigationState.obstaclePoints
                    )
                }

                // Sensor info panel
                SensorInfoPanel(
                    stepCount = navigationState.stepCount,
                    accelX = navigationState.currentSensorData.accelerometerX,
                    accelY = navigationState.currentSensorData.accelerometerY,
                    accelZ = navigationState.currentSensorData.accelerometerZ,
                    arStatus = arCoreTrackingStatus
                )

                // SLAM Button
                Button(
                    onClick = { activity?.startSLAM() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2196F3)
                    )
                ) {
                    Text("Start SLAM", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                // Control buttons
                ControlPanel(
                    isTracking = navigationState.isTracking,
                    onStartStop = {
                        if (navigationState.isTracking) {
                            viewModel.stopTracking()
                        } else {
                            viewModel.startTracking()
                        }
                    },
                    onReset = { viewModel.resetPaths() }
                )
            }
        }
    }
}

/**
 * Fullscreen SLAM camera view with obstacle detection
 */
@Composable
fun SlamFullscreenView(
    activity: MainActivity,
    trackingStatus: String,
    onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Fullscreen camera with SLAM visualization
        activity.arCoreManager.glSurfaceView?.let { glView ->
            AndroidView(
                factory = { context ->
                    // Remove from parent if already attached
                    (glView.parent as? android.view.ViewGroup)?.removeView(glView)
                    glView
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Top bar with status and close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status
            Column {
                Text(
                    text = "SLAM Active",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = trackingStatus,
                    color = if (trackingStatus.contains("Tracking")) Color.Green else Color.Yellow,
                    fontSize = 14.sp
                )
            }
            
            // Close button
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red.copy(alpha = 0.8f)
                )
            ) {
                Text("Close", color = Color.White)
            }
        }
        
        // Instructions at bottom
        Text(
            text = "Point camera at objects to detect obstacles",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp)
        )
    }
}

/**
 * Top bar with statistics
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartNavTopBar(
    drDistance: Float,
    slamDistance: Float,
    driftError: Float,
    arTrackingStatus: String = "Unknown",
    isARTracking: Boolean = false
) {
    val trackingColor = if (isARTracking) Color(0xFF4CAF50) else Color(0xFFFFA726)
    
    TopAppBar(
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SmartNav", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    // AR Status indicator
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(trackingColor, shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = arTrackingStatus,
                        fontSize = 10.sp,
                        color = trackingColor
                    )
                }
                Text(
                    "DR: ${"%.2f".format(drDistance)}m | SLAM: ${"%.2f".format(slamDistance)}m | Drift: ${"%.2f".format(driftError)}m",
                    fontSize = 12.sp
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

/**
 * Canvas visualization of both paths
 * Dynamic grid with auto-zoom and pinch-to-zoom support
 */
@Composable
fun PathVisualization(
    modifier: Modifier = Modifier,
    drPath: List<Position3D>,
    slamPath: List<Position3D>,
    obstaclePoints: List<Position3D> = emptyList()
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    // Dynamic scale - user can zoom, and auto-adjusts to fit all points
    var userScale by remember { mutableFloatStateOf(1f) }
    var baseScale by remember { mutableFloatStateOf(100f) } // 100 pixels = 1 meter at zoom 1x
    
    // Pan offset for dragging
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    
    // Calculate the maximum extent of all points to auto-fit (include obstacles)
    val allPoints = drPath + slamPath + obstaclePoints
    val maxExtent = remember(allPoints.size) {
        if (allPoints.isEmpty()) 1f
        else {
            val maxX = allPoints.maxOfOrNull { kotlin.math.abs(it.x) } ?: 1f
            val maxZ = allPoints.maxOfOrNull { kotlin.math.abs(it.z) } ?: 1f
            maxOf(maxX, maxZ, 1f)
        }
    }

    Box(
        modifier = modifier.background(Color(0xFF1A1A2E))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Pinch to zoom
                        userScale = (userScale * zoom).coerceIn(0.1f, 10f)
                        // Pan/drag
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
        ) {
            val centerX = size.width / 2 + offsetX
            val centerY = size.height / 2 + offsetY
            
            // Auto-adjust base scale to fit all points with padding
            val screenMin = minOf(size.width, size.height) / 2
            val autoScale = if (maxExtent > 0.1f) {
                (screenMin * 0.7f) / maxExtent // 70% of half screen
            } else {
                100f
            }
            
            // Final scale combines auto-fit and user zoom
            val scale = autoScale * userScale

            // Draw dynamic grid
            val gridSpacing = calculateGridSpacing(scale)
            val gridColor = Color.Gray.copy(alpha = 0.2f)
            
            // Vertical grid lines
            val startXGrid = ((0 - centerX) / gridSpacing).toInt() - 1
            val endXGrid = ((size.width - centerX) / gridSpacing).toInt() + 1
            for (i in startXGrid..endXGrid) {
                val x = centerX + i * gridSpacing
                drawLine(
                    color = if (i == 0) Color.Gray.copy(alpha = 0.5f) else gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = if (i == 0) 2f else 1f
                )
            }
            
            // Horizontal grid lines
            val startYGrid = ((0 - centerY) / gridSpacing).toInt() - 1
            val endYGrid = ((size.height - centerY) / gridSpacing).toInt() + 1
            for (i in startYGrid..endYGrid) {
                val y = centerY + i * gridSpacing
                drawLine(
                    color = if (i == 0) Color.Gray.copy(alpha = 0.5f) else gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = if (i == 0) 2f else 1f
                )
            }

            // Draw origin marker
            drawCircle(
                color = Color.White,
                radius = 8f,
                center = Offset(centerX, centerY)
            )

            // Draw Dead Reckoning path (RED)
            if (drPath.size >= 2) {
                val drPathShape = Path()
                val firstPoint = drPath[0].to2DOffset(scale, centerX, centerY)
                drPathShape.moveTo(firstPoint.x, firstPoint.y)

                for (i in 1 until drPath.size) {
                    val point = drPath[i].to2DOffset(scale, centerX, centerY)
                    drPathShape.lineTo(point.x, point.y)
                }

                drawPath(
                    path = drPathShape,
                    color = Color(0xFFFF4444),  // Red
                    style = Stroke(width = 4f)
                )

                // Draw current DR position
                val lastDR = drPath.last().to2DOffset(scale, centerX, centerY)
                drawCircle(
                    color = Color(0xFFFF4444),
                    radius = 10f,
                    center = lastDR
                )
            }

            // Draw SLAM path (BLUE)
            if (slamPath.size >= 2) {
                val slamPathShape = Path()
                val firstPoint = slamPath[0].to2DOffset(scale, centerX, centerY)
                slamPathShape.moveTo(firstPoint.x, firstPoint.y)

                for (i in 1 until slamPath.size) {
                    val point = slamPath[i].to2DOffset(scale, centerX, centerY)
                    slamPathShape.lineTo(point.x, point.y)
                }

                drawPath(
                    path = slamPathShape,
                    color = Color(0xFF4444FF),  // Blue
                    style = Stroke(width = 4f)
                )

                // Draw current SLAM position
                val lastSLAM = slamPath.last().to2DOffset(scale, centerX, centerY)
                drawCircle(
                    color = Color(0xFF4444FF),
                    radius = 10f,
                    center = lastSLAM
                )
            }
            
            // Draw obstacle points (GREEN dots - environment map from SLAM camera)
            if (obstaclePoints.isNotEmpty()) {
                // Sample obstacles to avoid drawing too many (max 500 for performance)
                val sampled = if (obstaclePoints.size > 500) {
                    obstaclePoints.filterIndexed { index, _ -> index % (obstaclePoints.size / 500 + 1) == 0 }
                } else {
                    obstaclePoints
                }
                
                for (obstacle in sampled) {
                    val point = obstacle.to2DOffset(scale, centerX, centerY)
                    // Only draw if within visible area
                    if (point.x >= -50 && point.x <= size.width + 50 &&
                        point.y >= -50 && point.y <= size.height + 50) {
                        drawCircle(
                            color = Color(0xFF00FF00).copy(alpha = 0.6f),  // Green with transparency
                            radius = 4f,
                            center = point
                        )
                    }
                }
            }
        }

        // Legend
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color(0xFFFF4444))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dead Reckoning", color = Color.White, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color(0xFF4444FF))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("SLAM", color = Color.White, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color(0xFF00FF00))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Obstacles (${obstaclePoints.size})", color = Color.White, fontSize = 14.sp)
            }
        }
        
        // Zoom controls
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            // Zoom In button
            IconButton(
                onClick = { userScale = (userScale * 1.3f).coerceIn(0.1f, 10f) },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = Color.White)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Zoom level indicator
            Text(
                text = "${"%.1f".format(userScale)}x",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Zoom Out button
            IconButton(
                onClick = { userScale = (userScale / 1.3f).coerceIn(0.1f, 10f) },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            ) {
                Text("−", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Reset view button
            TextButton(
                onClick = { 
                    userScale = 1f
                    offsetX = 0f
                    offsetY = 0f
                },
                modifier = Modifier
                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            ) {
                Text("Reset", color = Color.White, fontSize = 10.sp)
            }
        }
        
        // Scale indicator (bottom)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            val metersPerGrid = 1f / (baseScale * userScale / calculateGridSpacing(baseScale * userScale))
            Text(
                text = "Grid: ${"%.2f".format(metersPerGrid)}m",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Calculate appropriate grid spacing based on zoom level
 */
private fun calculateGridSpacing(scale: Float): Float {
    // Target grid lines every ~50-150 pixels
    val targetSpacing = 80f
    
    // Calculate meters per target spacing
    val metersPerSpacing = targetSpacing / scale
    
    // Round to nice values: 0.1, 0.2, 0.5, 1, 2, 5, 10, etc.
    val magnitude = kotlin.math.floor(kotlin.math.log10(metersPerSpacing.toDouble())).toInt()
    val normalized = metersPerSpacing / Math.pow(10.0, magnitude.toDouble()).toFloat()
    
    val niceValue = when {
        normalized < 1.5f -> 1f
        normalized < 3.5f -> 2f
        normalized < 7.5f -> 5f
        else -> 10f
    }
    
    val gridMeters = niceValue * Math.pow(10.0, magnitude.toDouble()).toFloat()
    return gridMeters * scale
}

/**
 * Sensor information panel
 */
@Composable
fun SensorInfoPanel(
    stepCount: Int,
    accelX: Float,
    accelY: Float,
    accelZ: Float,
    arStatus: String = "Unknown"
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text("Sensor Data", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Steps: $stepCount")
            Text("Accel: X=${"%.2f".format(accelX)} Y=${"%.2f".format(accelY)} Z=${"%.2f".format(accelZ)}")
            Spacer(modifier = Modifier.height(4.dp))
            Text("AR/SLAM Status: $arStatus", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

/**
 * Control button panel
 */
@Composable
fun ControlPanel(
    isTracking: Boolean,
    onStartStop: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(
            onClick = onStartStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTracking) Color.Red else Color.Green
            )
        ) {
            Text(if (isTracking) "Stop" else "Start")
        }

        Button(onClick = onReset) {
            Text("Reset")
        }
    }
}

/**
 * Material 3 Theme
 */
@Composable
fun SmartNavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6200EE),
            secondary = Color(0xFF03DAC6),
            background = Color(0xFF121212)
        ),
        content = content
    )
}