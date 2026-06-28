package momoi.mod.qqpro.hook.menu

import android.view.View
import androidx.fragment.app.FragmentManager
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import com.tencent.qqnt.kernel.nativeinterface.MemberRole
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.kernel.nativeinterface.PicElement
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.biz.richframework.util.RFWSaveUtil
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import WatchPicElementExtKt
import download
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.child
import momoi.mod.qqpro.hook.ChatMultiSelect
import momoi.mod.qqpro.hook.PressedImage
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentGroupMembers
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.action.MessageEdit
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.hook.aio_cell.MarketFaceImage
import momoi.mod.qqpro.hook.aio_cell.doAddFavEmoji
import momoi.mod.qqpro.hook.screenshot.ChatScreenshot
import momoi.mod.qqpro.hook.translate.HistoryTranslate
import momoi.mod.qqpro.hook.translate.MessageTranslate
import momoi.mod.qqpro.hook.copyImageFileToClipboard
import momoi.mod.qqpro.hook.copyImageToClipboard
import momoi.mod.qqpro.hook.forwardMsgRecord
import momoi.mod.qqpro.hook.forwardText
import momoi.mod.qqpro.hook.repeatMsgRecord
import momoi.mod.qqpro.hook.shareImageFile
import momoi.mod.qqpro.hook.shareMessage
import momoi.mod.qqpro.hook.view.ConfirmFragment
import momoi.mod.qqpro.hook.view.PartialCopyFragment
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.msg.getImageUrl
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.util.Json
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Shared message-menu data layer — the SINGLE source of truth for "what can I do with this message",
 * used by BOTH the long-press menu (长按菜单调整, display-only) and 消息多选 ([ChatMultiSelect]).
 *
 * [Entry] is the action model; [MsgCapabilities] derives the per-message facts once; [buildMessageActions]
 * assembles the complete ordered action list (recall included, resolved by awaiting eligibility — no
 * deferred row insertion). Nothing here depends on the menu Fragment: the only thing it needed was the
 * native item list ([names]) and a [native] dispatcher callback, both passed in (empty/no-op from
 * multi-select), so the list is identical in both contexts.
 */

/** One menu action. Public so both the menu and multi-select share the same model. */
class Entry(
    val order: Int,
    val label: String,
    val symbol: String,
    val destructive: Boolean,
    val action: () -> Unit,
)

/** Main-thread scope for the (lightly) async action builds. lifecycleScope isn't on the watch
 *  classpath; callers must guard their UI work against a dismissed/detached fragment after awaiting. */
val menuScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

/**
 * The derived facts about a single message — computed once and reused by every action decision, so the
 * menu and multi-select agree (e.g. [copyText] is the ONE copy-text definition: text → ark rich text →
 * file name). [pressedEl] is the specific image the user long-pressed in a multi-image bubble.
 */
class MsgCapabilities(val msg: MsgRecord?, val msgItem: WatchAIOMsgItem?) {
    val isArk = msg?.elements?.any { it.arkElement != null } == true
    val fwdText = msg?.elements?.mapNotNull { it.textElement?.content }?.joinToString("")
        ?.takeIf { it.isNotBlank() }
    val fileName = msg?.elements?.firstNotNullOfOrNull { it.fileElement?.fileName }
        ?.takeIf { it.isNotBlank() }
    val arkText = if (isArk && msg != null) arkText(msg) else null
    /** Copyable text: plain text → ark rich text → file name. The one definition both copy paths use. */
    val copyText = fwdText ?: arkText ?: fileName
    val forwardable = msg?.elements?.any {
        it.faceElement != null || it.marketFaceElement != null || it.videoElement != null ||
            it.pttElement != null || it.picElement != null || it.giphyElement != null ||
            it.faceBubbleElement != null
    } == true
    val hasPtt = msg?.elements?.any { it.pttElement != null } == true
    val hasPic = msg?.elements?.any { it.picElement != null } == true
    val pressedEl = msg?.let { PressedImage.elementFor(it.msgId) }?.takeIf { it.picElement != null }
    val picCount = msg?.elements?.count { it.picElement != null } ?: 0
    val mfFile: File? = msg?.takeIf { r -> r.elements?.any { it.marketFaceElement != null } == true }
        ?.let { MarketFaceImage.fileFor(it.msgId) }
    val hasShareableMedia = msg?.elements?.any {
        it.picElement != null || it.videoElement != null || it.pttElement != null
    } == true
    val isSelf = msg != null && msg.senderUid == SelfContact.peerUid
    val picEl: PicElement? = pressedEl?.picElement ?: msg?.elements?.firstNotNullOfOrNull { it.picElement }
    val ownPicSave = picEl != null && picCount > 1
}

