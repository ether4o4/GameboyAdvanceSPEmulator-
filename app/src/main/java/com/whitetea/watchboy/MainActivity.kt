package com.whitetea.watchboy

import android.app.Activity
import android.media.AudioManager
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.min

class MainActivity : Activity() {
    companion object {
        private const val PICK_ROM = 42
        private const val FRAME_WIDTH = 240
        private const val FRAME_HEIGHT = 160
        private const val MAX_ROM_BYTES = 32L * 1024L * 1024L
        private const val JOY_B = 1 shl 0
        private const val JOY_SELECT = 1 shl 2
        private const val JOY_START = 1 shl 3
        private const val JOY_UP = 1 shl 4
        private const val JOY_DOWN = 1 shl 5
        private const val JOY_LEFT = 1 shl 6
        private const val JOY_RIGHT = 1 shl 7
        private const val JOY_A = 1 shl 8
        private const val JOY_L = 1 shl 10
        private const val JOY_R = 1 shl 11
    }

    private external fun nativeSetSaveDirectory(path: String)
    private external fun nativeSetStatePath(path: String)
    private external fun nativeStart(path: String): Boolean
    private external fun nativeRunFrame()
    private external fun nativeSetButtons(bits: Int)
    private external fun nativeCopyFrame(pixels: IntArray)
    private external fun nativeReadAudio(samples: ShortArray): Int
    private external fun nativeSampleRate(): Int
    private external fun nativeSaveState(): Boolean
    private external fun nativeLoadState(): Boolean
    private external fun nativeStop()
    private external fun nativeShutdown()

