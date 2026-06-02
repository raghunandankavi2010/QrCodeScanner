package com.example.qrcodescanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.FileProvider
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.ScaleGestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.qrcodescanner.ui.theme.QrCodeScannerTheme
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
object Scanner

@Serializable
data class Result(val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QrCodeScannerTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = Scanner) {
                    composable<Scanner> {
                        QrScannerScreen(navController)
                    }
                    composable<Result> { backStackEntry ->
                        val result: Result = backStackEntry.toRoute()
                        ResultScreen(result.text, navController)
                    }
                }
            }
        }
    }
}

@Composable
fun QrScannerScreen(navController: NavController) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (hasCameraPermission) {
            QrScannerView(navController)
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Camera permission is required to scan QR codes.")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                        Text(text = "Grant Permission")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerView(navController: NavController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var isGeneratingReport by remember { mutableStateOf(false) }
    var isFlashOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var isNavigating by remember { mutableStateOf(false) }

    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                
                // Pinch to zoom support
                val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        cameraInfo?.zoomState?.value?.let { zoomState ->
                            val currentZoomRatio = zoomState.zoomRatio
                            cameraControl?.setZoomRatio(currentZoomRatio * detector.scaleFactor)
                        }
                        return true
                    }
                }
                val scaleGestureDetector = ScaleGestureDetector(ctx, listener)
                
                previewView.setOnTouchListener { v, event ->
                    scaleGestureDetector.onTouchEvent(event)
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point).build()
                        cameraControl?.startFocusAndMetering(action)
                    }
                    v.performClick()
                    true
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1920, 1080),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()
                        )
                        .build()

                    // Single-threaded executor — no synchronization needed.
                    var frameCounter = 0
                    var lastEvIndex = 0
                    var emptyFrameCount = 0
                    var lastZoomAdjustFrame = -100

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !isNavigating) {
                            frameCounter++

                            // Proportional EV steps so direct sunlight gets corrected in ~150 ms, not ~2 s.
                            if (frameCounter % 5 == 0) {
                                val info = cameraInfo
                                val ctrl = cameraControl
                                if (info != null && ctrl != null &&
                                    info.exposureState.isExposureCompensationSupported) {
                                    val luminance = centerLuminance(imageProxy)
                                    val range = info.exposureState.exposureCompensationRange
                                    val delta = when {
                                        luminance > 220.0 -> -3
                                        luminance > 180.0 -> -2
                                        luminance > 150.0 -> -1
                                        luminance <  40.0 -> 3
                                        luminance <  70.0 -> 2
                                        luminance < 100.0 -> 1
                                        else -> 0
                                    }
                                    if (delta != 0) {
                                        val newIndex = (lastEvIndex + delta)
                                            .coerceIn(range.lower, range.upper)
                                        if (newIndex != lastEvIndex) {
                                            lastEvIndex = newIndex
                                            ctrl.setExposureCompensationIndex(newIndex)
                                        }
                                    }
                                }
                            }

                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                            barcodeScanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    if (barcodes.isNotEmpty()) {
                                        emptyFrameCount = 0
                                        if (!isNavigating) {
                                            barcodes[0].displayValue?.let { text ->
                                                isNavigating = true

                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    vibrator.vibrate(50)
                                                }

                                                navController.navigate(Result(text))
                                            }
                                        }
                                    } else {
                                        // After ~0.5 s of no decode, ramp zoom up 0.1x per 5 frames to 2.0x.
                                        emptyFrameCount++
                                        if (emptyFrameCount >= 15 &&
                                            frameCounter - lastZoomAdjustFrame >= 5) {
                                            val info = cameraInfo
                                            val ctrl = cameraControl
                                            if (info != null && ctrl != null) {
                                                val currentZoom = info.zoomState.value?.zoomRatio ?: 1f
                                                if (currentZoom < 2.0f) {
                                                    val newZoom = (currentZoom + 0.1f).coerceAtMost(2.0f)
                                                    ctrl.setZoomRatio(newZoom)
                                                    lastZoomAdjustFrame = frameCounter
                                                }
                                            }
                                        }
                                    }
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        } else {
                            imageProxy.close()
                        }
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )
                        cameraControl = camera.cameraControl
                        cameraInfo = camera.cameraInfo

                        // Center-weighted AF/AE/AWB — bright edges don't steal metering from the QR.
                        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                        val centerPoint = factory.createPoint(0.5f, 0.5f)
                        val action = FocusMeteringAction.Builder(
                            centerPoint,
                            FocusMeteringAction.FLAG_AF or
                                FocusMeteringAction.FLAG_AE or
                                FocusMeteringAction.FLAG_AWB
                        )
                            .setAutoCancelDuration(5, TimeUnit.SECONDS)
                            .build()
                        camera.cameraControl.startFocusAndMetering(action)
                    } catch (e: Exception) {
                        Log.e("QrScanner", "Use case binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // Viewfinder Overlay
        QrScannerOverlay()

        // UI Controls Layer
        Box(modifier = Modifier.fillMaxSize()) {
            // Sample diagnostic report (free, HTML -> PDF via Android's print framework)
            Button(
                onClick = {
                    if (isGeneratingReport) return@Button
                    isGeneratingReport = true
                    scope.launch {
                        try {
                            val file = generateDiagnosticReportPdf(context)
                            sharePdf(context, file)
                        } catch (e: Exception) {
                            Log.e("QrScanner", "Report generation failed", e)
                            Toast.makeText(
                                context,
                                "Could not create report: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            isGeneratingReport = false
                        }
                    }
                },
                enabled = !isGeneratingReport,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                if (isGeneratingReport) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generating…")
                } else {
                    Text("Sample Report PDF")
                }
            }

            // Flashlight Toggle
            IconButton(
                onClick = {
                    isFlashOn = !isFlashOn
                    cameraControl?.enableTorch(isFlashOn)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isFlashOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                    contentDescription = "Toggle Flash",
                    tint = Color.White
                )
            }
        }
    }
}


