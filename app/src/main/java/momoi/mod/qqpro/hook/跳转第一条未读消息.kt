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

class SkipAction(
    private val rv: RecyclerView,
    private val tv: TextView,
    private val recent: RecentContacts.Data
): View.OnClickListener {

    private fun format(count: Int) = "↑ ${count}条新消息"
    private var count = recent.unreadCntCached
    private var lastUnreadMsg: WatchAIOMsgItem? = null
    private var isClicked = false

    init {
        tv.text = format(count)
        tv.setOnClickListener(this)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            var isFinished = false
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isFinished) return
                val first = (rv.layoutManager as AIOLayoutManager).findFirstVisibleItemPosition()
                if (first == -1) return
                val newCount = recent.unreadCntCached - CurrentMsgList.msgList.value.size + first
                if (newCount < count) {
                    count = newCount
                    lastUnreadMsg = CurrentMsgList.msgList.value.getOrNull(first)
                    if (count > 0) {
                        tv.text = format(count)
                    } else {
                        isFinished = true
                        tv.visibility = View.GONE
                    }
                }
            }
        })
    }

    override fun onClick(v: View?) {
        if (isClicked) return
        val list = CurrentMsgList.msgList.value
        Utils.log("SkipAction click: count=$count lastUnreadMsg=${lastUnreadMsg != null} listSize=${list.size}")

        val onProgress: (Int) -> Unit = { pct -> tv.text = "加载中 $pct%" }
        val onFail: () -> Unit = {
            Utils.toast(tv.context, "加载失败，请重试")
            tv.text = format(count)
            isClicked = false
        }
        // Remember where we are now and show the back-down button so the user can return here.
        BubbleTextView.beginJumpUp()
        when {
            lastUnreadMsg != null -> {
                isClicked = true
                CurrentMsgList.upwardMsg(CurrentMsgList.getMsgIndex(lastUnreadMsg!!), count, onProgress, onFail) {
                    rv.scrollToPosition(it)
                }
            }
            // Not scrolled yet: jump straight to the first unread, measured from the latest message.
            list.isNotEmpty() && count > 0 -> {
                isClicked = true
                CurrentMsgList.upwardMsg(list.size - 1, count - 1, onProgress, onFail) {
                    rv.scrollToPosition(it)
                }
            }
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