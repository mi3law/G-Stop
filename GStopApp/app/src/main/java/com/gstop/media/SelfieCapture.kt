package com.gstop.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The three photographs a stop takes of the person stopping.
 *
 * Bound to the *stop screen*, not to the service. The camera is a while-in-use permission: a
 * foreground service started from an alarm is on shaky ground with it, whereas a visible activity
 * is not. If the stop screen never appears — notifications blocked, an OEM lock screen that
 * refuses the full-screen intent — no photographs are taken and the stop is otherwise unaffected.
 * The sound is the stop; this is only the record of it.
 *
 * No preview is bound. The screen stays black.
 */
class SelfieCapture(context: Context) {

    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)

    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    /** True once a front camera is open and ready to be asked for a frame. */
    suspend fun bind(owner: LifecycleOwner): Boolean {
        if (!hasPermission(appContext)) return false
        return try {
            val cameraProvider = awaitProvider()
            if (!cameraProvider.hasCamera(FRONT)) {
                Log.i(TAG, "no front camera on this device; stop photos are off")
                return false
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setJpegQuality(JPEG_QUALITY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                TARGET_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .build()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(owner, FRONT, capture)
            provider = cameraProvider
            imageCapture = capture
            true
        } catch (e: Exception) {
            // A camera that will not open must never take a stop down with it.
            Log.w(TAG, "front camera unavailable: ${e.message}")
            false
        }
    }

    /** Fire-and-forget: the stop does not wait on the shutter. */
    fun capture(stopId: Long, slot: PhotoSlot) {
        val capture = imageCapture ?: return
        val file = StopMedia.photo(appContext, stopId, slot)
        if (file.exists()) return // a replayed request for a slot already taken
        file.parentFile?.mkdirs()

        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        try {
            capture.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.i(TAG, "stop $stopId: ${slot.name.lowercase()} photo saved")
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "stop $stopId: ${slot.name.lowercase()} photo failed", exception)
                        file.delete()
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "stop $stopId: could not take ${slot.name.lowercase()} photo: ${e.message}")
        }
    }

    fun release() {
        runCatching { provider?.unbindAll() }
        provider = null
        imageCapture = null
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({ continuation.resume(future.get()) }, executor)
        }

    companion object {
        private const val TAG = "GStop.Selfie"
        private const val JPEG_QUALITY = 80

        /** Around two megapixels: enough to see a posture, small enough to keep for years. */
        private val TARGET_SIZE = Size(1200, 1600)

        private val FRONT = CameraSelector.DEFAULT_FRONT_CAMERA

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

        fun deviceHasFrontCamera(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }
}
