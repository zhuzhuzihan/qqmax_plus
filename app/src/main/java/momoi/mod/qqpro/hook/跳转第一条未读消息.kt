package momoi.mod.qqpro.hook

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.AIOLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tencent.aio.api.list.IListUIOperationApi
import com.tencent.mvi.api.help.CreateViewParams
import com.tencent.watch.aio_impl.coreImpl.vb.WatchAIOListVB
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.asGroup
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.action.RecentContacts
import momoi.mod.qqpro.hook.view.BubbleTextView
import momoi.mod.qqpro.hook.view.smoothScrollToStart
import momoi.mod.qqpro.lib.FrameScope
import momoi.mod.qqpro.lib.background
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize

/**
 * One stop the jump chip can take you to. [seq] is the msgSeq of an important unread message
 * (@我/回复/新文件/新公告); a [seq] of null is the terminal "first unread" stop, which is jumped to
 * the count-based way (it has no single msgSeq).
 */
private data class JumpPoint(val seq: Long?, val label: String)

/**
 * The top-right "↑ X条新消息" jump chip. When [Settings.chatImportantJump] is on it steps through the
 * important unread messages one at a time, bottom→top (newest unread first), before the final stop at
 * the first unread; with it off it behaves as the plain first-unread jump.
 *
 * The important stops come from the same kernel signal the conversation-list tags read
 * ([RecentContacts.Data.raw].listOfSpecificEventTypeInfosInMsgBox → each msg's msgSeq + label). They
 * are visited newest→oldest because that is the order you encounter them scrolling up from the bottom.
 * A stop clears (and the chip advances to the next, older one) as soon as its message scrolls into
 * view — whether you got there by tapping the chip or by scrolling manually. When the queue empties
 * (the first unread is reached) the chip hides.
 */
