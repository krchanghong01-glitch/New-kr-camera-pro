package com.kr.camerapro

import android.Manifest
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaActionSound
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kr.camerapro.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private lateinit var prefs: SharedPreferences
    private var backRes = Size(4096, 3072)
    private var frontRes = Size(2592, 1944)
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var isTorchOn = false

    private var currentMode = 0
    private val modeNames = arrayOf("FOTO", "VIDEO", "PORTRAIT", "PRO")

    private val shutterSound = MediaActionSound()
    private lateinit var gestureDetector: GestureDetector
    private var isGridEnabled = false
    private var timerSeconds = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("KR_CAMERA_PRO_PREFS", MODE_PRIVATE)
        loadSettings()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > Math.abs(e2.y - e1.y) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    currentMode = if (diffX > 0) (currentMode - 1 + modeNames.size) % modeNames.size else (currentMode + 1) % modeNames.size
                    runOnUiThread { updateModeUI() }
                    return true
                }
                return false
            }
        })
        binding.viewFinder.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100)
        } else {
            startCamera()
        }
    }

    private fun loadSettings() {
        backRes = when (prefs.getString("BACK_RES", "50MP")) {
            "50MP" -> Size(4096, 3072)
            "12MP" -> Size(3072, 2304)
            "4K" -> Size(3840, 2160)
            "1080p" -> Size(1920, 1080)
            else -> Size(1280, 720)
        }
        frontRes = when (prefs.getString("FRONT_RES", "5MP")) {
            "5MP" -> Size(2592, 1944)
            "1080p" -> Size(1920, 1080)
            else -> Size(1280, 720)
        }
        flashMode = when (prefs.getString("FLASH", "off")) {
            "on" -> ImageCapture.FLASH_MODE_ON
            "auto" -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        isGridEnabled = prefs.getBoolean("GRID", false)
        binding.gridOverlay.visibility = if (isGridEnabled) View.VISIBLE else View.GONE
        updateFlashIcon()
    }

    private fun updateFlashIcon() {
        binding.btnFlash.setImageResource(when {
            isTorchOn -> R.drawable.ic_flash_on
            flashMode == ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            flashMode == ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCamera(cameraProvider, lensFacing)
            setupButtons()
            updateModeUI()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider, facing: Int) {
        provider.unbindAll()
        val resSize = if (facing == CameraSelector.LENS_FACING_BACK) backRes else frontRes

        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(resSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .setTargetRotation(windowManager.defaultDisplay.rotation)
            .build()
            .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(resSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(if (facing == CameraSelector.LENS_FACING_FRONT) ImageCapture.FLASH_MODE_OFF else flashMode)
            .setTargetRotation(windowManager.defaultDisplay.rotation)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val cameraSelector = CameraSelector.Builder().requireLensFacing(facing).build()
        try {
            camera = provider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture)
            setTorchState(isTorchOn && facing == CameraSelector.LENS_FACING_BACK)
            binding.btnFlash.isEnabled = (facing == CameraSelector.LENS_FACING_BACK)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat mengakses kamera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTorchState(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    private fun updateModeUI() {
        binding.txtMode.text = modeNames[currentMode]
        binding.btnCapture.setImageResource(
            if (currentMode == 1 && recording != null) R.drawable.ic_stop
            else if (currentMode == 1) R.drawable.ic_video
            else R.drawable.ic_camera
        )
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            when (currentMode) {
                0 -> takePhoto()
                1 -> toggleRecording()
                2 -> takePhoto()
                3 -> takePhoto()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                isTorchOn = false
                setTorchState(false)
            }
            updateFlashIcon()
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                bindCamera(cameraProvider, lensFacing)
            }, ContextCompat.getMainExecutor(this))
        }

        binding.btnSettings.setOnClickListener { showSettings() }

        binding.btnFlash.setOnClickListener {
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                if (currentMode == 1 || isTorchOn) {
                    isTorchOn = !isTorchOn
                    setTorchState(isTorchOn)
                } else {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    prefs.edit().putString("FLASH", when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> "on"
                        ImageCapture.FLASH_MODE_AUTO -> "auto"
                        else -> "off"
                    }).apply()
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        bindCamera(cameraProvider, lensFacing)
                    }, ContextCompat.getMainExecutor(this))
                }
                updateFlashIcon()
            }
        }

        binding.thumbnail.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply { type = "image/*" })
        }
    }

    private fun takePhoto() {
        if (timerSeconds > 0) {
            startTimer { capturePhoto() }
        } else {
            capturePhoto()
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)

        val photoFile = File(cacheDir, "kr_temp_${System.currentTimeMillis()}.jpg")
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = (lensFacing == CameraSelector.LENS_FACING_FRONT)
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
            .setMetadata(metadata)
            .build()

        capture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bitmap = correctOrientation(photoFile)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "KR_Pro_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                }
                photoFile.delete()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Foto tersimpan", Toast.LENGTH_SHORT).show()
                    uri?.let {
                        val thumb = BitmapFactory.decodeStream(contentResolver.openInputStream(it))
                        binding.thumbnail.setImageBitmap(thumb)
                        binding.thumbnail.visibility = View.VISIBLE
                    }
                }
            }
            override fun onError(exception: ImageCaptureException) {
                photoFile.delete()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Gagal: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun correctOrientation(file: File): Bitmap {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val rotation = windowManager.defaultDisplay.rotation * 90
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun toggleRecording() {
        if (recording != null) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val videoFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "KR_Pro_Video_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(videoFile).build()
        recording = videoCapture?.output?.prepareRecording(this, outputOptions)
            ?.withAudioEnabled()
            ?.start(cameraExecutor) { recordEvent ->
                if (recordEvent is VideoRecordEvent.Finalize) {
                    runOnUiThread { stopRecording() }
                }
            }
        binding.recIndicator.visibility = View.VISIBLE
        updateModeUI()
        Toast.makeText(this, "Merekam video...", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        binding.recIndicator.visibility = View.GONE
        updateModeUI()
    }

    private fun startTimer(onFinish: () -> Unit) {
        onFinish()
    }

    private fun showSettings() {
        AlertDialog.Builder(this).apply {
            setTitle("Pengaturan")
            setItems(arrayOf("Kamera Belakang", "Kamera Depan", "Grid", "Timer")) { _, i ->
                when (i) {
                    0 -> showBackResDialog()
                    1 -> showFrontResDialog()
                    2 -> {
                        isGridEnabled = !isGridEnabled
                        prefs.edit().putBoolean("GRID", isGridEnabled).apply()
                        binding.gridOverlay.visibility = if (isGridEnabled) View.VISIBLE else View.GONE
                    }
                    3 -> showTimerDialog()
                }
            }
            show()
        }
    }

    private fun showBackResDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("Resolusi Belakang")
            setItems(arrayOf("50MP", "12MP", "4K", "1080p", "720p")) { _, i ->
                prefs.edit().putString("BACK_RES", arrayOf("50MP", "12MP", "4K", "1080p", "720p")[i]).apply()
                loadSettings()
                startCamera()
            }
            show()
        }
    }

    private fun showFrontResDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("Resolusi Depan")
            setItems(arrayOf("5MP", "1080p", "720p")) { _, i ->
                prefs.edit().putString("FRONT_RES", arrayOf("5MP", "1080p", "720p")[i]).apply()
                loadSettings()
                startCamera()
            }
            show()
        }
    }

    private fun showTimerDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("Timer")
            setItems(arrayOf("Off", "3 detik", "5 detik", "10 detik")) { _, i ->
                timerSeconds = when (i) { 1->3; 2->5; 3->10; else->0 }
            }
            show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
