package momoi.mod.qqpro.hook.sticker

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.hook.qzone.HChipScroll
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.bitmapDecodeFile
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.util.Utils

/**
 * Material 3 store-sticker (商城表情) picker. Top: a horizontal scroll of pack chips (the user's owned
 * packs, synced from the phone — see [StickerStore]). Below: a 2-wide grid of that pack's stickers.
 * Tapping a sticker sends it to the current chat and dismisses. All data via the watch-proven kernel
 * emoticon APIs; no store/mall browse (no API for that on this build).
 */
class StickerPickerFragment : MyDialogFragment() {

    private var chipRow: LinearLayout? = null
    private lateinit var grid: RecyclerView
    private var emptyLabel: TextView? = null

    private var packs: List<StickerStore.Pack> = emptyList()
    private var selectedEpId: Int = -1
    private val stickers = ArrayList<StickerStore.Sticker>()
    private var cell = 100.dp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val edge = if (Utils.isRoundScreen) 16.dp else 8.dp
        cell = ((ctx.resources.displayMetrics.widthPixels - 2 * edge - 8.dp) / 2).coerceAtLeast(72.dp)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(edge, 8.dp, edge, 6.dp)
        }

        root.addView(TextView(ctx).apply {
            text = "表情"
            setTextColor(M3.onSurface)
            textSize = 15f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Pack chips (horizontal scroll). HChipScroll implements HorizontalDragWidget so the
        // SwipeBackLayout does NOT grab horizontal drags here — the chips scroll, and swipe-back
        // still works on the rest of the fragment (title / grid).
        val scroll = HChipScroll(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
        }
        chipRow = row
        scroll.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Sticker grid (2-wide).
        grid = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 2)
            clipToPadding = false
            setPadding(0, 4.dp, 0, 0)
            adapter = StickerAdapter()
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        emptyLabel = TextView(ctx).apply {
            text = "加载中…"
            setTextColor(M3.onSurfaceVariant)
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        loadPacks()
        return swipeBackWrap(root)
    }

    private fun loadPacks() {
        StickerStore.loadPacks { list ->
            if (!isAdded) return@loadPacks
            packs = list
            buildChips()
            if (list.isNotEmpty()) selectPack(list.first().epId)
            else { emptyLabel?.text = "没有可用的表情包（在手机上添加后会同步到手表）"; emptyLabel?.visibility = View.VISIBLE }
        }
    }

    private fun buildChips() {
        val ctx = context ?: return
        val row = chipRow ?: return
        row.removeAllViews()
        packs.forEach { pack ->
            val chip = TextView(ctx).apply {
                text = pack.name
                textSize = 11f
                isSingleLine = true
                gravity = Gravity.CENTER
                compoundDrawablePadding = 5.dp
                setPadding(12.dp, 6.dp, 12.dp, 6.dp)
                setOnClickListener { selectPack(pack.epId) }
            }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 8.dp
            })
            // A representative sticker as the chip's leading icon (the pack has no cover image).
            StickerStore.packIcon(pack.epId) { file ->
                if (!isAdded || file == null || !file.exists()) return@packIcon
                runCatching {
                    val bmp = android.graphics.BitmapFactory.decodeFile(file.path) ?: return@packIcon
                    val d = android.graphics.drawable.BitmapDrawable(resources, bmp).apply { setBounds(0, 0, 18.dp, 18.dp) }
                    chip.setCompoundDrawables(d, null, null, null)
                }.onFailure { Utils.log("chip icon decode err: $it") }
            }
        }
        styleChips()
    }

    /** Selected chip = filled accent; others = tonal. M3 colour tokens, recomputed on every selection. */
    private fun styleChips() {
        val row = chipRow ?: return
        packs.forEachIndexed { i, pack ->
            val chip = row.getChildAt(i) as? TextView ?: return@forEachIndexed
            val selected = pack.epId == selectedEpId
            chip.background = GradientDrawable().apply {
                cornerRadius = 9999f
                setColor(if (selected) M3.primary else M3.surfaceContainer)
            }
            chip.setTextColor(if (selected) M3.onPrimary else M3.onSurfaceVariant)
        }
    }

    private fun selectPack(epId: Int) {
        if (epId == selectedEpId && stickers.isNotEmpty()) return
        selectedEpId = epId
        styleChips()
        stickers.clear()
        grid.adapter?.notifyDataSetChanged()
        emptyLabel?.text = "加载中…"
        emptyLabel?.visibility = View.VISIBLE
        StickerStore.loadStickers(epId) { list ->
            if (!isAdded || epId != selectedEpId) return@loadStickers
            stickers.clear()
            stickers.addAll(list)
            grid.adapter?.notifyDataSetChanged()
            emptyLabel?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            if (list.isEmpty()) emptyLabel?.text = "这个表情包暂时没有内容"
        }
    }

    private fun onStickerTap(s: StickerStore.Sticker) {
        val ctx = context ?: return
        Utils.toast(ctx, "发送中…")
        StickerStore.send(s) { ok ->
            if (ok) dismiss() else context?.let { Utils.toast(it, "发送失败") }
        }
    }

    private inner class StickerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            // A square card cell: rounded surface container holding the centred sticker image.
            val card = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { cornerRadius = M3.radiusLg.toFloat(); setColor(M3.surfaceContainer) }
                layoutParams = RecyclerView.LayoutParams(cell, cell).apply { /* margins set below */ }
            }
            val pad = 8.dp
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(cell - pad * 2, cell - pad * 2, Gravity.CENTER)
            }
            val spin = M3CircularProgress(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(24.dp, 24.dp, Gravity.CENTER)
            }
            card.addView(img); card.addView(spin)
            card.setTag(java.lang.Integer.valueOf(0)) // marker
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun getItemCount() = stickers.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val s = stickers[position]
            val card = holder.itemView as FrameLayout
            val img = card.getChildAt(0) as ImageView
            val spin = card.getChildAt(1)
            // Even outer spacing via item margins.
            (card.layoutParams as? RecyclerView.LayoutParams)?.let {
                val m = 4.dp
                it.setMargins(m, m, m, m)
                it.width = cell; it.height = cell
            }
            img.setImageDrawable(null)
            spin.visibility = View.VISIBLE
            card.setOnClickListener { onStickerTap(s) }
            // Tag the view with the sticker so a recycled async result for an old sticker is ignored.
            card.tag = s.eId
            StickerStore.thumbFile(s) { file ->
                if (card.tag != s.eId) return@thumbFile // recycled
                spin.visibility = View.GONE
                if (file != null && file.exists()) runCatching { img.bitmapDecodeFile(file) }
                    .onFailure { Utils.log("sticker thumb decode err: $it") }
            }
        }
    }
}
