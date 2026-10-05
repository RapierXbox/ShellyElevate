package me.rapierxbox.shellyelevatev2.switcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
import me.rapierxbox.shellyelevatev2.Constants.SP_APP_SWITCHER_PREVIEWS
import me.rapierxbox.shellyelevatev2.MainActivity
import me.rapierxbox.shellyelevatev2.helper.ForegroundDetector
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

// small screenshots of apps for the switcher cards. taken while the switcher gesture is still
// in progress so the card can show the app the moment the switcher opens
object SnapshotStore {
    private const val TAG = "SnapshotStore"
    private const val MAX_ENTRIES = 8
    // half the panel matches the card size so it stays sharp without wasting memory
    private const val DOWNSCALE = 2
    // the screen the user swiped on counts as current for this long
    private const val CURRENT_VALID_MS = 15_000L

    // raw screencap pixel formats with four bytes per pixel
    private const val FORMAT_RGBA_8888 = 1
    private const val FORMAT_RGBX_8888 = 2

    private val SCALE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "SnapshotStore") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true)
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    @Volatile
    private var capturing = false

    @Volatile
    private var currentPackage: String? = null

    @Volatile
    private var currentAtMs = 0L

    // the app that was in front when the last snapshot was taken
    fun currentPackage(): String? =
        currentPackage?.takeIf { SystemClock.elapsedRealtime() - currentAtMs < CURRENT_VALID_MS }

    // safe from any thread and ignored while a capture is still running
    // also records which app was in front so the switcher can center it even with previews off
    @JvmStatic
    fun capture(context: Context) {
        val app = context.applicationContext
        if (capturing) return
        capturing = true
        executor.execute {
            try {
                takeSnapshot(app)
            } catch (e: Exception) {
                Log.w(TAG, "snapshot failed: ${e.message}")
            } finally {
                capturing = false
            }
        }
    }

    fun get(packageName: String): Bitmap? = synchronized(cache) { cache[packageName] }

    fun remove(packageName: String) {
        synchronized(cache) { cache.remove(packageName) }
    }

    // listeners run on the main thread with the package that became current or got a new snapshot
    fun addListener(listener: (String) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners -= listener
    }

    private fun enabled(context: Context) = context
        .getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getBoolean(SP_APP_SWITCHER_PREVIEWS, true)

    private fun takeSnapshot(context: Context) {
        val started = SystemClock.elapsedRealtime()
        // asked before the screencap since the switcher is in front by the time it finishes
        val top = ForegroundDetector.current(context) ?: return
        // our package stands for the dashboard host so settings or the switcher must not land there
        val isOtherOwnUi = top.packageName == context.packageName &&
            top.className != null && top.className != MainActivity::class.java.name
        if (isOtherOwnUi) return
        val pkg = top.packageName
        currentPackage = pkg
        currentAtMs = SystemClock.elapsedRealtime()
        mainHandler.post { listeners.forEach { it(pkg) } }
        if (!enabled(context)) return

        // without a file argument screencap streams raw pixels to stdout
        val process = Runtime.getRuntime().exec(arrayOf("screencap"))
        val bitmap = try {
            process.outputStream.close()
            process.inputStream.use { readAndDecode(it) }
        } finally {
            process.destroy()
        }
        if (bitmap == null) return
        synchronized(cache) {
            cache[pkg] = bitmap
            while (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
        }
        Log.d(TAG, "snapshot of $pkg in ${SystemClock.elapsedRealtime() - started}ms")
        mainHandler.post { listeners.forEach { it(pkg) } }
    }

    // header is width height format and on newer releases a dataspace word
    // the pixels are read into one exactly sized buffer and scaled straight into the small 565 card bitmap
    private fun readAndDecode(input: InputStream): Bitmap? {
        val head = ByteArray(12)
        if (!readFully(input, head, 0, head.size)) return null
        val header = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.getInt(0)
        val height = header.getInt(4)
        val format = header.getInt(8)
        if (width <= 0 || height <= 0 || (format != FORMAT_RGBA_8888 && format != FORMAT_RGBX_8888)) {
            Log.w(TAG, "unsupported screencap ${width}x$height format $format")
            return null
        }
        val pixelBytes = width * height * 4
        // room for the optional dataspace word in front of the pixels
        val raw = ByteArray(pixelBytes + 4)
        val read = readAll(input, raw)
        val offset = read - pixelBytes
        if (offset != 0 && offset != 4) {
            Log.w(TAG, "unexpected screencap payload $read for ${width}x$height")
            return null
        }
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // rgba bytes match the memory layout of an argb_8888 bitmap
        full.copyPixelsFromBuffer(ByteBuffer.wrap(raw, offset, pixelBytes))
        // 565 halves the memory and the cards never need alpha
        val small = Bitmap.createBitmap(width / DOWNSCALE, height / DOWNSCALE, Bitmap.Config.RGB_565)
        Canvas(small).drawBitmap(full, null, Rect(0, 0, small.width, small.height), SCALE_PAINT)
        full.recycle()
        return small
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var done = 0
        while (done < length) {
            val n = input.read(buffer, offset + done, length - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    // fills the buffer until the stream ends and returns how much arrived
    private fun readAll(input: InputStream, buffer: ByteArray): Int {
        var done = 0
        while (done < buffer.size) {
            val n = input.read(buffer, done, buffer.size - done)
            if (n < 0) break
            done += n
        }
        return done
    }
}
