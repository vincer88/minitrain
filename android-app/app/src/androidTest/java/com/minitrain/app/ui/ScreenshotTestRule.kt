package com.minitrain.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.TestWatcher
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple rule that stores Compose screenshots under [reports/screenshots] on the device.
 */
class ScreenshotTestRule : TestWatcher() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val screenshotDir: File by lazy {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(baseDir, "reports/screenshots").apply { mkdirs() }
    }

    fun capture(name: String, node: SemanticsNodeInteraction) {
        val image = node.captureToImage()
        saveBitmap("${timestamp()}_${name}.png", image)
    }

    private fun saveBitmap(fileName: String, image: ImageBitmap) {
        val androidBitmap = image.asAndroidBitmap()
        val file = File(screenshotDir, fileName)
        FileOutputStream(file).use { output ->
            androidBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return formatter.format(Date())
    }

}
