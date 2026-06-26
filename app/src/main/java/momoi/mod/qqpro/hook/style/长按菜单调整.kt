package momoi.mod.qqpro.hook.style

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MemberRole
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.biz.richframework.util.RFWSaveUtil
import WatchPicElementExtKt
import download
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.cell.base.WatchAIOGroupWidgetItemCell
import com.tencent.watch.aio_impl.ui.menu.AIOLongClickMenuFragment
import com.tencent.watch.aio_impl.ui.menu.MenuItemFactory
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.hook.HistoryMsgRegistry
import momoi.mod.qqpro.hook.aio_cell.MarketFaceImage
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentGroupMembers
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.action.MessageEdit
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.hook.copyImageFileToClipboard
import momoi.mod.qqpro.hook.copyImageToClipboard
import momoi.mod.qqpro.hook.forwardMsgRecord
import momoi.mod.qqpro.hook.forwardText
import momoi.mod.qqpro.hook.repeatMsgRecord
import momoi.mod.qqpro.hook.shareImageFile
import momoi.mod.qqpro.hook.shareMessage
import momoi.mod.qqpro.hook.view.ConfirmFragment
import momoi.mod.qqpro.hook.view.PartialCopyFragment
import momoi.mod.qqpro.hook.aio_cell.doAddFavEmoji
import momoi.mod.qqpro.child
import momoi.mod.qqpro.msg.getImageUrl
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File

/**
 * 长按菜单 Material 重建 — built entirely from scratch (no native rows): one ordered, data-driven
 * [Entry] list rendered as Material rows (Material Symbol icons, accent / error tint, rounded M3
 * card that clips while scrolling, ripple, dim scrim). Each action is either native (invoke the
 * fragment's callback `b` with the ItemEnum) or a QQPro custom one. Ordered most-used-first.
 *
 * Inside a 合并转发聊天记录 history viewer the message isn't live, so only read-only entries are shown
 * (no 删除/撤回/编辑/复读/回复). Rows are tagged with their order so the async 撤回 can be inserted in place.
 */
@Mixin
class 长按菜单调整(p0: (MenuItemFactory.ItemEnum) -> Unit, p1: String?) :
    AIOLongClickMenuFragment(p0, p1) {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val msgId = arguments?.getLong("key_msg_id") ?: 0L
        val cellItem = runCatching {
            val field = this.b.javaClass.getDeclaredField("b")
            field.isAccessible = true
            (field.get(this.b) as WatchAIOGroupWidgetItemCell<*, *>).f()
        }.getOrNull()
        val liveItem = cellItem ?: CurrentMsgList.msgList.value.find { it.d.msgId == msgId }
        val isHistory = liveItem == null
        val msg = liveItem?.d ?: HistoryMsgRegistry.find(msgId)
        val fm = runCatching { parentFragmentManager }.getOrNull()
        Utils.log("menu: msg=${msg != null} history=$isHistory msgId=$msgId")
        return LongPressMenu.build(this, inflater, msg, liveItem, fm, isHistory)
    }
}

object LongPressMenu {

    private class Entry(
        val order: Int,
        val label: String,
        val symbol: String,
        val destructive: Boolean,
        val action: () -> Unit,
    )

    @SuppressLint("ClickableViewAccessibility")
    fun build(
        fragment: AIOLongClickMenuFragment,
        inflater: LayoutInflater,
        msg: MsgRecord?,
        msgItem: WatchAIOMsgItem?,
        fm: androidx.fragment.app.FragmentManager?,
        isHistory: Boolean,
    ): View {
        val ctx = inflater.context
        // Material = one M3 surface card with ripple rows; non-material = semi-transparent dark rows
        // with white text (the "+ menu material-disabled" look).
        val material = Settings.materialLongPressMenu.value
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
        }

        buildEntries(fragment, card, msg, msgItem, fm, isHistory).sortedBy { it.order }.forEach { e ->
            card.addView(rowFor(fragment, e, material))
        }
        addRecall(fragment, card, msg, isHistory, material)