/**
 * Build the complete, ordered action list for [msg]. [names] is the kernel's key_item_list (drives the
 * native-dispatch entries 回复/复制/翻译/朗读/保存/收藏 fallbacks; empty from multi-select). [native]
 * invokes a native menu item (the menu passes a fragment-backed dispatcher; multi-select passes none).
 * Recall is included inline by awaiting [recallEligible] — no deferred insertion.
 */
suspend fun buildMessageActions(
    host: View,
    msg: MsgRecord?,
    msgItem: WatchAIOMsgItem?,
    fm: FragmentManager?,
    isHistory: Boolean,
    names: Set<String>,
    native: (String) -> Unit = {},
): List<Entry> {
    val caps = MsgCapabilities(msg, msgItem)
    val out = ArrayList<Entry>()
    fun add(order: Int, label: String, symbol: String, destructive: Boolean = false, action: () -> Unit) =
        out.add(Entry(order, label, symbol, destructive, action))

    // 1 回复 (native, live only)
    if (!isHistory && "ReplyMsg" in names) add(1, "回复", MaterialSymbols.reply) { native("ReplyMsg") }
    // 2 复制 (our own copy when we have the text; else native CopyMsg)
    val copyText = caps.copyText
    if (copyText != null) add(2, "复制", MaterialSymbols.content_copy) { Utils.copyToClipboard(host.context, copyText) }
    else if ("CopyMsg" in names) add(2, "复制", MaterialSymbols.content_copy) { native("CopyMsg") }
    // 3 撤回 — recall via kernel, gated on awaited eligibility (own / owner / admin-on-others). Red.
    if (!isHistory && msg != null && msg.msgId != 0L && recallEligible(msg))
        add(3, "撤回", MaterialSymbols.undo, destructive = true) {
            runCatching { KernelServiceUtil.c()?.recallMsg(CurrentContact, msg.msgId, null) }
                .onFailure { Utils.log("menu recall failed: $it") }
        }
    // 4 编辑 (own text message)
    if (!isHistory && caps.isSelf && msg != null && caps.fwdText != null)
        add(4, "编辑", MaterialSymbols.edit) { MessageEdit.beginFull(msg) }
    // 5 部分复制 (text or ark)
    if (copyText != null && fm != null) add(5, "部分复制", MaterialSymbols.content_copy) {
        runCatching { PartialCopyFragment(copyText).show(fm, "qqpro_partial_copy") }
    }
    // 6 复制图片
    if (caps.hasPic && msg != null) add(6, "复制图片", MaterialSymbols.image) { host.copyImageToClipboard(msg, msgItem, caps.pressedEl) }
    else if (caps.mfFile != null) add(6, "复制图片", MaterialSymbols.image) { host.copyImageFileToClipboard(caps.mfFile) }
    // 7 转发
    if (msg != null && (caps.fwdText != null || caps.forwardable)) add(7, "转发", MaterialSymbols.forward) {
        if (caps.forwardable) host.forwardMsgRecord(msg, msgItem) else if (caps.fwdText != null) host.forwardText(caps.fwdText)
    }
    // 8 复读 (resend whole message; not ark/file/combined; live only)
    if (!isHistory && msg != null && (caps.forwardable || caps.fwdText != null) && !caps.isArk)
        add(8, "复读", MaterialSymbols.repeat) { repeatMsgRecord(msg, msgItem) }
    // 8 多选 (enter multi-select; ties with 复读, stable sort keeps it just below)
    if (!isHistory && Settings.chatMultiSelect.value && msg != null && msg.msgId != 0L)
        add(8, "多选", MaterialSymbols.check) { ChatMultiSelect.enter(msg.msgId) }
    // 9 系统分享
    if (caps.mfFile != null) add(9, "系统分享", MaterialSymbols.send) { host.shareImageFile(caps.mfFile) }
    else if (msg != null && (caps.fwdText != null || caps.hasShareableMedia)) add(9, "系统分享", MaterialSymbols.send) { host.shareMessage(msg, msgItem) }
    // 10 收藏 / 11 保存 (native dispatch when the cell offers it; else our own for in-bubble pic/marketface)
    val picEl = caps.picEl
    when {
        caps.ownPicSave -> add(10, "收藏", MaterialSymbols.star) { withPicFile(host, picEl!!) { f -> doAddFavEmoji(host.context, f) } }
        "SaveFavEmoji" in names -> add(10, "收藏", MaterialSymbols.star) { native("SaveFavEmoji") }
        picEl != null -> add(10, "收藏", MaterialSymbols.star) { withPicFile(host, picEl) { f -> doAddFavEmoji(host.context, f) } }
        caps.mfFile != null -> add(10, "收藏", MaterialSymbols.star) { doAddFavEmoji(host.context, caps.mfFile) }
    }
    when {
        caps.ownPicSave -> add(11, "保存", MaterialSymbols.download) { withPicFile(host, picEl!!) { f -> saveFileTo(host, f) } }
        "SavePic" in names -> add(11, "保存", MaterialSymbols.download) { native("SavePic") }
        picEl != null -> add(11, "保存", MaterialSymbols.download) { withPicFile(host, picEl) { f -> saveFileTo(host, f) } }
        caps.mfFile != null -> add(11, "保存", MaterialSymbols.download) { saveFileTo(host, caps.mfFile) }
    }
    // 12-14 翻译 / 隐藏翻译 / 朗读. Our own translate (ai-life endpoint) replaces QQ's native
    // TranslateText for text messages when enabled; otherwise fall back to the native entries.
    val ourTranslate = !isHistory && Settings.translateMenuEntry.value && msg != null && caps.fwdText != null
    // History (合并转发) text bubbles have no live cell, so translation renders into the forward
    // viewer's own registered TextView via [HistoryTranslate] instead of [MessageTranslate].
    val historyTranslate = isHistory && Settings.translateMenuEntry.value && msg != null &&
        caps.fwdText != null && HistoryTranslate.has(msg.msgId)
    if (historyTranslate) {
        val id = msg!!.msgId
        val label = if (HistoryTranslate.isOn(id)) "隐藏翻译" else "翻译"
        add(12, label, MaterialSymbols.translate) { HistoryTranslate.toggle(id) }
    } else if (ourTranslate) {
        val translateMsg = msg!!
        val label = if (MessageTranslate.isManual(translateMsg.msgId)) "隐藏翻译" else "翻译"
        add(12, label, MaterialSymbols.translate) { MessageTranslate.toggleManual(translateMsg) }
    } else if (caps.hasPtt) {
        // For voice messages QQ reuses the TranslateText item as 语音转文字 (speech-to-text).
        if (!isHistory && "TranslateText" in names) add(12, "转文字", MaterialSymbols.record_voice_over) { native("TranslateText") }
        if (!isHistory && "HideTranslateText" in names) add(13, "隐藏文字", MaterialSymbols.record_voice_over) { native("HideTranslateText") }
    } else {
        if (!isHistory && "TranslateText" in names) add(12, "翻译", MaterialSymbols.translate) { native("TranslateText") }
        if (!isHistory && "HideTranslateText" in names) add(13, "隐藏翻译", MaterialSymbols.translate) { native("HideTranslateText") }
    }
    if (!isHistory && "SpeakText" in names) add(14, "朗读", MaterialSymbols.volume_up) { native("SpeakText") }
    // 截图 — render this message into an image (long-press single; 消息多选 does the batch).
    if (!isHistory && Settings.chatScreenshot.value && msg != null)
        add(9, "截图", MaterialSymbols.image) { ChatScreenshot.capture(host, listOf(msg.msgId)) }
    // 15 删除 (local delete, live only) — red, last
    val deleteId = msg?.msgId
    if (!isHistory && deleteId != null && deleteId != 0L && msg != null)
        add(15, "删除", MaterialSymbols.delete, destructive = true) {
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

    return out.sortedBy { it.order }
}

/**
 * Whether the current user may recall [msg]. Replaces the old callback-based addRecall:
 *  - own message → yes (self-recall; kernel rejects past the time window, harmlessly).
 *  - group 群主(owner) → any message.
 *  - group 管理员(admin) → other NORMAL members' messages (not owner / another admin).
 *  - otherwise → no.
 * Group-role lookups are awaited via [memberOf].
 */
suspend fun recallEligible(msg: MsgRecord): Boolean {
    if (msg.msgId == 0L) return false
    if (msg.senderUid == SelfContact.peerUid) return true
    if (!CurrentContact.isGroup) return false
    val self = memberOf(SelfContact.peerUid) ?: return false
    return when (self.role) {
        MemberRole.OWNER -> true
        MemberRole.ADMIN -> {
            val target = memberOf(msg.senderUid) ?: return false
            target.role != MemberRole.OWNER && target.role != MemberRole.ADMIN
        }
        else -> false
    }
}

/**
 * Suspend bridge over [CurrentGroupMembers.get]'s callback. Uses CompletableDeferred (a non-inline
 * suspend await) rather than suspendCancellableCoroutine, which the compileOnly coroutines jar can't
 * inline. complete() is idempotent, so a double callback is harmless.
 */
private suspend fun memberOf(uid: String): MemberInfo? {
    val d = CompletableDeferred<MemberInfo?>()
    runCatching { CurrentGroupMembers.get(uid) { m -> d.complete(m) } }
        .onFailure { d.complete(null) }
    // CurrentGroupMembers.get's callback is NOT guaranteed to fire (kernel returns nothing / member
    // not in the cached list / bulk list never loads). Without a bound, d.await() would suspend
    // forever — the awaiting buildMessageActions never returns, so the long-press menu renders an
    // empty card and the menuScope coroutine (capturing card/fragment) leaks. Bound it: null on
    // timeout → recallEligible treats it as "not eligible" and the menu still renders.
    return withTimeoutOrNull(2000) { d.await() }
        ?: run { Utils.log("menu: memberOf($uid) timed out"); null }
}

/** Save a captured media [file] (marketface/sticker) to the gallery, with feedback. */
private fun saveFileTo(host: View, file: File) {
    runCatching { RFWSaveUtil.a(host.context, file.path, null); Utils.toast(host.context, "已保存到相册") }
        .onFailure { Utils.log("menu save file: $it"); Utils.toast(host.context, "保存失败") }
}

/**
 * Resolve a picture message's local file, then run [use] with it. Live chat images are owned by the
 * kernel (resolve its local path); fall back to our cache / an HTTP download. Runs [use] on the UI thread.
 */
private fun withPicFile(host: View, pic: PicElement, use: (File) -> Unit) {
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

/**
 * Copyable text for an ark card. The ark "prompt" field is only a short summary, so reconstruct the
 * rich text the card shows (by app type) and fall back to prompt when the schema is unknown.
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
        json.str("app") == "com.tencent.mannounce" -> {
            val a = meta.json("mannounce") ?: meta.keys.firstOrNull()?.let { meta.json(it) }
            join(decodeB64(a?.str("title")), decodeB64(a?.str("text")))
        }
        json.str("app") == "com.tencent.contact.lua" ->
            meta.json("contact")?.let { join(it.str("nickname"), it.str("contact")) }
        json.str("app") == "com.tencent.map" ->
            meta.json("Location.Search")?.let { join(it.str("name"), it.str("address")) }
        else -> meta.keys.firstOrNull()?.let { meta.json(it) }?.let { join(it.str("title"), it.str("desc")) }
    }
    rich ?: prompt
}.getOrNull()