class SkipAction(
    private val rv: RecyclerView,
    private val tv: TextView,
    private val recent: RecentContacts.Data
): View.OnClickListener {

    private fun format(count: Int) = "↑ ${count}条新消息"
    private val unreadTotal = recent.unreadCntCached
    private var count = unreadTotal                 // remaining unread toward the first-unread stop
    private var lastUnreadMsg: WatchAIOMsgItem? = null
    private var isClicked = false
    private var isFinished = false

    // Ordered stops: important messages newest→oldest, then a terminal first-unread stop (seq=null).
    // When the setting is off the important stops are skipped → only the terminal stop remains.
    private val points: ArrayDeque<JumpPoint> = ArrayDeque<JumpPoint>().apply {
        if (Settings.chatImportantJump.value) {
            val seen = HashSet<Long>()
            recent.raw.listOfSpecificEventTypeInfosInMsgBox.orEmpty()
                .flatMap { e -> e.msgInfos.orEmpty().map { mi -> mi.msgSeq to specificEventLabel(e.eventTypeInMsgBox, mi.highlightDigest) } }
                .filter { it.first > 0L }
                .sortedByDescending { it.first }     // newest (closest to bottom) first
                .forEach { (seq, label) -> if (seen.add(seq)) addLast(JumpPoint(seq, label)) }
        }
        addLast(JumpPoint(null, format(unreadTotal)))
        Utils.log("SkipAction: ${size} stop(s) -> ${joinToString { it.label }}")
    }

    private fun head(): JumpPoint? = points.firstOrNull()

    /** Repaint the chip for the current head stop (terminal stop shows the live remaining count). */
    private fun refreshLabel() {
        val h = head() ?: return hide()
        tv.text = if (h.seq == null) format(count) else "↑ ${h.label}"
    }

    private fun hide() {
        isFinished = true
        tv.visibility = View.GONE
    }

    init {
        refreshLabel()
        tv.setOnClickListener(this)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isFinished) return
                val first = (rv.layoutManager as AIOLayoutManager).findFirstVisibleItemPosition()
                if (first == -1) return
                val list = CurrentMsgList.msgList.value
                var dirty = false

                // Pop important stops that have scrolled into view. The list is in ascending-seq order
                // (higher index = newer = bottom), so the top-of-viewport seq descends as you scroll
                // up; a stop is visible once that seq has reached (≤) the stop's own seq.
                val firstSeq = list.getOrNull(first)?.d?.msgSeq
                if (firstSeq != null) {
                    while (true) {
                        val h = head() ?: break
                        if (h.seq != null && firstSeq <= h.seq) { points.removeFirst(); dirty = true } else break
                    }
                }

                // Always track the remaining unread (for the terminal stop), the original way.
                val newCount = unreadTotal - list.size + first
                if (newCount < count) {
                    count = newCount
                    lastUnreadMsg = list.getOrNull(first)
                    dirty = true
                }

                val h = head()
                if (h == null || (h.seq == null && count <= 0)) { hide(); return }
                if (dirty) refreshLabel()
            }
        })
    }

    override fun onClick(v: View?) {
        if (isClicked || isFinished) return
        val h = head() ?: return
        val list = CurrentMsgList.msgList.value
        Utils.log("SkipAction click: head=${h.label} seq=${h.seq} count=$count listSize=${list.size}")

        val onProgress: (Int) -> Unit = { tv.text = "加载中…" }
        val onFail: () -> Unit = {
            Utils.toast(tv.context, "加载失败，请重试")
            isClicked = false
            refreshLabel()
        }
        // Remember where we are now and show the back-down button so the user can return here.
        BubbleTextView.beginJumpUp()
        isClicked = true
        if (h.seq != null) {
            // Important stop: locate the exact message (paging up if needed) and smooth-scroll to it.
            // The scroll listener pops it and advances to the next stop once it lands in view.
            CurrentMsgList.findMsg(h.seq, onProgress, result = { msg ->
                isClicked = false
                if (msg == null) { onFail(); return@findMsg }
                rv.smoothScrollToStart(CurrentMsgList.getMsgIndex(msg))
            })
        } else when {
            // Terminal first-unread stop, jumped the count-based way (no single msgSeq).
            lastUnreadMsg != null ->
                CurrentMsgList.upwardMsg(CurrentMsgList.getMsgIndex(lastUnreadMsg!!), count, onProgress, onFail) {
                    isClicked = false
                    rv.smoothScrollToStart(it)
                }
            list.isNotEmpty() && count > 0 ->
                CurrentMsgList.upwardMsg(list.size - 1, count - 1, onProgress, onFail) {
                    isClicked = false
                    rv.smoothScrollToStart(it)
                }
            else -> isClicked = false
        }
    }
}
@Mixin
class 跳转第一条未读消息 : WatchAIOListVB() {
    @SuppressLint("RtlHardcoded")
    override fun h(
        createViewParams: CreateViewParams,
        childView: View,
        uiHelper: IListUIOperationApi
    ): View = FrameScope(super.h(createViewParams, childView, uiHelper) as FrameLayout).apply {
        val peerUid = CurrentContact.peerUid
        Utils.log("current peerUid: $peerUid")
        RecentContacts.get(peerUid)?.let { recent ->
            Utils.log("unreadCntCached: ${recent.unreadCntCached}")
            if (recent.unreadCntCached > 0) {
                val tv = add<TextView>()
                    .layoutGravity(Gravity.RIGHT or Gravity.TOP)
                    // Left corners rounded, right square so it sits flush to the screen edge (the right
                    // semicircle is "hidden" by simply not rounding it) — no right margin needed.
                    .background(roundCornerDrawable(M3.surfaceContainerHigh, 9999f, 0f, 9999f, 0f))
                    .padding(6.dp)
                    .textSize(12f)
                    .textColor(M3.primary)
                // Only fix needed: clear the rich titlebar (which overlays the list from the very top
                // in chat-only mode) / screen top — it was flush against it.
                (tv.layoutParams as? FrameLayout.LayoutParams)?.topMargin =
                    (if (Settings.enableTitlebar.value) Settings.titlebarHeight.value.toInt() + 12 else 10).dp
                SkipAction(this@跳转第一条未读消息.H, tv, recent)
            }
        }
    }.group
}