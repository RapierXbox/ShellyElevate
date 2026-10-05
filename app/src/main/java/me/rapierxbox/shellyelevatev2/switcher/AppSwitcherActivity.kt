package me.rapierxbox.shellyelevatev2.switcher

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Outline
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import me.rapierxbox.shellyelevatev2.R
import me.rapierxbox.shellyelevatev2.SettingsActivity
import me.rapierxbox.shellyelevatev2.display.DisplayController
import me.rapierxbox.shellyelevatev2.helper.PrivilegedShell
import kotlin.math.abs
import kotlin.math.min

// ios style switcher. recent apps as large cards on the left and every app on the right
// everything it shows is prepared in the background so opening it only binds views
class AppSwitcherActivity : ComponentActivity() {

    private class Card(val packageName: String, val label: String, val icon: Drawable?, val isModule: Boolean)

    private lateinit var scrim: View
    private lateinit var content: LinearLayout
    private lateinit var cardsArea: View
    private lateinit var cards: RecyclerView
    private lateinit var drawer: View
    private lateinit var apps: RecyclerView
    private lateinit var empty: TextView

    private val cardAdapter = CardAdapter()
    private val appAdapter = AppAdapter()
    private var cardWidth = 0
    private var cardHeight = 0
    private var closing = false

    private val snapshotListener: (String) -> Unit = { pkg -> cardAdapter.onSnapshot(pkg) }
    private val catalogListener: () -> Unit = {
        appAdapter.submit(AppCatalog.apps())
        cardAdapter.submit(buildCards())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_switcher)
        applyImmersive()

        scrim = findViewById(R.id.switcherScrim)
        content = findViewById(R.id.switcherContent)
        cardsArea = findViewById(R.id.switcherCardsArea)
        cards = findViewById(R.id.switcherCards)
        drawer = findViewById(R.id.switcherDrawer)
        apps = findViewById(R.id.switcherApps)
        empty = findViewById(R.id.switcherEmpty)

        if (resources.displayMetrics.heightPixels > resources.displayMetrics.widthPixels) stackVertically()

        scrim.setOnClickListener { close() }
        findViewById<View>(R.id.switcherSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finishNow()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        setupApps()
        setupCards()

        // hidden until the first layout so nothing flashes at its final spot before animating in
        content.alpha = 0f
        content.post {
            sizeCards()
            content.alpha = 1f
            animateIn()
        }
    }

    override fun onStart() {
        super.onStart()
        AppSwitcher.isOpen = true
        SnapshotStore.addListener(snapshotListener)
        AppCatalog.addListener(catalogListener)
    }

