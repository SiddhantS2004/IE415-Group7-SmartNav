package com.smartnav.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // State to track ARCore readiness
    private val arCoreReady = mutableStateOf(false)
    
    // State to track permission status
    private val cameraPermissionGranted = mutableStateOf(false)
    
    // State to track initialization status message
    private val statusMessage = mutableStateOf("Checking camera permission...")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            Log.d(TAG, "Camera permission result: $isGranted")
            if (isGranted) {
                cameraPermissionGranted.value = true
                statusMessage.value = "Camera permission granted. Initializing AR..."
                // Initialize ARCore after permission is granted
                initializeARCore()
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
                            // ARCore is ready - show main app
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
                statusMessage.value = "Camera permission granted. Initializing AR..."
                // Permission already granted - initialize ARCore
                initializeARCore()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d(TAG, "Should show permission rationale")
                statusMessage.value = "Camera access is needed for SLAM tracking..."
                // Show rationale and then request
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

    private fun initializeARCore() {
        Log.d(TAG, "Initializing ARCore")
        statusMessage.value = "Checking ARCore availability..."

        try {
            arCoreManager = ARCoreManager(this)

            if (!arCoreManager.isARCoreSupported()) {
                Log.e(TAG, "ARCore not supported on this device")
                statusMessage.value = "ARCore not supported on this device"
                Toast.makeText(this, "ARCore not supported on this device", Toast.LENGTH_LONG).show()
                return
            }

            statusMessage.value = "Starting AR session..."
            Log.d(TAG, "ARCore supported, initializing session")

            if (!arCoreManager.initializeSession(this)) {
                Log.e(TAG, "Failed to initialize ARCore session")
                statusMessage.value = "Failed to initialize ARCore. Please try again."
                Toast.makeText(this, "Failed to initialize ARCore", Toast.LENGTH_LONG).show()
                return
            }

            Log.d(TAG, "ARCore session initialized successfully")
            
            // Resume the session immediately after initialization
            arCoreManager.resume()
            
            // ARCore is ready - update state to show main UI
            arCoreReady.value = true
            statusMessage.value = "AR Ready!"
            
        } catch (e: Exception) {
            Log.e(TAG, "Exception during ARCore initialization", e)
            statusMessage.value = "Error: ${e.message}"
            Toast.makeText(this, "ARCore initialization error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, arCoreReady=${arCoreReady.value}")
        
        // Only resume if ARCore is fully initialized and ready
        if (arCoreReady.value && ::arCoreManager.isInitialized) {
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
    
    // Set up ARCore manager in ViewModel when activity is available
    // Since SmartNavApp is only shown when arCoreReady.value is true, 
    // arCoreManager is guaranteed to be initialized at this point
    LaunchedEffect(activity) {
        activity?.let { mainActivity ->
            viewModel.arCoreManager = mainActivity.arCoreManager
        }
    }

    // Collect tracking status from ARCore manager
    val arCoreTrackingStatus by activity?.arCoreManager?.trackingStatus?.collectAsState() 
        ?: remember { mutableStateOf("Not connected") }
    val isARTracking by activity?.arCoreManager?.isTracking?.collectAsState()
        ?: remember { mutableStateOf(false) }

    val navigationState by viewModel.navigationState.collectAsState()

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
            // Main visualization canvas
            PathVisualization(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                drPath = navigationState.drPath.positions,
                slamPath = navigationState.slamPath.positions
            )

            // Sensor info panel
            SensorInfoPanel(
                stepCount = navigationState.stepCount,
                accelX = navigationState.currentSensorData.accelerometerX,
                accelY = navigationState.currentSensorData.accelerometerY,
                accelZ = navigationState.currentSensorData.accelerometerZ,
                arStatus = arCoreTrackingStatus
            )

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
                onReset = { viewModel.resetPaths() },
                onSave = {
                    val summary = viewModel.saveSession()
                    // For now, just show in console
                    println(summary)
                }
            )
        }
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
 * This is where the magic happens - real-time trajectory display
 */
@Composable
fun PathVisualization(
    modifier: Modifier = Modifier,
    drPath: List<Position3D>,
    slamPath: List<Position3D>
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    Box(
        modifier = modifier.background(Color(0xFF1A1A2E))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val scale = 100f  // Scale factor: 100 pixels = 1 meter

            // Draw grid
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1f
            )
            drawLine(
                color = Color.Gray.copy(alpha = 0.3f),
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = 1f
            )

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
        }
    }
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
    onReset: () -> Unit,
    onSave: () -> Unit
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

        Button(onClick = onSave) {
            Text("Save")
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