    private lateinit var watchView: WatchBoyView
    private val coreLock = Any()
    private val coreThread = HandlerThread("WatchBoy-Core").apply { start() }
    private val coreHandler by lazy { Handler(coreThread.looper) }
    private val framePixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT)
    private val frameBitmap = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.RGB_565)
    @Volatile private var activityResumed = false
    @Volatile private var playing = false
    @Volatile private var heldButtons = 0
    @Volatile private var pulseButtons = 0
    @Volatile private var pulseEndsAt = 0L
    private var nextFrameAt = 0L
    private var audioTrack: AudioTrack? = null
    @Volatile private var audioActive = false
    private var audioThread: Thread? = null

    private val frameLoop = object : Runnable {
        override fun run() {
            if (!activityResumed || !playing) return
            synchronized(coreLock) {
                val pulse = if (System.nanoTime() < pulseEndsAt) pulseButtons else 0
                nativeSetButtons(heldButtons or pulse)
                nativeRunFrame()
            }
            watchView.postInvalidateOnAnimation()
            val now = System.nanoTime()
            nextFrameAt += 16_666_667L
            if (nextFrameAt < now) nextFrameAt = now
            coreHandler.postDelayed(this, ((nextFrameAt - now) / 1_000_000L).coerceAtLeast(0L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        val romDirectory = File(filesDir, "roms").apply { mkdirs() }
        synchronized(coreLock) { nativeSetSaveDirectory(romDirectory.absolutePath) }
        watchView = WatchBoyView()
        setContentView(watchView)
        intent?.data?.let { importRom(it) }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (playing) startPlaybackWorkers()
    }

    override fun onPause() {
        activityResumed = false
        stopPlaybackWorkers(saveState = true)
        super.onPause()
    }

    override fun onDestroy() {
        stopPlaybackWorkers(saveState = true)
        synchronized(coreLock) { nativeShutdown() }
        coreThread.quitSafely()
        super.onDestroy()
    }

    @Deprecated("Use system back navigation")
    override fun onBackPressed() {
        if (playing) returnToLibrary() else super.onBackPressed()
    }

    private fun openRomPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-gba-rom"))
        }
        try {
            startActivityForResult(intent, PICK_ROM)
        } catch (_: Exception) {
            toast("No file picker is available on this watch")
        }
    }

    @Deprecated("Legacy document-picker callback for broad Wear OS compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_ROM && resultCode == RESULT_OK) data?.data?.let { importRom(it) }
    }

    private fun importRom(uri: Uri) {
        val name = displayName(uri).substringAfterLast('/').ifBlank { "game.gba" }
        val extension = name.substringAfterLast('.', "gba").lowercase()
        if (extension !in setOf("gba", "agb", "bin")) {
            toast("Choose a .gba, .agb, or .bin ROM")
            return
        }
        val romFolder = File(filesDir, "roms").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(96)
        val target = File(romFolder, safeName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ROM_BYTES) throw IllegalArgumentException("ROM exceeds 32 MB")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalStateException("The selected file could not be opened")
            if (target.length() !in 32_768L..MAX_ROM_BYTES) {
                target.delete()
                toast("ROM must be between 32 KB and 32 MB")
                return
            }
            startRom(target)
        } catch (error: Exception) {
            target.delete()
            toast("Import failed: ${error.message ?: "could not read file"}")
        }
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) ?: "game.gba" else uri.lastPathSegment ?: "game.gba"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "game.gba"
        } finally {
            cursor?.close()
        }
    }

    private fun startRom(file: File) {
        stopPlaybackWorkers(saveState = true)
        val loaded = synchronized(coreLock) {
            nativeSetSaveDirectory(File(filesDir, "roms").absolutePath)
            nativeSetStatePath(File(file.absolutePath + ".state").absolutePath)
            nativeStart(file.absolutePath).also { if (it) nativeLoadState() }
        }
        if (!loaded) {
            playing = false
            watchView.showLibrary()
            toast("mGBA could not open that ROM")
            return
        }
        playing = true
        watchView.showGame(file.nameWithoutExtension)
        if (activityResumed) startPlaybackWorkers()
    }

    private fun returnToLibrary() {
        stopPlaybackWorkers(saveState = true)
        synchronized(coreLock) { nativeStop() }
        playing = false
        watchView.showLibrary()
    }

    private fun startPlaybackWorkers() {
        if (!playing || !activityResumed) return
        coreHandler.removeCallbacks(frameLoop)
        nextFrameAt = System.nanoTime()
        coreHandler.post(frameLoop)
        startAudio()
    }

    private fun stopPlaybackWorkers(saveState: Boolean) {
        coreHandler.removeCallbacks(frameLoop)
        if (saveState && playing) synchronized(coreLock) { nativeSaveState() }
        heldButtons = 0
        pulseButtons = 0
        synchronized(coreLock) { if (playing) nativeSetButtons(0) }
        stopAudio()
    }

    private fun startAudio() {
        if (audioActive || !playing) return
        val sourceRate = synchronized(coreLock) { nativeSampleRate().coerceIn(8000, 96000) }
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()?.coerceIn(8000, 96000) ?: 48000
        val minBytes = AudioTrack.getMinBufferSize(
            outputRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(outputRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setBufferSizeInBytes(minBytes * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return
        }
        audioTrack = track
        try { track.play() } catch (_: Exception) { track.release(); audioTrack = null; return }
        audioActive = true
        audioThread = Thread {
            val samples = ShortArray(2048)
            val output = ShortArray(4096)
            var phase = 0
            while (audioActive && !Thread.currentThread().isInterrupted) {
                val count = synchronized(coreLock) { nativeReadAudio(samples) }
                if (count >= 2) {
                    var outCount = 0
                    var frame = 0
                    while (frame + 1 < count && outCount + 1 < output.size) {
                        phase += outputRate
                        while (phase >= sourceRate && outCount + 1 < output.size) {
                            output[outCount++] = samples[frame]
                            output[outCount++] = samples[frame + 1]
                            phase -= sourceRate
                        }
                        frame += 2
                    }
                    if (outCount > 0) try {
                        track.write(output, 0, outCount, AudioTrack.WRITE_BLOCKING)
                    } catch (_: Exception) {
                        break
                    }
                }
                else try { Thread.sleep(5L) } catch (_: InterruptedException) { break }
            }
        }.apply { name = "WatchBoy-Audio"; isDaemon = true; start() }
    }

    private fun stopAudio() {
        audioActive = false
        audioThread?.interrupt()
        audioThread = null
        audioTrack?.let { track ->
            try { track.pause(); track.flush(); track.release() } catch (_: Exception) { }
        }
        audioTrack = null
    }

    private fun pulse(mask: Int, frames: Int = 8) {
        pulseButtons = mask
        pulseEndsAt = System.nanoTime() + frames * 16_666_667L
    }

    private fun availableRoms(): List<File> = File(filesDir, "roms").listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in setOf("gba", "agb", "bin") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private inner class WatchBoyView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val circlePath = Path()
        private val frameRect = RectF()
        private var gameMode = false
        private var currentTitle = ""
        private var downX = 0f
        private var downY = 0f
        private var touchHoldMask = 0

        fun showGame(title: String) { gameMode = true; currentTitle = title; invalidate() }
        fun showLibrary() { gameMode = false; currentTitle = ""; invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val side = min(w, h)
            canvas.drawColor(Color.rgb(4, 8, 10))
            circlePath.reset()
            circlePath.addCircle(w * .5f, h * .5f, side * .492f, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(circlePath)
            canvas.drawColor(Color.rgb(13, 20, 23))
            paint.color = Color.rgb(190, 150, 73)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = side * .012f
            canvas.drawCircle(w * .5f, h * .5f, side * .464f, paint)
            paint.style = Paint.Style.FILL
            if (gameMode) drawGame(canvas, w, h, side) else drawLibrary(canvas, w, h, side)
            canvas.restoreToCount(save)
        }

        private fun drawLibrary(canvas: Canvas, w: Float, h: Float, side: Float) {
            centered(canvas, "WATCHBOY", w * .5f, h * .20f, side * .060f, Color.WHITE, true)
            centered(canvas, "ADVANCE", w * .5f, h * .265f, side * .033f, Color.rgb(211, 171, 92), true)
            rounded(canvas, RectF(w * .23f, h * .34f, w * .77f, h * .435f), side * .03f, Color.rgb(38, 86, 81))
            centered(canvas, "+  IMPORT ROM", w * .5f, h * .40f, side * .031f, Color.WHITE, true)
            val roms = availableRoms().take(3)
            if (roms.isEmpty()) {
                centered(canvas, "ADD A GBA FILE", w * .5f, h * .54f, side * .027f, Color.rgb(180, 193, 190), false)
                centered(canvas, "TO START PLAYING", w * .5f, h * .60f, side * .027f, Color.rgb(180, 193, 190), false)
            } else {
                centered(canvas, "RECENT GAMES", w * .5f, h * .49f, side * .025f, Color.rgb(211, 171, 92), true)
                roms.forEachIndexed { index, file ->
                    val y = h * (.555f + index * .095f)
                    rounded(canvas, RectF(w * .16f, y - side * .03f, w * .84f, y + side * .03f), side * .025f, Color.rgb(25, 37, 39))
                    centered(canvas, file.nameWithoutExtension.take(20), w * .5f, y + side * .008f, side * .027f, Color.WHITE, false)
                }
            }
            centered(canvas, "TAP A GAME TO RESUME", w * .5f, h * .86f, side * .022f, Color.rgb(144, 161, 158), false)
        }

        private fun drawGame(canvas: Canvas, w: Float, h: Float, side: Float) {
            centered(canvas, currentTitle.take(22), w * .5f, h * .12f, side * .026f, Color.rgb(232, 219, 185), true)
            val frameW = min(w * .86f, h * .60f * 1.5f)
            val frameH = frameW / 1.5f
            frameRect.set(w * .5f - frameW * .5f, h * .18f, w * .5f + frameW * .5f, h * .18f + frameH)
            rounded(canvas, RectF(frameRect.left - 3, frameRect.top - 3, frameRect.right + 3, frameRect.bottom + 3), side * .018f, Color.rgb(190, 150, 73))
            synchronized(coreLock) {
                nativeCopyFrame(framePixels)
                frameBitmap.setPixels(framePixels, 0, FRAME_WIDTH, 0, 0, FRAME_WIDTH, FRAME_HEIGHT)
            }
            canvas.drawBitmap(frameBitmap, null, frameRect, paint)
            centered(canvas, "SWIPE LEFT / RIGHT", w * .5f, h * .80f, side * .026f, Color.WHITE, true)
            centered(canvas, "UP FLICK / TOP CENTER: A", w * .5f, h * .855f, side * .020f, Color.rgb(208, 178, 115), false)
            centered(canvas, "TOP LEFT: UP   DOWN SWIPE: DOWN", w * .5f, h * .90f, side * .016f, Color.rgb(159, 175, 171), false)
        }

        private fun centered(canvas: Canvas, text: String, x: Float, baseline: Float, size: Float, color: Int, bold: Boolean) {
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(text, x, baseline, paint)
        }

        private fun rounded(canvas: Canvas, rect: RectF, radius: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!gameMode) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val y = event.y / height.toFloat()
                    if (y in .31f.. .45f) openRomPicker()
                    else {
                        val row = ((y - .525f) / .095f).toInt()
                        val file = availableRoms().getOrNull(row)
                        if (file != null) startRom(file)
                    }
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    touchHoldMask = when {
                        event.y > height * .68f && event.x < width * .30f -> JOY_LEFT
                        event.y > height * .68f && event.x > width * .70f -> JOY_RIGHT
                        else -> 0
                    }
                    heldButtons = touchHoldMask
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val tap = abs(dx) < width * .07f && abs(dy) < height * .06f
                    val longPress = event.eventTime - event.downTime >= 450L
                    heldButtons = 0
                    when {
                        dy < -height * .08f -> pulse(JOY_A, 10)
                        dy > height * .10f -> pulse(JOY_DOWN, 10)
                        abs(dx) > width * .10f -> pulse(if (dx < 0) JOY_LEFT else JOY_RIGHT, 10)
                        tap && longPress && downY < height * .44f && downX < width * .5f -> pulse(JOY_L, 8)
                        tap && longPress && downY < height * .44f -> pulse(JOY_R, 8)
                        tap && downY < height * .44f && downX < width * .30f -> pulse(JOY_UP, 8)
                        tap && downY < height * .44f && downX > width * .70f -> pulse(JOY_R, 8)
                        tap && downY < height * .44f -> pulse(JOY_A, 8)
                        tap && downY > height * .68f && downX > width * .63f -> pulse(JOY_A, 8)
                        tap && downY > height * .68f && downX < width * .37f -> pulse(JOY_B, 8)
                        tap && downY > height * .68f && downX in (width * .37f)..(width * .63f) ->
                            pulse(if (longPress) JOY_SELECT else JOY_START, 8)
                    }
                    return true
                }
            }
            return true
        }
    }

    init {
        System.loadLibrary("mgba_libretro")
        System.loadLibrary("watchboy")
    }
}