@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(text: String, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isGeneratingPdf by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Result") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Scanned Content:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    if (isGeneratingPdf) return@Button
                    isGeneratingPdf = true
                    scope.launch {
                        try {
                            val file = generateScanPdf(context, text)
                            sharePdf(context, file)
                        } catch (e: Exception) {
                            Log.e("ResultScreen", "PDF generation failed", e)
                            Toast.makeText(
                                context,
                                "Could not create PDF: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            isGeneratingPdf = false
                        }
                    }
                },
                enabled = !isGeneratingPdf,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGeneratingPdf) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generating PDF…")
                } else {
                    Text("Save / Share as PDF")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan Again")
            }
        }
    }
}

/** Fires an ACTION_SEND chooser for [file] as a PDF, granting temporary read access. */
private fun sharePdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share QR scan PDF"))
}

/**
 * Average luminance of the frame's center 50% region, computed from the Y plane
 * of the YUV_420_888 image. Sampling every 16th row/column keeps this <1 ms even
 * at 1080p. Returns 128.0 (neutral) if the buffer is unreadable.
 *
 * Used to drive exposure compensation so sunlight glare doesn't blow out the QR
 * code's white modules and kill the contrast ML Kit depends on.
 */
private fun centerLuminance(imageProxy: ImageProxy): Double {
    val plane = imageProxy.planes[0]
    val buffer = plane.buffer.duplicate()
    val rowStride = plane.rowStride
    val width = imageProxy.width
    val height = imageProxy.height

    val rowStart = height / 4
    val rowEnd = 3 * height / 4
    val colStart = width / 4
    val colEnd = 3 * width / 4

    var sum = 0L
    var count = 0
    val step = 16
    var r = rowStart
    while (r < rowEnd) {
        var c = colStart
        while (c < colEnd) {
            val idx = r * rowStride + c
            if (idx < buffer.limit()) {
                sum += (buffer.get(idx).toInt() and 0xFF)
                count++
            }
            c += step
        }
        r += step
    }
    return if (count > 0) sum.toDouble() / count else 128.0
}

@Composable
fun QrScannerOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val linePosition by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "linePosition"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val rectSize = width * 0.75f
        val left = (width - rectSize) / 2
        val top = (height - rectSize) / 2

        with(drawContext.canvas.nativeCanvas) {
            val checkPoint = saveLayer(null, null)

            // 1. Draw semi-transparent dimming overlay
            drawRect(
                color = Color.Black.copy(alpha = 0.6f)
            )

            // 2. Clear the viewfinder rectangle (the "hole")
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(left, top),
                size = ComposeSize(rectSize, rectSize),
                cornerRadius = CornerRadius(24.dp.toPx()),
                blendMode = BlendMode.Clear
            )

            // 3. Draw the white border corners
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = ComposeSize(rectSize, rectSize),
                cornerRadius = CornerRadius(24.dp.toPx()),
                style = Stroke(width = 3.dp.toPx())
            )

            // 4. Draw the scanning laser line
            val lineY = top + (rectSize * linePosition)
            drawLine(
                color = Color.Green,
                start = Offset(left + 20.dp.toPx(), lineY),
                end = Offset(left + rectSize - 20.dp.toPx(), lineY),
                strokeWidth = 2.dp.toPx()
            )

            restoreToCount(checkPoint)
        }
    }
}