        // The rounded frame is the (fixed) ScrollView; rows scroll INSIDE it. Material clips them to a
        // solid surface card; non-material is a transparent container (each row is its own pill).
        val scroll = ScrollView(ctx).apply {
            if (material) {
                background = GradientDrawable().apply { setColor(M3.surfaceContainer); cornerRadius = M3.radiusLg }
                clipToOutline = true
                setPadding(0, 6.dp, 0, 6.dp)
            }
            isVerticalScrollBarEnabled = false
            isClickable = true
            addView(card, FrameLayout.LayoutParams(MP, WC))
        }
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x99_000000.toInt())
            setOnClickListener { runCatching { fragment.dismiss() } }
            addView(scroll, FrameLayout.LayoutParams(MP, WC, Gravity.CENTER).apply {
                val m = 14.dp; leftMargin = m; rightMargin = m; topMargin = m; bottomMargin = m
            })
        }
        return SwipeBackLayout(ctx).apply {
            onSwipeBack = { runCatching { fragment.dismiss() } }
            addView(scrim, FrameLayout.LayoutParams(MP, MP))
        }
    }

    private fun buildEntries(
        fragment: AIOLongClickMenuFragment,
        host: View,
        msg: MsgRecord?,
        msgItem: WatchAIOMsgItem?,
        fm: androidx.fragment.app.FragmentManager?,
        isHistory: Boolean,
    ): List<Entry> {
        val names = runCatching { fragment.requireArguments().getStringArrayList("key_item_list") }
            .getOrNull().orEmpty().toSet()
        val out = ArrayList<Entry>()
        fun add(order: Int, label: String, symbol: String, destructive: Boolean = false, action: () -> Unit) =
            out.add(Entry(order, label, symbol, destructive, action))
        fun native(name: String): () -> Unit = {
            runCatching { fragment.b.invoke(MenuItemFactory.ItemEnum.valueOf(name)) }
                .onFailure { Utils.log("menu native $name: $it") }
        }

        val isArk = msg?.elements?.any { it.arkElement != null } == true
        val fwdText = msg?.elements?.mapNotNull { it.textElement?.content }?.joinToString("")
            ?.takeIf { it.isNotBlank() }
        // File messages carry no text element — copy the file name (the old behaviour the native
        // CopyMsg used to provide). firstNotNullOfOrNull skips elements without a fileElement.
        val fileName = msg?.elements?.firstNotNullOfOrNull { it.fileElement?.fileName }
            ?.takeIf { it.isNotBlank() }
        // Copyable text: plain text, an ark card's prompt, or a file element's name.
        val copyText = fwdText ?: (if (isArk) arkText(msg) else null) ?: fileName
        // Resendable (sendMsg-rebuildable) media; ark / file / combined-forward are NOT.
        val forwardable = msg?.elements?.any {
            it.faceElement != null || it.marketFaceElement != null || it.videoElement != null ||
                it.pttElement != null || it.picElement != null || it.giphyElement != null ||
                it.faceBubbleElement != null
        } == true
        val hasPic = msg?.elements?.any { it.picElement != null } == true
        // For a multi-image bubble, resolve the image the user actually long-pressed (ImagePlay records
        // it on touch); elements.first() — what native and our old code used — always grabbed image #1.
        val pressedEl = msg?.let { momoi.mod.qqpro.hook.PressedImage.elementFor(it.msgId) }
            ?.takeIf { it.picElement != null }
        val picCount = msg?.elements?.count { it.picElement != null } ?: 0
        val mfFile: File? = msg?.takeIf { r -> r.elements?.any { it.marketFaceElement != null } == true }
            ?.let { MarketFaceImage.fileFor(it.msgId) }
        val hasShareableMedia = msg?.elements?.any {
            it.picElement != null || it.videoElement != null || it.pttElement != null
        } == true
        val isSelf = msg != null && msg.senderUid == SelfContact.peerUid

        // 1 回复 (live only)
        if (!isHistory && "ReplyMsg" in names) add(1, "回复", MaterialSymbols.reply, action = native("ReplyMsg"))
        // 2 复制 (works for ark via its prompt; native toast breaks at watch DPI so copy ourselves)
        if (copyText != null) add(2, "复制", MaterialSymbols.content_copy) { Utils.copyToClipboard(host.context, copyText) }
        else if ("CopyMsg" in names) add(2, "复制", MaterialSymbols.content_copy, action = native("CopyMsg"))
        // 3 撤回 (own message, native) — red; non-own admin recall added async in addRecall
        if (!isHistory && isSelf && "RevokeMsg" in names)
            add(3, "撤回", MaterialSymbols.undo, destructive = true, action = native("RevokeMsg"))
        // 4 编辑 (own message WITH text), just below 撤回 — media-only messages have nothing to edit.
        if (!isHistory && isSelf && msg != null && fwdText != null)
            add(4, "编辑", MaterialSymbols.edit) { MessageEdit.beginFull(msg) }
        // 5 部分复制 (text or ark)
        if (copyText != null && fm != null) add(5, "部分复制", MaterialSymbols.content_copy) {
            runCatching { PartialCopyFragment(copyText).show(fm, "qqpro_partial_copy") }
        }
        // 6 复制图片
        if (hasPic && msg != null) add(6, "复制图片", MaterialSymbols.image) { host.copyImageToClipboard(msg, msgItem, pressedEl) }
        else if (mfFile != null) add(6, "复制图片", MaterialSymbols.image) { host.copyImageFileToClipboard(mfFile) }
        // 7 转发 (to other chats)
        if (msg != null && (fwdText != null || forwardable)) add(7, "转发", MaterialSymbols.forward) {
            if (forwardable) host.forwardMsgRecord(msg) else if (fwdText != null) host.forwardText(fwdText)
        }
        // 8 复读 (resend whole message; not ark/file/combined; live only) — below 转发
        if (!isHistory && msg != null && (forwardable || fwdText != null) && !isArk)
            add(8, "复读", MaterialSymbols.repeat) { repeatMsgRecord(msg) }
        // 9 系统分享 (to other apps)
        if (mfFile != null) add(9, "系统分享", MaterialSymbols.send) { host.shareImageFile(mfFile) }
        else if (msg != null && (fwdText != null || hasShareableMedia)) add(9, "系统分享", MaterialSymbols.send) { host.shareMessage(msg, msgItem) }
        // 10 收藏 / 11 保存. Native dispatch (which shows its own toast) when the cell offers it;
        // otherwise our own for the in-bubble image / marketface that the native menu omits.
        val picEl: PicElement? = pressedEl?.picElement ?: msg?.elements?.firstNotNullOfOrNull { it.picElement }
        // With several images in one bubble, native SavePic/SaveFavEmoji also fall back to image #1, so
        // we must handle it ourselves with the pressed image. For a single image keep deferring to native.
        val ownPicSave = picEl != null && picCount > 1
        when {
            ownPicSave -> add(10, "收藏", MaterialSymbols.star) { withPicFile(host, picEl!!) { f -> doAddFavEmoji(host.context, f) } }
            "SaveFavEmoji" in names -> add(10, "收藏", MaterialSymbols.star, action = native("SaveFavEmoji"))
            picEl != null -> add(10, "收藏", MaterialSymbols.star) { withPicFile(host, picEl) { f -> doAddFavEmoji(host.context, f) } }
            mfFile != null -> add(10, "收藏", MaterialSymbols.star) { doAddFavEmoji(host.context, mfFile) }
        }
        when {
            ownPicSave -> add(11, "保存", MaterialSymbols.download) { withPicFile(host, picEl!!) { f -> saveFileTo(host, f) } }
            "SavePic" in names -> add(11, "保存", MaterialSymbols.download, action = native("SavePic"))
            picEl != null -> add(11, "保存", MaterialSymbols.download) { withPicFile(host, picEl) { f -> saveFileTo(host, f) } }
            mfFile != null -> add(11, "保存", MaterialSymbols.download) { saveFileTo(host, mfFile) }
        }
        // 12-14 翻译 / 隐藏翻译 / 朗读 (live only)
        if (!isHistory && "TranslateText" in names) add(12, "翻译", MaterialSymbols.translate, action = native("TranslateText"))
        if (!isHistory && "HideTranslateText" in names) add(13, "隐藏翻译", MaterialSymbols.translate, action = native("HideTranslateText"))
        if (!isHistory && "SpeakText" in names) add(14, "朗读", MaterialSymbols.volume_up, action = native("SpeakText"))
        // 15 删除 (local delete, live only) — red, last
        val deleteId = msg?.msgId
        if (!isHistory && deleteId != null && deleteId != 0L && msg != null)
            add(15, "删除", MaterialSymbols.delete, destructive = true) {
                // The menu has dismissed; show the confirm on the chat fragment's manager.
                val doDelete = {
                    val contact = Contact(msg.chatType, msg.peerUid, "")
                    runCatching {
                        MsgUtil.msgService.deleteMsg(contact, arrayListOf(deleteId), IOperateCallback { code, reason ->
                            Utils.log("menu delete: id=$deleteId code=$code reason=$reason")
                            runOnUi {
                                if (code == 0) { CurrentMsgList.removeLive(setOf(deleteId)); Utils.toast(host.context, "删除成功") }
                                else Utils.toast(host.context, "删除失败")
                            }
                        })
                    }.onFailure { Utils.log("menu delete failed: $it"); Utils.toast(host.context, "删除失败") }
                }
                if (fm != null) runCatching {
                    ConfirmFragment("仅从本机删除此消息，不会撤回，对方仍可看到。", "删除", destructive = true) { doDelete() }
                        .show(fm, "qqpro_delete_confirm")
                }.onFailure { doDelete() } else doDelete()
            }

        return out
    }

    /**
     * 撤回 row logic, driven by the SELF role (not the kernel's "RevokeMsg" menu item, which it drops
     * once the self-recall time window passes — so owner/admin would otherwise lose recall on their
     * own/old messages). In a group:
     *  - 群主 (owner): recall any message, including own — always.
     *  - 管理员 (admin): recall own messages and other normal members', but NOT the owner or another
     *    admin's.
     *  - normal member: only the kernel's time-limited self-recall ([buildEntries]'s native RevokeMsg).
     * Inserted at order 3; de-duped against the native self-recall [buildEntries] may already have added.
     */
    private fun addRecall(fragment: AIOLongClickMenuFragment, card: LinearLayout, msg: MsgRecord?, isHistory: Boolean, material: Boolean) {
        if (isHistory || msg == null) return
        val recallId = msg.msgId
        if (recallId == 0L) return
        if (!CurrentContact.isGroup) return
        val isSelf = msg.senderUid == SelfContact.peerUid
        val targetUid = msg.senderUid
        fun addRow() = runOnUi {
            // Don't duplicate an order-3 撤回 row already present (e.g. the native self-recall that
            // buildEntries adds while it's still in the kernel's menu list).
            if ((0 until card.childCount).any { card.getChildAt(it).tag == 3 }) return@runOnUi
            val e = Entry(3, "撤回", MaterialSymbols.undo, true) {
                runCatching { KernelServiceUtil.c()?.recallMsg(CurrentContact, recallId, null) }
                    .onFailure { Utils.log("menu recall failed: $it") }
            }
            insertByOrder(card, rowFor(fragment, e, material), 3)
        }
        CurrentGroupMembers.get(SelfContact.peerUid) { self ->
            when (self.role) {
                MemberRole.OWNER -> addRow() // owner: recall anyone, incl. self
                MemberRole.ADMIN ->
                    if (isSelf) addRow() // admin: own message always
                    else CurrentGroupMembers.get(targetUid) { target ->
                        // admin: other normal members only (not owner / other admins)
                        if (target.role != MemberRole.OWNER && target.role != MemberRole.ADMIN) addRow()
                    }
                else -> {} // normal member: only the native time-limited self-recall (buildEntries)
            }
        }
    }

    /** Save a captured media [file] (marketface/sticker) to the gallery, with feedback. */
    private fun saveFileTo(host: View, file: File) {
        runCatching { RFWSaveUtil.a(host.context, file.path, null); Utils.toast(host.context, "已保存到相册") }
            .onFailure { Utils.log("menu save file: $it"); Utils.toast(host.context, "保存失败") }
    }

    /**
     * Resolve a picture message's local file, then run [use] with it. Live chat images are owned by
     * the kernel (resolve its local path); fall back to our cache / an HTTP download (history images
     * carry a real url). Runs [use] on the UI thread.
     */
    private fun withPicFile(host: View, pic: com.tencent.qqnt.kernel.nativeinterface.PicElement, use: (File) -> Unit) {
        runCatching { WatchPicElementExtKt.C0(pic) }.getOrNull()
            ?.takeIf { it.isNotEmpty() && File(it).let { f -> f.exists() && f.length() > 0 } }
            ?.let { use(File(it)); return }
        val cacheDir = host.context.safeCacheDir ?: run { Utils.toast(host.context, "保存失败"); return }
        val cacheFile = cacheDir.child("${pic.md5HexStr}.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) { use(cacheFile); return }
        download(pic.getImageUrl(), cacheFile) { ok ->
            runOnUi { if (ok) use(cacheFile) else Utils.toast(host.context, "保存失败") }
        }
    }

    /** Ark card display text for copy — the `prompt` summary (e.g. "[分享]标题"). Non-destructive. */
    /**
     * Copyable text for an ark card. The card itself (see [momoi.mod.qqpro.hook.aio_cell.CardMsgView])
     * renders the full title/desc/body, but the ark "prompt" field is only a short summary (e.g.
     * "[群公告]…"), so copying it truncates. Reconstruct the same rich text the card shows, by app type,
     * and fall back to prompt when the schema is unknown.
     */
    private fun arkText(msg: MsgRecord): String? = runCatching {
        val ark = msg.elements.firstNotNullOfOrNull { it.arkElement } ?: return null
        val json = Json(ark.bytesData)
        val prompt = json.str("prompt")?.takeIf { it.isNotBlank() }
        val meta = json.json("meta")
        fun decodeB64(s: String?): String? = s?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrNull() }
        fun join(vararg parts: String?): String? =
            parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                .takeIf { it.isNotBlank() }
        val rich = when {
            meta == null -> null
            // 群公告 (com.tencent.mannounce): base64 title + body.
            json.str("app") == "com.tencent.mannounce" -> {
                val a = meta.json("mannounce") ?: meta.keys.firstOrNull()?.let { meta.json(it) }
                join(decodeB64(a?.str("title")), decodeB64(a?.str("text")))
            }
            // 名片 (com.tencent.contact.lua): nickname + account.
            json.str("app") == "com.tencent.contact.lua" ->
                meta.json("contact")?.let { join(it.str("nickname"), it.str("contact")) }
            // 位置 (com.tencent.map): name + address.
            json.str("app") == "com.tencent.map" ->
                meta.json("Location.Search")?.let { join(it.str("name"), it.str("address")) }
            // Generic title/desc card.
            else -> meta.keys.firstOrNull()?.let { meta.json(it) }?.let { join(it.str("title"), it.str("desc")) }
        }
        rich ?: prompt
    }.getOrNull()

    private fun rowFor(fragment: AIOLongClickMenuFragment, e: Entry, material: Boolean): View =
        row(fragment.requireContext(), e, material) { runCatching { e.action() }; runCatching { fragment.dismiss() } }
            .apply { tag = e.order }

    private fun insertByOrder(card: LinearLayout, rowView: View, order: Int) {
        val idx = (0 until card.childCount).firstOrNull { (card.getChildAt(it).tag as? Int ?: Int.MAX_VALUE) > order }
        if (idx != null) card.addView(rowView, idx) else card.addView(rowView)
    }

    private fun row(ctx: Context, e: Entry, material: Boolean, onClick: () -> Unit): View {
        // Material: accent/onSurface on a flush ripple row inside the surface card.
        // Non-material: white text + white icon (red for destructive) on a semi-transparent dark
        // rounded pill, with a small gap between rows.
        val red = 0xFF_FF6B6B.toInt()
        val tint = if (e.destructive) (if (material) M3.error else red) else (if (material) M3.primary else Color.WHITE)
        val textColor = if (e.destructive) (if (material) M3.error else red) else (if (material) M3.onSurface else Color.WHITE)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            isClickable = true
            background = if (material) {
                RippleDrawable(ColorStateList.valueOf(0x33_888888), null, ColorDrawable(Color.WHITE))
            } else {
                val pill = GradientDrawable().apply { setColor(0x80_242424.toInt()); cornerRadius = 14.dp.toFloat() }
                RippleDrawable(ColorStateList.valueOf(0x33_FFFFFF), pill, pill)
            }
            setOnClickListener { onClick() }
            if (!material) {
                // Each row is its own pill → give it horizontal/vertical margin so they don't touch.
                layoutParams = LinearLayout.LayoutParams(MP, WC).apply {
                    leftMargin = 10.dp; rightMargin = 10.dp; topMargin = 3.dp; bottomMargin = 3.dp
                }
            }
            addView(ImageView(ctx).apply { setImageDrawable(MaterialSymbol(e.symbol, tint)) },
                LinearLayout.LayoutParams(22.dp, 22.dp))
            addView(TextView(ctx).apply { text = e.label; setTextColor(textColor); textSize = 15f },
                LinearLayout.LayoutParams(0, WC, 1f).apply { marginStart = 16.dp })
        }
    }

    private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
}
