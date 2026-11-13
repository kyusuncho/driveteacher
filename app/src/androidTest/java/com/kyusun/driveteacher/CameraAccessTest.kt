package com.kyusun.driveteacher

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Which cameras can a third-party Pleos app see and open? Results: `adb logcat -s CameraAccessTest`. */
class CameraAccessTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(CameraManager::class.java)

    @Test
    fun appCanSeeAtLeastOneCamera() {
        val ids = manager.cameraIdList
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        Log.i(TAG, "FEATURE_CAMERA_ANY=$hasFeature, cameras visible to app: ${ids.size}")
        ids.forEach { Log.i(TAG, describe(it)) }
        assertTrue("No cameras exposed to apps via CameraManager", ids.isNotEmpty())
    }

    @Test
    fun everyVisibleCameraCanBeOpened() {
        val failures = manager.cameraIdList.mapNotNull { id -> open(id)?.let { "$id: $it" } }
        failures.forEach { Log.w(TAG, "open failed $it") }
        assertTrue("Cameras that failed to open: $failures", failures.isEmpty())
    }

    /**
     * Saves one frame per camera to the app cache as camera-<id>.jpg.
     * connectedAndroidTest uninstalls the app (and the files), so run it directly and pull:
     *   ./gradlew installDebug installDebugAndroidTest
     *   adb shell am instrument -w -e class com.kyusun.driveteacher.CameraAccessTest#captureFrameFromEachCamera \
     *     com.kyusun.driveteacher.test/androidx.test.runner.AndroidJUnitRunner
     *   adb exec-out run-as com.kyusun.driveteacher --user 10 cat cache/camera-0.jpg > assets/camera-0.jpg
     * (AAOS runs apps as user 10, not 0; check with `adb shell am get-current-user`.)
     */
    @Test
    fun captureFrameFromEachCamera() {
        manager.cameraIdList.forEach { id ->
            val file = File(context.cacheDir, "camera-$id.jpg").apply { writeBytes(captureJpeg(id)) }
            Log.i(TAG, "saved ${file.path}")
        }
    }

    /** Streams JPEG frames from camera [id] and returns one taken after a short warm-up (auto exposure). */
    private fun captureJpeg(id: String): ByteArray {
        val config = checkNotNull(manager.getCameraCharacteristics(id)[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
        val size = config.getOutputSizes(ImageFormat.JPEG).maxBy { it.width * it.height }
        val thread = HandlerThread("capture-$id").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val device = AtomicReference<CameraDevice>()
        val jpeg = AtomicReference<ByteArray>()
        val done = CountDownLatch(1)
        var frames = 0

        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                if (++frames > WARMUP_FRAMES && jpeg.get() == null) {
                    val buffer = image.planes[0].buffer
                    jpeg.set(ByteArray(buffer.remaining()).also { buffer.get(it) })
                    done.countDown()
                }
            }
        }, handler)

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                val request = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    .apply { addTarget(reader.surface) }
                    .build()
                session.setRepeatingRequest(request, null, handler)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) = done.countDown()
        }
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device.set(camera)
                val outputs = listOf(OutputConfiguration(reader.surface))
                camera.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, { handler.post(it) }, sessionCallback),
                )
            }
            override fun onDisconnected(camera: CameraDevice) = done.countDown()
            override fun onError(camera: CameraDevice, code: Int) = done.countDown()
        }, handler)

        try {
            done.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            device.get()?.close()
            reader.close()
            thread.quitSafely()
        }
        return checkNotNull(jpeg.get()) { "camera $id: no frame within $CAPTURE_TIMEOUT_SECONDS s" }
    }

    private fun describe(id: String): String {
        val c = manager.getCameraCharacteristics(id)
        val facing = when (c[CameraCharacteristics.LENS_FACING]) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
        val automotive = if (Build.VERSION.SDK_INT >= 33) {
            " automotiveLocation=${c[CameraCharacteristics.AUTOMOTIVE_LOCATION]}" +
                " automotiveLensFacing=${c[CameraCharacteristics.AUTOMOTIVE_LENS_FACING]?.toList()}"
        } else ""
        return "id=$id facing=$facing hwLevel=${c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]}" +
            " physicalIds=${c.physicalCameraIds}$automotive"
    }

    /** Opens and immediately closes the camera. Returns null on success, otherwise the reason. */
    private fun open(id: String): String? {
        val thread = HandlerThread("camera-$id").apply { start() }
        val done = CountDownLatch(1)
        var error: String? = "timeout"
        val callback = object : CameraDevice.StateCallback() {
            private fun finish(device: CameraDevice, result: String?) {
                error = result
                device.close()
                done.countDown()
            }
            override fun onOpened(device: CameraDevice) = finish(device, null)
            override fun onDisconnected(device: CameraDevice) = finish(device, "disconnected")
            override fun onError(device: CameraDevice, code: Int) = finish(device, "error code $code")
        }
        try {
            manager.openCamera(id, callback, Handler(thread.looper))
            done.await(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            error = e.toString()
        } finally {
            thread.quitSafely()
        }
        return error
    }

    private companion object {
        const val TAG = "CameraAccessTest"
        const val OPEN_TIMEOUT_SECONDS = 5L
        const val CAPTURE_TIMEOUT_SECONDS = 10L
        const val WARMUP_FRAMES = 5
    }
}
