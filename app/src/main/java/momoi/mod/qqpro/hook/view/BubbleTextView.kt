package momoi.mod.qqpro.hook.view

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.AIOLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol

/**
 * The native chat unread bubble ([com.tencent.watch.aio_impl.reserve1.unreadbubble.UnreadBubbleVB])
 * drives this view through [setText] / [setBackgroundResource] / [setVisibility]. We restyle it into
 * a large pill at the bottom-right (left side rounded, right flush to the screen edge) and take over
 * its visibility with our own state machine.
 *
 * Visibility is decided by [shouldShow], in priority order:
 *  1. There are new unread messages (native gave a count) → show COLORED (blue "↓ N").
 *  2. Else the [anchors] return-list is non-empty (we jumped up and haven't come back) → show DARK ("↓").
 *  3. Else the last scroll was DOWNWARD and the latest message is still more than
 *     [SCROLL_DIST_THRESHOLD] items below the viewport → show DARK.
 *  4. Otherwise → hidden.
 *
 * Two-stage return via [anchors]: each programmatic upward jump (tap a reply source, jump-to-first-
 * unread) pushes the message we left from onto [anchors]. The button (rule 2) stays up until that
 * anchor is reached. An anchor is REMOVED the moment it becomes visible again — whether the user
 * tapped the button to scroll back, scrolled down manually, or the jump was so short the anchor was
 * never off-screen (e.g. tapping a reply whose source sits just above it). Tapping the button scrolls
 * to the most recent anchor; once no anchors remain, a tap does the real go-to-bottom.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class BubbleTextView(context: Context) : TextView(context) {
    // Left corners fully rounded (semicircle), right corners square so it sits flush to the screen edge.
    // Backgrounds captured at construction; the arrow/count tint auto-contrasts against whichever bg is
    // shown (M3.onColor), so the arrow stays visible in both light and dark themes. The neutral (non-
    // unread) bg is a translucent surface-container tone rather than a fixed dark grey, so it isn't a
    // black blob in light mode.
    private val blueBgColor = M3.primary
    private val greyBgColor = (M3.surfaceContainerHigh and 0x00FFFFFF) or 0xCC_000000.toInt()
    private val blueBg = roundCornerDrawable(blueBgColor, 9999f, 0f, 9999f, 0f)
    private val greyBg = roundCornerDrawable(greyBgColor, 9999f, 0f, 9999f, 0f)
    private val coloredTint = M3.onColor(blueBgColor)
    private val darkTint = M3.onColor(greyBgColor)

    // Native intent, captured from its setText / setVisibility calls. hasUnread() = both together.
    private var nativeWantsShow = false
    private var isCountMode = false
    private var countText = ""

    // +1 last scroll was downward (toward newest), -1 upward, 0 none. Drives rule 3.
    private var lastScrollDir = 0

    private var listenersAttached = false
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var scrollListener: RecyclerView.OnScrollListener? = null
    private var resolvedRv: RecyclerView? = null
    private var lastMode = Mode.HIDDEN

    private enum class Mode { HIDDEN, COLORED, DARK }

    // Native installs its own go-to-bottom click; captured here and wrapped for the staged return.
    private var delegateClick: OnClickListener? = null

    init {
        gravity = Gravity.CENTER
        setTextColor(M3.onSurface)
        textSize = 14f
        setPadding(18.dp, 9.dp, 14.dp, 9.dp)
    }

    // ---- native driving us ----

    // Native K() sets aio_unread_bg (the blue count circle); we own the background, so just re-derive.
    override fun setBackgroundResource(resid: Int) { refresh() }

    override fun setText(text: CharSequence?, type: BufferType?) {
        countText = text?.toString().orEmpty()
        isCountMode = countText.isNotEmpty()
        refresh()
    }

    // Native uses VISIBLE(0) for the count, INVISIBLE(4) for back-to-bottom, GONE(8) to hide.
    // Treat anything but GONE as "native wants the count shown".
    override fun setVisibility(visibility: Int) {
        nativeWantsShow = visibility != View.GONE
        refresh()
    }

    // Wrap whatever click listener native installs so we can intercept it for the staged return.
    override fun setOnClickListener(l: OnClickListener?) {
        if (l == null) {
            delegateClick = null
            super.setOnClickListener(null)
            return
        }
        delegateClick = l
        super.setOnClickListener { v -> onBubbleClick(v) }
    }

    // ---- list helpers ----

    // The chat message RecyclerView, resolved from THIS view's own hierarchy (the chat the bubble
    // overlays) rather than the global CurrentMsgList.vb.H — which is a single static that can point
    // at a preloaded/previous chat's list after a re-attach, so a scroll listener bound to it never
    // fires for the visible chat (the dir-stuck-at-0 bug). Cached at attach via [resolvedRv].
    private fun rv(): RecyclerView? =
        resolvedRv ?: findChatRecyclerView() ?: runCatching { CurrentMsgList.vb.H }.getOrNull()

    private fun lm(): AIOLayoutManager? = rv()?.layoutManager as? AIOLayoutManager

    /** Walk to the window root, then find the message list (its layout manager is an AIOLayoutManager). */
    private fun findChatRecyclerView(): RecyclerView? {
        var root: View = this
        while (root.parent is View) root = root.parent as View
        return findAioRecyclerView(root)
    }

    private fun findAioRecyclerView(v: View): RecyclerView? {
        if (v is RecyclerView && v.layoutManager is AIOLayoutManager) return v
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) findAioRecyclerView(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** The live adapter item list (authoritative); falls back to the accumulated mirror. */
    private fun liveList(): List<*> =
        runCatching { CurrentMsgList.uiOp?.m() }.getOrNull() ?: CurrentMsgList.msgList.value

    private fun msgCount(): Int = runCatching { liveList().size }.getOrDefault(0)

    private fun msgIdAt(pos: Int): Long? =
        (liveList().getOrNull(pos) as? WatchAIOMsgItem)?.d?.msgId

    private fun liveIndexOfMsgId(id: Long): Int =
        runCatching { liveList().indexOfFirst { (it as? WatchAIOMsgItem)?.d?.msgId == id } }.getOrDefault(-1)

    /** Items between the last visible row and the latest message (0 == latest is on screen). */
    private fun distanceToLast(): Int {
        val lm = lm() ?: return 0
        val count = msgCount()
        if (count <= 0) return 0
        return (count - 1) - lm.findLastVisibleItemPosition()
    }

    /** True when the message with [id] is within the currently visible row range. */
    private fun isMsgIdVisible(id: Long): Boolean {
        val lm = lm() ?: return false
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < 0) return false
        for (p in first..last) if (msgIdAt(p) == id) return true
        return false
    }

    // ---- visibility state machine ----

    private fun hasUnread(): Boolean = nativeWantsShow && isCountMode

    /**
     * Drop anchors that have been REACHED or are no longer in the list. "Reached" is movement-driven:
     *  - gone: the anchor message is no longer loaded in the list.
     *  - returned: the jump carried it off-screen ([hasLeftViewport]) and it is now visible again
     *    (the user scrolled/tapped back down to it).
     *  - no-op jump (fallback): the jump never moved the list ([sawScroll] still false) yet the anchor
     *    has stayed visible past [NO_SCROLL_SETTLE_MS] — the target was already on screen, so there is
     *    nothing to return from. The long settle guards against a DEFERRED jump (chat search switches
     *    pages then rv.post's the scroll ~700ms later) being mistaken for a no-op before it scrolls —
     *    the old fixed 600ms arm pruned the still-visible anchor first and hid the down button.
     *
     * A still-visible anchor that has neither left the viewport nor seen a scroll is kept: the jump is
     * pending. Returns true if any went.
     */
    private fun pruneAnchors(): Boolean {
        if (anchors.isEmpty()) return false
        val now = System.currentTimeMillis()
        val before = anchors.size
        val it = anchors.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (liveIndexOfMsgId(a.msgId) < 0) { it.remove(); continue }
            if (!isMsgIdVisible(a.msgId)) { a.hasLeftViewport = true; continue }
            val noOpJump = !a.sawScroll && now - a.addedAt >= NO_SCROLL_SETTLE_MS
            if (a.hasLeftViewport || noOpJump) it.remove()
        }
        val removed = anchors.size != before
        if (removed) Utils.log("BubbleTextView.pruneAnchors: $before -> ${anchors.size} (reached/gone)")
        return removed
    }

    /**
     * Recompute the mode from current state and apply it. Called on every scroll AND every layout
     * pass, so it MUST be cheap and idempotent: appearance (background / text / icon) is only touched
     * when the mode actually changes, otherwise re-styling every layout would requestLayout in a loop
     * via the global-layout listener. Only an unread-count text change restyles within COLORED mode.
     */
    private fun refresh() {
        pruneAnchors()
        val unread = hasUnread()
        val show = unread ||
            anchors.isNotEmpty() ||
            (lastScrollDir > 0 && distanceToLast() > SCROLL_DIST_THRESHOLD)
        val mode = when {
            !show -> Mode.HIDDEN
            unread -> Mode.COLORED
            else -> Mode.DARK
        }
        if (mode != lastMode) {
            when (mode) {
                Mode.COLORED -> {
                    background = blueBg
                    setTextColor(coloredTint)
                    super.setText(countText, BufferType.NORMAL)
                    leadingSymbol(MaterialSymbols.arrow_downward, coloredTint, sizeDp = 14, gap = 3)
                }
                Mode.DARK -> {
                    background = greyBg
                    super.setText("", BufferType.NORMAL)
                    leadingSymbol(MaterialSymbols.arrow_downward, darkTint, sizeDp = 14, gap = 0)
                }
                Mode.HIDDEN -> {}
            }
            super.setVisibility(if (mode == Mode.HIDDEN) View.GONE else View.VISIBLE)
            Utils.log("BubbleTextView.refresh: $lastMode -> $mode unread=$unread anchors=${anchors.size} dir=$lastScrollDir dist=${distanceToLast()}")
            lastMode = mode
        } else if (mode == Mode.COLORED && text?.toString() != countText) {
            // Count changed while staying colored (a new message arrived) — update text only.
            setTextColor(coloredTint)
            super.setText(countText, BufferType.NORMAL)
            leadingSymbol(MaterialSymbols.arrow_downward, coloredTint, sizeDp = 14, gap = 3)
        }
    }

    // ---- click: staged return ----

    private fun onBubbleClick(v: View?) {
        pruneAnchors()
        val target = anchors.lastOrNull()
        if (target != null) {
            val idx = liveIndexOfMsgId(target.msgId)
            if (idx >= 0) {
                // Scroll back to the most recent anchor; it's pruned by refresh() once it's on screen.
                // Mark it as having left the viewport so the scroll-in counts as "returned" and prunes
                // it even if it was a fresh re-jump that never actually went off-screen.
                target.hasLeftViewport = true
                rv()?.smoothScrollToStart(idx)
                Utils.log("BubbleTextView: returning to anchor msgId=${target.msgId} idx=$idx")
                return
            }
            // Anchor no longer loaded — drop it and fall through to go-to-bottom.
            anchors.remove(target)
        }
        goToBottom(v)
    }

    /**
     * Continuous smooth scroll to the latest message (visits/binds every row on the way, unlike the
     * native snap). Falls back to the native click if the list/index isn't available.
     */
    private fun goToBottom(v: View?) {
        val rv = rv()
        val last = msgCount() - 1
        if (rv != null && last >= 0) rv.smoothScrollToEnd(last) else delegateClick?.onClick(v)
    }

    // ---- lifecycle / listeners ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        current = this
        attachListeners(0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (current === this) current = null
        anchors.clear()
        lastScrollDir = 0
        lastMode = Mode.HIDDEN
        resolvedRv?.let { r ->
            layoutListener?.let { l -> runCatching { r.viewTreeObserver?.removeOnGlobalLayoutListener(l) } }
            scrollListener?.let { s -> runCatching { r.removeOnScrollListener(s) } }
        }
        layoutListener = null
        scrollListener = null
        resolvedRv = null
        listenersAttached = false
    }

    private fun attachListeners(tries: Int) {
        if (listenersAttached || tries > 20) return
        // Resolve from our own hierarchy first (correct chat); only fall back to the global static.
        val rv = findChatRecyclerView() ?: runCatching { CurrentMsgList.vb.H }.getOrNull()
        if (rv == null) {
            postDelayed({ attachListeners(tries + 1) }, 200)
            return
        }
        listenersAttached = true
        resolvedRv = rv
        val sl = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Record that the list actually moved, so pruneAnchors can tell a jump that has scrolled
                // (anchor genuinely reached / a no-op tiny jump) from a deferred jump that hasn't run yet.
                if (kotlin.math.abs(dy) > 4 && anchors.isNotEmpty()) anchors.forEach { it.sawScroll = true }
                val nd = if (dy > 4) 1 else if (dy < -4) -1 else lastScrollDir
                if (nd != lastScrollDir) {
                    Utils.log("BubbleTextView.onScrolled: dy=$dy dir=$lastScrollDir->$nd dist=${distanceToLast()} last=${lm()?.findLastVisibleItemPosition()} count=${msgCount()}")
                    lastScrollDir = nd
                }
                refresh()
            }
        }
        scrollListener = sl
        rv.addOnScrollListener(sl)
        // A jump (or an older-page load) can place an anchor back on screen WITHOUT emitting a scroll
        // event, so also re-evaluate on layout passes — this is what prunes the anchor and hides the
        // button when the user jumps back without a clean onScrolled (the old bug). Gated on a pending
        // anchor so it's a no-op (no per-frame list scan) in the common case.
        val ll = ViewTreeObserver.OnGlobalLayoutListener { if (anchors.isNotEmpty()) refresh() }
        layoutListener = ll
        runCatching { rv.viewTreeObserver.addOnGlobalLayoutListener(ll) }
        Utils.log("BubbleTextView listeners attached rv=${System.identityHashCode(rv)}")
    }

    companion object {
        private const val SCROLL_DIST_THRESHOLD = 3
        // Fallback only: if a jump NEVER scrolls the list (target was already fully on screen) the
        // anchor can't be detected as reached by movement, so drop it once this much time has passed
        // with the anchor still visible and no scroll seen. Must be longer than any jump's dispatch
        // latency (page switch + rv.post for the chat-search jump is ~700ms) so a deferred jump that
        // simply hasn't scrolled YET is never mistaken for a no-op jump. See [pruneAnchors].
        private const val NO_SCROLL_SETTLE_MS = 2500L

        private var current: BubbleTextView? = null

        /**
         * A return point: the message we jumped from.
         *
         * Pruning is movement-driven, NOT wall-clock-driven (a fixed timer mis-fired on the deferred
         * chat-search jump, which scrolls ~700ms after the anchor is added — the timer pruned the
         * still-visible anchor before the jump had moved, hiding the down button). [sawScroll] records
         * that the list actually scrolled after this anchor was added; [hasLeftViewport] records that
         * the jump carried the anchor off-screen at least once. An anchor counts as "reached" only once
         * it has left and come back into view.
         */
        private class Anchor(val msgId: Long, val addedAt: Long) {
            var sawScroll = false
            var hasLeftViewport = false
        }

        // Outstanding programmatic upward jumps, oldest-first. Removed when reached (see pruneAnchors).
        // Tapping returns to the most recent (last). Static so it survives a jump's detach/attach.
        private val anchors = ArrayList<Anchor>()
        private const val MAX_ANCHORS = 16

        /**
         * Call right before a programmatic upward jump (reply source / jump-to-first-unread). Pushes
         * the top-most currently visible message as a return anchor and shows the button immediately.
         * A delayed refresh re-checks after the no-scroll settle window so a no-op jump (target already
         * on screen, list never scrolls) is eventually pruned even without further scroll/layout events.
         */
        fun beginJumpUp() {
            val view = current
            val lm = runCatching { CurrentMsgList.vb.H.layoutManager as? AIOLayoutManager }.getOrNull()
            val pos = lm?.findFirstVisibleItemPosition() ?: -1
            // Prefer the live adapter list (its indices match findFirstVisibleItemPosition); fall back
            // to the mirror. Must match what isMsgIdVisible() reads or the anchor never prunes.
            val live = runCatching { CurrentMsgList.uiOp?.m() }.getOrNull()
            val item = (live?.getOrNull(pos) ?: CurrentMsgList.msgList.value.getOrNull(pos)) as? WatchAIOMsgItem
            val id = item?.d?.msgId
            if (id != null) {
                anchors.removeAll { it.msgId == id }    // de-dup: move to most-recent
                anchors.add(Anchor(id, System.currentTimeMillis()))
                while (anchors.size > MAX_ANCHORS) anchors.removeAt(0)
            }
            Utils.log("BubbleTextView beginJumpUp anchor pos=$pos id=$id anchors=${anchors.size}")
            view?.refresh()
            view?.postDelayed({ view.refresh() }, NO_SCROLL_SETTLE_MS + 100)
        }
    }
}
