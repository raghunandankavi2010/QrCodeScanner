package com.example.qrcodescanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ScaleGestureDetector
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import kotlinx.serialization.Serializable
import java.util.concurrent.Executors

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
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    var isFlashOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    var isNavigating by remember { mutableStateOf(false) }

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
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage != null && !isNavigating) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            
                            barcodeScanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    if (barcodes.isNotEmpty() && !isNavigating) {
                                        val barcode = barcodes[0]
                                        barcode.displayValue?.let { text ->
                                            isNavigating = true
                                            navController.navigate(Result(text))
                                        }
                                        
                                        // Auto-zoom logic: zoom in if the QR code is too small in the frame
                                        barcode.boundingBox?.let { box ->
                                            val boxSize = maxOf(box.width(), box.height())
                                            val minDimension = minOf(image.width, image.height)
                                            
                                            // If QR code occupies less than 25% of the frame, zoom in
                                            if (boxSize < minDimension * 0.25) {
                                                cameraControl?.setZoomRatio(2.5f)
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
                onClick = { navController.popBackStack() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan Again")
            }
        }
    }
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
                size = Size(rectSize, rectSize),
                cornerRadius = CornerRadius(24.dp.toPx()),
                blendMode = BlendMode.Clear
            )

            // 3. Draw the white border corners
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(left, top),
                size = Size(rectSize, rectSize),
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
