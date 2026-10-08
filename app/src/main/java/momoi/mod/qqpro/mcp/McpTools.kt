package momoi.mod.qqpro.mcp

import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.enums.ChatType
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.action.RecentContacts
import momoi.mod.qqpro.hook.summarize.SummaryMessages
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The MCP tool surface. Everything here reads the live state the hooks already maintain
 * ([RecentContacts] conversation cache, [CurrentContact]/[CurrentMsgList] open-chat mirror) or calls
 * the QQ kernel through the same proven paths our in-app features use ([MsgUtil.msgService.sendMsg]
 * for text send — identical to NotificationReply / InlineInput).
 *
 * Threading: tool calls arrive on an MCP socket thread. Kernel calls with callbacks are bridged via
 * [CountDownLatch] (kernel callbacks fire on binder threads); UI-dependent state
 * ([CurrentMsgList.msgList] mirror) is snapshotted under the list's own monitor, and history paging
 * reuses [CurrentMsgList.loadAll], which internally hops to the UI thread and calls back from there.
 */
object McpTools {

    class ToolException(message: String) : Exception(message)

    /** Tool metadata for `tools/list`; [invoke] implements `tools/call`. */
    class Tool(
        val name: String,
        val description: String,
        val inputSchema: JSONObject,
        val invoke: (args: JSONObject) -> String,
    )