    override fun onStop() {
        AppSwitcher.isOpen = false
        SnapshotStore.removeListener(snapshotListener)
        AppCatalog.removeListener(catalogListener)
        // anything covering the switcher ends it so it never waits in the background
        if (!isFinishing) finish()
        super.onStop()
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

    // tall screens put the app grid under the cards
    private fun stackVertically() {
        content.orientation = LinearLayout.VERTICAL
        cardsArea.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.55f)
        drawer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f).apply {
            topMargin = dp(16)
        }
    }

    // app grid

    private fun setupApps() {
        apps.setHasFixedSize(true)
        apps.itemAnimator = null
        apps.layoutManager = GridLayoutManager(this, 3)
        apps.adapter = appAdapter
        appAdapter.submit(AppCatalog.apps())
        if (AppCatalog.apps().isEmpty()) AppCatalog.refresh()
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<AppCatalog.AppEntry> = emptyList()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appTileIcon)
            val label: TextView = view.findViewById(R.id.appTileLabel)
        }

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<AppCatalog.AppEntry>) {
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
            holder.itemView.setOnClickListener { open(app.packageName) }
        }
    }

    // cards

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCards() {
        cards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        LinearSnapHelper().attachToRecyclerView(cards)
        cards.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.left = dp(10)
                outRect.right = dp(10)
            }
        })
        cards.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = applyStackEffect()
        })
        cards.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyStackEffect() }

        // a tap on empty space between cards closes like a tap on the scrim
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        cards.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var downX = 0f
            private var downY = 0f
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                    }
                    MotionEvent.ACTION_UP -> {
                        val tap = abs(e.x - downX) < slop && abs(e.y - downY) < slop
                        if (tap && rv.findChildViewUnder(e.x, e.y) == null) close()
                    }
                }
                return false
            }
        })

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.UP) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                // the display module card cannot be closed
                val card = cardAdapter.cardAt(viewHolder.bindingAdapterPosition) ?: return 0
                return if (card.isModule) 0 else ItemTouchHelper.UP
            }

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val card = cardAdapter.removeAt(viewHolder.bindingAdapterPosition) ?: return
                closeApp(card.packageName)
                updateEmptyHint()
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.3f
        }).attachToRecyclerView(cards)

        cards.adapter = cardAdapter
        cardAdapter.submit(buildCards())
        updateEmptyHint()
    }

    // card size follows the screen aspect so a snapshot fills it without bars
    private fun sizeCards() {
        val metrics = resources.displayMetrics
        val aspect = metrics.widthPixels.toFloat() / metrics.heightPixels
        val maxHeight = (cards.height * 0.86f).toInt() - dp(40)
        var height = maxHeight
        var width = (height * aspect).toInt()
        val maxWidth = (cards.width * 0.72f).toInt()
        if (width > maxWidth) {
            width = maxWidth
            height = (width / aspect).toInt()
        }
        cardWidth = width.coerceAtLeast(dp(80))
        cardHeight = height.coerceAtLeast(dp(80))
        // padding lets the first and last card snap to the middle and centers the row vertically
        val side = ((cards.width - cardWidth) / 2 - dp(10)).coerceAtLeast(0)
        val top = ((cards.height - cardHeight - dp(40)) / 2).coerceAtLeast(0)
        cards.setPadding(side, top, side, 0)
        cardAdapter.notifyItemRangeChanged(0, cardAdapter.itemCount)

        val tile = dp(88)
        val columns = ((apps.width - apps.paddingLeft - apps.paddingRight) / tile).coerceAtLeast(2)
        (apps.layoutManager as GridLayoutManager).spanCount = columns
    }

    // neighbours shrink and fade the further they sit from the middle. transforms only so no relayout
    private fun applyStackEffect() {
        if (cardWidth == 0) return
        val center = cards.width / 2f
        val step = cardWidth + dp(20).toFloat()
        for (i in 0 until cards.childCount) {
            val child = cards.getChildAt(i)
            val childCenter = (child.left + child.right) / 2f
            val distance = min(abs(childCenter - center) / step, 1f)
            val scale = 1f - 0.12f * distance
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = 1f - 0.35f * distance
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
                pkg == modulePkg && pkg == packageName ->
                    Card(pkg, getString(module.titleRes), applicationInfo.loadIcon(packageManager), isModule = true)
                pkg == packageName -> null
                else -> AppCatalog.find(pkg)?.let {
                    Card(pkg, it.label, it.icon?.let { bmp -> BitmapDrawable(resources, bmp) }, isModule = pkg == modulePkg)
                }
            }
        }
    }

    private fun updateEmptyHint() {
        empty.isVisible = cardAdapter.itemCount <= 1
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardAdapter.Holder>() {
        private val items = ArrayList<Card>()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.cardIcon)
            val label: TextView = view.findViewById(R.id.cardLabel)
            val frame: View = view.findViewById(R.id.cardFrame)
            val iconLarge: ImageView = view.findViewById(R.id.cardIconLarge)
            val snapshot: ImageView = view.findViewById(R.id.cardSnapshot)

            init {
                frame.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                    }
                }
                frame.clipToOutline = true
            }
        }

        fun cardAt(position: Int): Card? = items.getOrNull(position)

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Card>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        fun removeAt(position: Int): Card? {
            if (position !in items.indices) return null
            val card = items.removeAt(position)
            notifyItemRemoved(position)
            return card
        }

        fun onSnapshot(pkg: String) {
            val index = items.indexOfFirst { it.packageName == pkg }
            if (index >= 0) notifyItemChanged(index, PAYLOAD_SNAPSHOT)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_switcher_card, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_SNAPSHOT)) {
                bindSnapshot(holder, items[position], fade = true)
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val card = items[position]
            if (cardWidth > 0) {
                holder.frame.layoutParams = holder.frame.layoutParams.apply {
                    width = cardWidth
                    height = cardHeight
                }
                holder.itemView.layoutParams = holder.itemView.layoutParams.apply { width = cardWidth }
            }
            holder.icon.setImageDrawable(card.icon)
            holder.iconLarge.setImageDrawable(card.icon)
            holder.label.text = card.label
            bindSnapshot(holder, card, fade = false)
            holder.itemView.setOnClickListener { open(card.packageName) }
        }

        private fun bindSnapshot(holder: Holder, card: Card, fade: Boolean) {
            val bitmap = SnapshotStore.get(card.packageName)
            holder.snapshot.setImageBitmap(bitmap)
            holder.iconLarge.isVisible = bitmap == null
            holder.snapshot.animate().cancel()
            if (bitmap != null && fade) {
                holder.snapshot.alpha = 0f
                holder.snapshot.animate().alpha(1f).setDuration(160).start()
            } else {
                holder.snapshot.alpha = 1f
            }
        }
    }

    // actions

    private fun open(packageName: String) {
        if (closing) return
        if (AppSwitcher.launch(this, packageName)) finishNow() else close()
    }

    private fun closeApp(packageName: String) {
        RecentApps.remove(this, packageName)
        SnapshotStore.remove(packageName)
        Thread({ PrivilegedShell.runShell("am force-stop $packageName") }, "SwitcherForceStop").start()
    }

    // animations

    private fun animateIn() {
        val decelerate = DecelerateInterpolator(2f)
        scrim.animate().alpha(1f).setDuration(180).start()
        cardsArea.translationY = dp(48).toFloat()
        cardsArea.alpha = 0f
        cardsArea.animate().translationY(0f).alpha(1f).setDuration(240)
            .setInterpolator(decelerate).withLayer().start()
        drawer.translationX = dp(48).toFloat()
        drawer.alpha = 0f
        drawer.animate().translationX(0f).alpha(1f).setDuration(240).setStartDelay(40)
            .setInterpolator(decelerate).withLayer().start()
    }

    private fun close() {
        if (closing) return
        closing = true
        val accelerate = AccelerateInterpolator()
        scrim.animate().alpha(0f).setDuration(150).start()
        drawer.animate().translationX(dp(32).toFloat()).alpha(0f).setDuration(150)
            .setInterpolator(accelerate).withLayer().start()
        cardsArea.animate().translationY(dp(32).toFloat()).alpha(0f).setDuration(150)
            .setInterpolator(accelerate).withLayer().withEndAction { finishNow() }.start()
    }

    // the app being opened animates in by itself so the switcher just disappears under it
    private fun finishNow() {
        closing = true
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PAYLOAD_SNAPSHOT = "snapshot"
    }
}
