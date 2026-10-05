package me.rapierxbox.shellyelevatev2.switcher

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import me.rapierxbox.shellyelevatev2.Constants.SHARED_PREFERENCES_NAME
import me.rapierxbox.shellyelevatev2.Constants.SP_APP_SWITCHER_PREVIEWS
import me.rapierxbox.shellyelevatev2.MainActivity
import me.rapierxbox.shellyelevatev2.helper.ForegroundDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

// small screenshots of apps for the switcher cards. taken while the switcher gesture is still
// in progress so the card can show the app the moment the switcher opens
object SnapshotStore {
    private const val TAG = "SnapshotStore"
    private const val MAX_ENTRIES = 8
    // a third of the panel is plenty for a card and keeps memory and decode time low
    private const val DOWNSCALE = 3
    // the screen the user swiped on counts as current for this long
    private const val CURRENT_VALID_MS = 15_000L

    // raw screencap pixel formats with four bytes per pixel
    private const val FORMAT_RGBA_8888 = 1
    private const val FORMAT_RGBX_8888 = 2

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
    @JvmStatic
    fun capture(context: Context) {
        val app = context.applicationContext
        if (!enabled(app) || capturing) return
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

    // listeners run on the main thread with the package that got a new snapshot
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
        // without a file argument screencap streams raw pixels to stdout
        val process = Runtime.getRuntime().exec(arrayOf("screencap"))
        val raw = try {
            process.outputStream.close()
            process.inputStream.use { it.readBytes() }
        } finally {
            process.destroy()
        }
        val bitmap = decode(raw) ?: return
        val top = ForegroundDetector.current(context)
        // our package stands for the dashboard host so settings or the switcher must not land there
        val isOtherOwnUi = top?.packageName == context.packageName &&
            top.className != null && top.className != MainActivity::class.java.name
        val pkg = top?.packageName
        if (pkg == null || isOtherOwnUi) {
            bitmap.recycle()
            return
        }
        currentPackage = pkg
        currentAtMs = SystemClock.elapsedRealtime()
        synchronized(cache) {
            cache[pkg] = bitmap
            while (cache.size > MAX_ENTRIES) cache.remove(cache.keys.first())
        }
        Log.d(TAG, "snapshot of $pkg in ${SystemClock.elapsedRealtime() - started}ms")
        mainHandler.post { listeners.forEach { it(pkg) } }
    }

    // header is width height format and on newer releases a dataspace word
    private fun decode(raw: ByteArray): Bitmap? {
        if (raw.size < 16) return null
        val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.getInt(0)
        val height = header.getInt(4)
        val format = header.getInt(8)
        if (width <= 0 || height <= 0 || (format != FORMAT_RGBA_8888 && format != FORMAT_RGBX_8888)) {
            Log.w(TAG, "unsupported screencap ${width}x$height format $format")
            return null
        }
        val pixelBytes = width * height * 4
        val offset = raw.size - pixelBytes
        if (offset !in 12..16) {
            Log.w(TAG, "unexpected screencap size ${raw.size} for ${width}x$height")
            return null
        }
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // rgba bytes match the memory layout of an argb_8888 bitmap
        full.copyPixelsFromBuffer(ByteBuffer.wrap(raw, offset, pixelBytes))
        val scaled = Bitmap.createScaledBitmap(full, width / DOWNSCALE, height / DOWNSCALE, true)
        if (scaled !== full) full.recycle()
        // 565 halves the memory and the cards never need alpha
        val small = scaled.copy(Bitmap.Config.RGB_565, false)
        scaled.recycle()
        return small
    }
}