    val all: List<Tool> by lazy {
        listOf(
            Tool(
                name = "get_recent_chats",
                description = "列出最近的 QQ 会话（联系人/群聊），含未读数与最后一条消息摘要。" +
                    "返回的 peerUid 与 chatType 可用作其他工具的目标参数。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
            ) { getRecentChats() },
            Tool(
                name = "get_current_chat",
                description = "读取当前在手表上打开的聊天页的消息。参数：count 条数(默认 50, 最大 200)；" +
                    "loadAll 为 true 时先向上加载全部历史再返回(较慢)。需先在手表上打开该聊天。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("count", JSONObject().apply { put("type", "integer"); put("description", "返回的消息条数 1-200") })
                        put("loadAll", JSONObject().apply { put("type", "boolean"); put("description", "是否先加载全部历史") })
                    })
                },
            ) { args -> getCurrentChat(args.optInt("count", 50), args.optBoolean("loadAll", false)) },
            Tool(
                name = "get_current_chat_info",
                description = "获取当前打开聊天页的目标信息（peerUid / chatType / 名称），不需要额外参数。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
            ) { getCurrentChatInfo() },
            Tool(
                name = "send_message",
                description = "发送一条文本消息到指定 QQ 会话。需要 peerUid 与 chatType(1=私聊, 2=群聊)。" +
                    "目标 peerUid 通常来自 get_recent_chats 或 get_current_chat_info。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("peerUid", JSONObject().apply { put("type", "string"); put("description", "目标会话 uid") })
                        put("chatType", JSONObject().apply { put("type", "integer"); put("description", "1=私聊 2=群聊") })
                        put("text", JSONObject().apply { put("type", "string"); put("description", "要发送的文本内容") })
                    })
                    put("required", JSONArray().put("peerUid").put("chatType").put("text"))
                },
            ) { args ->
                sendMessage(
                    args.getString("peerUid"),
                    args.getInt("chatType"),
                    args.getString("text"),
                )
            },
            Tool(
                name = "get_self",
                description = "获取当前登录的 QQ 账号信息（QQ 号与 uid）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
            ) { getSelf() },
        )
    }

    fun invoke(name: String, args: JSONObject): String {
        val tool = all.firstOrNull { it.name == name } ?: throw ToolException("未知工具: $name")
        return tool.invoke(args)
    }

    // ── implementations ──────────────────────────────────────────────────────────────────────────

    /** Snapshot of every conversation the list page has rendered/heard about, newest first. */
    private fun getRecentChats(): String {
        val arr = JSONArray()
        val seen = mutableSetOf<String>()
        fun put(c: RecentContactInfo) {
            val uid = c.peerUid ?: return
            if (uid.isEmpty() || !seen.add(uid)) return
            arr.put(JSONObject().apply {
                put("peerUid", uid)
                put("peerUin", c.peerUin)
                put("chatType", c.chatType)
                put("name", c.remark?.takeIf { it.isNotEmpty() } ?: (c.peerName ?: uid))
                put("unread", c.unreadCnt)
                put("lastMessageTime", c.msgTime)
                put("abstract", abstractOf(c).take(120))
            })
        }
        RecentContacts.map.values.forEach { put(it.raw) }
        return JSONObject().apply {
            put("count", arr.length())
            put("chats", arr)
        }.toString()
    }

    private fun getCurrentChatInfo(): String {
        val c = CurrentContact
        if (c.peerUid.isEmpty()) throw ToolException("当前没有打开的聊天页")
        return JSONObject().apply {
            put("peerUid", c.peerUid)
            put("chatType", c.chatType)
            put("isGroup", c.chatType == ChatType.GROUP)
        }.toString()
    }

    /** Messages of the chat that is currently open on the watch (the [CurrentMsgList] mirror). */
    private fun getCurrentChat(count: Int, loadAll: Boolean): String {
        val n = count.coerceIn(1, 200)
        if (CurrentContact.peerUid.isEmpty()) throw ToolException("当前没有打开的聊天页")
        if (loadAll) {
            // Page in older history until the top. loadAll hops to the UI thread internally and
            // invokes onDone from there; we block the socket thread on a latch (bounded).
            val latch = CountDownLatch(1)
            CurrentMsgList.loadAll(shouldContinue = { CurrentContact.peerUid.isNotEmpty() }) {
                latch.countDown()
            }
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw ToolException("加载历史消息超时（会话可能已关闭）")
            }
        }
        // The mirror is mutated on the UI thread AND the Observable itself is swapped when the chat
        // changes (CurrentMsgList.Clear). Capture the Observable reference once and snapshot its
        // current list under that list's own monitor; whatever happens in between, our snapshot is
        // internally consistent (it may be from the previous chat — acceptable for a live mirror).
        val obs = CurrentMsgList.msgList
        val items = synchronized(obs.value) { obs.value.toList() }
        if (items.isEmpty()) throw ToolException("消息尚未加载，请先在手表上打开该聊天")
        val slice = items.takeLast(n)
        val arr = JSONArray()
        slice.forEach { item ->
            val rec = item.d
            arr.put(JSONObject().apply {
                put("sender", SummaryMessages.senderName(item))
                put("text", SummaryMessages.textOf(rec))
                put("msgId", rec.msgId)
                put("time", rec.msgTime)
            })
        }
        return JSONObject().apply {
            put("peerUid", CurrentContact.peerUid)
            put("chatType", CurrentContact.chatType)
            put("totalLoaded", items.size)
            put("messages", arr)
        }.toString()
    }

    /** Send a text message through the same kernel path our in-app send uses. */
    private fun sendMessage(peerUid: String, chatType: Int, text: String): String {
        if (peerUid.isBlank()) throw ToolException("peerUid 不能为空")
        if (chatType != ChatType.PRIVATE && chatType != ChatType.GROUP) {
            throw ToolException("chatType 必须是 1(私聊) 或 2(群聊)")
        }
        if (text.isBlank()) throw ToolException("消息内容不能为空")
        val element = MsgElement().apply {
            elementType = ElementType.TEXT
            textElement = TextElement().apply { content = text }
        }
        val latch = CountDownLatch(1)
        var ok = false
        var detail = ""
        MsgUtil.msgService.sendMsg(
            Contact(chatType, peerUid, ""), 0L, arrayListOf<MsgElement>(element),
            IOperateCallback { code, msg ->
                ok = code == 0
                detail = "code=$code msg=$msg"
                latch.countDown()
            },
        )
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw ToolException("发送超时")
        }
        if (!ok) throw ToolException("发送失败 ($detail)")
        return JSONObject().apply {
            put("ok", true)
            put("peerUid", peerUid)
            put("chatType", chatType)
            put("textLength", text.length)
        }.toString()
    }

    private fun getSelf(): String {
        val rt = runCatching { mqq.app.MobileQQ.getMobileQQ().peekAppRuntime() }.getOrNull()
            ?: throw ToolException("QQ 内核尚未登录")
        return JSONObject().apply {
            put("uin", runCatching { rt.currentUin }.getOrNull() ?: "")
            put("uid", runCatching { rt.currentUid }.getOrNull() ?: "")
        }.toString()
    }

    /** Human-readable last-message preview from the conversation's [RecentContactInfo.abstractContent]. */
    private fun abstractOf(c: RecentContactInfo): String =
        c.abstractContent?.joinToString("") { el ->
            when (el.elementType) {
                ElementType.TEXT -> el.content ?: ""
                ElementType.PIC -> "[图片]"
                ElementType.PTT -> "[语音]"
                ElementType.VIDEO -> "[视频]"
                ElementType.FILE -> "[文件]"
                else -> ""
            }
        } ?: ""
}
