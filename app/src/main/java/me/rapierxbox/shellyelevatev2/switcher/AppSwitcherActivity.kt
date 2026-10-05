package me.rapierxbox.shellyelevatev2.switcher

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.SettingsActivity
import me.rapierxbox.shellyelevatev2.display.DisplayController
import me.rapierxbox.shellyelevatev2.helper.PrivilegedShell
import me.rapierxbox.shellyelevatev2.helper.touch.TouchCalibrator

// app switcher as swipeable pages. one page per recent app and the app list as the last page
// on the right. everything moves on springs. the activity is built once and only hidden between
// uses so opening it again just rebinds the pages
class AppSwitcherActivity : ComponentActivity(), SpringPager.Listener {

    private class Card(val packageName: String, val label: String, val icon: Drawable?, val isModule: Boolean)

    private class CardViews(val root: View) {
        val icon: ImageView = root.findViewById(R.id.cardIcon)
        val label: TextView = root.findViewById(R.id.cardLabel)
        val iconLarge: ImageView = root.findViewById(R.id.cardIconLarge)
        val snapshot: ImageView = root.findViewById(R.id.cardSnapshot)
        var packageName: String? = null
    }

    private lateinit var scrim: View
    private lateinit var pager: SpringPager
    private lateinit var dots: LinearLayout
    private lateinit var appsPage: View
    private lateinit var apps: RecyclerView

    private val appAdapter = AppAdapter()
    private val cards = ArrayList<Card>()
    // inflated card pages kept for the next open
    private val cardPool = ArrayList<CardViews>()
    private val shownCards = ArrayList<CardViews>()
    private var closing = false
    private var hidden = false
    private var sized = false
    private var shownAtMs = 0L

    private val density by lazy { resources.displayMetrics.density }
    private val cardRadius by lazy { 20f * density }
    private val ownIcon by lazy { applicationInfo.loadIcon(packageManager) }
    private val accelerate = AccelerateInterpolator()

    private val roundedOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, cardRadius)
        }
    }

    private val snapshotListener: (String) -> Unit = { pkg -> onSnapshotOrCurrent(pkg) }
    private val catalogListener: () -> Unit = {
        appAdapter.submit(AppCatalog.apps())
        rebuildPages(keepPage = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_switcher)
        applyImmersive()

        scrim = findViewById(R.id.switcherScrim)
        pager = findViewById(R.id.switcherPager)
        dots = findViewById(R.id.switcherDots)
        pager.listener = this

        appsPage = LayoutInflater.from(this).inflate(R.layout.item_switcher_apps_page, pager, false)
        apps = appsPage.findViewById(R.id.switcherApps)
        apps.outlineProvider = roundedOutline
        apps.clipToOutline = true
        apps.setHasFixedSize(true)
        apps.itemAnimator = null
        apps.layoutManager = GridLayoutManager(this, 4)
        apps.adapter = appAdapter
        appAdapter.submit(AppCatalog.apps())
        if (AppCatalog.apps().isEmpty()) AppCatalog.refresh()
        appsPage.findViewById<View>(R.id.switcherSettings).setOnClickListener {
            if (closing) return@setOnClickListener
            startActivity(Intent(this, SettingsActivity::class.java))
            hideNow()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        sizePages()
        prepareShow()
    }

    // a later open brings the hidden task back here instead of creating the activity again
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        prepareShow()
    }

    override fun onStart() {
        super.onStart()
        hidden = false
        AppSwitcher.isOpen = true
        SnapshotStore.addListener(snapshotListener)
        AppCatalog.addListener(catalogListener)
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
    }

    override fun onStop() {
        AppSwitcher.isOpen = false
        SnapshotStore.removeListener(snapshotListener)
        AppCatalog.removeListener(catalogListener)
        // anything covering the switcher ends this use so it never pops back up later on its own
        if (!hidden) hideNow()
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        TouchCalibrator.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // pages follow the screen aspect so a snapshot fills its card. the panel never rotates so this runs once
    private fun sizePages() {
        if (sized) return
        sized = true
        val metrics = resources.displayMetrics
        val header = (40 * density).toInt()
        var width = (metrics.widthPixels * 0.70f).toInt()
        var cardHeight = width * metrics.heightPixels / metrics.widthPixels
        val maxCardHeight = (metrics.heightPixels * 0.84f).toInt() - header
        if (cardHeight > maxCardHeight) {
            cardHeight = maxCardHeight
            width = cardHeight * metrics.widthPixels / metrics.heightPixels
        }
        pager.setPageSize(width, cardHeight + header)
        val columns = ((width - 16 * density) / (84 * density)).toInt().coerceAtLeast(2)
        (apps.layoutManager as GridLayoutManager).spanCount = columns
    }

    // fresh pages and the entry spring for every open
    private fun prepareShow() {
        closing = false
        shownAtMs = SystemClock.uptimeMillis()
        rebuildPages(keepPage = false)
        pager.jumpTo(0)
        updateDots()
        scrim.animate().cancel()
        pager.animate().cancel()
        scrim.alpha = 0f
        pager.alpha = 0f
        pager.scaleX = ENTRY_SCALE
        pager.scaleY = ENTRY_SCALE
        // the first frame after layout is skipped so the first thing drawn is the animation
        pager.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                pager.viewTreeObserver.removeOnPreDrawListener(this)
                animateIn()
                return false
            }
        })
    }

    // pages

    private fun rebuildPages(keepPage: Boolean) {
        val page = pager.currentPage
        cards.clear()
        cards.addAll(buildCards())
        for (views in shownCards) {
            pager.removeView(views.root)
            cardPool += views
        }
        shownCards.clear()
        if (appsPage.parent == null) pager.addPage(appsPage)
        cards.forEachIndexed { index, card ->
            val views = if (cardPool.isNotEmpty()) cardPool.removeAt(cardPool.size - 1) else createCardViews()
            bindCard(views, card)
            pager.addPage(views.root, index)
            shownCards += views
        }
        if (keepPage) pager.jumpTo(page.coerceAtMost(cards.size))
        updateDots()
    }

    private fun createCardViews(): CardViews {
        val views = CardViews(LayoutInflater.from(this).inflate(R.layout.item_switcher_card, pager, false))
        val frame = views.root.findViewById<View>(R.id.cardFrame)
        frame.outlineProvider = roundedOutline
        frame.clipToOutline = true
        return views
    }

    private fun bindCard(views: CardViews, card: Card) {
        views.packageName = card.packageName
        views.icon.setImageDrawable(card.icon)
        views.iconLarge.setImageDrawable(card.icon)
        views.label.text = card.label
        bindSnapshot(views, fade = false)
    }

    private fun bindSnapshot(views: CardViews, fade: Boolean) {
        val bitmap = views.packageName?.let { SnapshotStore.get(it) }
        views.snapshot.setImageBitmap(bitmap)
        views.iconLarge.isVisible = bitmap == null
        views.snapshot.animate().cancel()
        if (bitmap != null && fade) {
            views.snapshot.alpha = 0f
            views.snapshot.animate().alpha(1f).setDuration(160).start()
        } else {
            views.snapshot.alpha = 1f
        }
    }

    private fun buildCards(): List<Card> {
        val modulePkg = AppSwitcher.modulePackage(this)
        val module = DisplayController.activeModule(this)
        val order = LinkedHashSet<String>()
        SnapshotStore.currentPackage()?.let { order += it }
        modulePkg?.let { order += it }
        order += RecentApps.list(this)
        return order.mapNotNull { pkg ->
            when {
                pkg == modulePkg && pkg == packageName -> Card(pkg, getString(module.titleRes), ownIcon, isModule = true)
                pkg == packageName -> null
                else -> AppCatalog.find(pkg)?.let {
                    Card(pkg, it.label, it.icon?.let { bmp -> BitmapDrawable(resources, bmp) }, isModule = pkg == modulePkg)
                }
            }
        }
    }

    // the app the user swiped on may only be known a moment after the switcher opened
    // it moves to the front while the entry spring still hides the change and later only its picture updates
    private fun onSnapshotOrCurrent(pkg: String) {
        val reorder = SystemClock.uptimeMillis() - shownAtMs < REORDER_WINDOW_MS &&
            pkg == SnapshotStore.currentPackage() && cards.firstOrNull()?.packageName != pkg
        if (reorder) {
            rebuildPages(keepPage = false)
            pager.jumpTo(0)
        } else {
            shownCards.firstOrNull { it.packageName == pkg }?.let { bindSnapshot(it, fade = true) }
        }
    }

    private fun updateDots() {
        val count = cards.size + 1
        while (dots.childCount < count) {
            val size = (7 * density).toInt()
            dots.addView(View(this).apply {
                setBackgroundResource(R.drawable.switcher_dot)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = size / 2
                    marginEnd = size / 2
                }
            })
        }
        val current = pager.currentPage
        for (i in 0 until dots.childCount) {
            val dot = dots.getChildAt(i)
            dot.isVisible = i < count
            val active = i == current
            dot.animate().alpha(if (active) 1f else 0.35f).scaleX(if (active) 1.3f else 1f)
                .scaleY(if (active) 1.3f else 1f).setDuration(150).start()
        }
    }

    // pager callbacks

    override fun onPageTap(index: Int) {
        val card = cards.getOrNull(index) ?: return
        open(card.packageName)
    }

    override fun canDismiss(index: Int): Boolean = cards.getOrNull(index)?.isModule == false

    override fun onPageDismissed(index: Int) {
        val card = cards.removeAt(index)
        shownCards.removeAt(index).let { cardPool += it }
        closeApp(card.packageName)
        updateDots()
    }

    override fun onOutsideTap() = close()

    override fun onPageSettled(index: Int) = updateDots()

    // app grid

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<AppCatalog.AppEntry> = emptyList()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appTileIcon)
            val label: TextView = view.findViewById(R.id.appTileLabel)

            init {
                view.setOnClickListener {
                    items.getOrNull(bindingAdapterPosition)?.let { open(it.packageName) }
                }
            }
        }

        // the catalog hands out a new list on every reload so the same list means nothing changed
        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<AppCatalog.AppEntry>) {
            if (list === items) return
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app_tile, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.icon.setImageBitmap(app.icon)
            holder.label.text = app.label
        }
    }

    // actions

    private fun open(packageName: String) {
        if (closing) return
        if (AppSwitcher.launch(this, packageName)) hideNow() else close()
    }

    private fun closeApp(packageName: String) {
        RecentApps.remove(this, packageName)
        SnapshotStore.remove(packageName)
        Thread({ PrivilegedShell.runShell("am force-stop $packageName") }, "SwitcherForceStop").start()
    }

    // animations

    // the pages spring in from slightly larger which reads as the current app shrinking into its card
    private fun animateIn() {
        scrim.animate().alpha(1f).setDuration(160).setStartDelay(0).start()
        pager.animate().alpha(1f).setDuration(120).setStartDelay(0).withLayer().start()
        for (property in arrayOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y)) {
            SpringAnimation(pager, property, 1f).apply {
                spring.setStiffness(ENTRY_STIFFNESS).dampingRatio = ENTRY_DAMPING
                start()
            }
        }
    }

    private fun close() {
        if (closing) return
        closing = true
        scrim.animate().alpha(0f).setDuration(140).setStartDelay(0).setInterpolator(accelerate).start()
        pager.animate().alpha(0f).scaleX(EXIT_SCALE).scaleY(EXIT_SCALE).setDuration(140).setStartDelay(0)
            .setInterpolator(accelerate).withLayer().withEndAction { hideNow() }.start()
    }

    // sends the switcher task behind everything so the next open skips building it all again
    // the app being opened animates in by itself so the switcher just disappears under it
    private fun hideNow() {
        closing = true
        if (hidden) return
        hidden = true
        if (!moveTaskToBack(true)) finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REORDER_WINDOW_MS = 400L
        const val ENTRY_SCALE = 1.12f
        const val EXIT_SCALE = 0.92f
        // opens quickly with a slight settle like the ios switcher
        val ENTRY_STIFFNESS = FluidMotion.stiffness(0.36f)
        const val ENTRY_DAMPING = 0.82f
    }
}
