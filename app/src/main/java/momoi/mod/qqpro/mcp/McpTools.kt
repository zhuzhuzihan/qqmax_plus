package momoi.mod.qqpro.mcp

import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.TextElement
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.QQNT
import momoi.mod.qqpro.api.GroupBulletinApi
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.enums.ChatType
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.action.RecentContacts
import momoi.mod.qqpro.hook.qzone.QzoneFeedM3
import momoi.mod.qqpro.hook.summarize.SummaryMessages
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The MCP tool surface. Everything here reads the live state the hooks already maintain
 * ([RecentContacts] conversation cache, [CurrentContact]/[CurrentMsgList] open-chat mirror,
 * [QzoneFeedM3] loaded-feed mirror) or calls the QQ kernel through the same proven paths our
 * in-app features use ([MsgUtil.msgService.sendMsg] for text send — identical to
 * NotificationReply / InlineInput; [QQNT.Group.getMemberList] for members, [GroupBulletinApi]
 * for announcements).
 *
 * Threading: tool calls arrive on an MCP socket thread. Kernel calls with callbacks are bridged via
 * [CountDownLatch] (kernel callbacks fire on binder threads; GroupBulletinApi delivers on the UI
 * thread — both just release the latch); UI-dependent state ([CurrentMsgList.msgList] mirror) is
 * snapshotted under the list's own monitor, and history paging reuses [CurrentMsgList.loadAll],
 * which internally hops to the UI thread and calls back from there. Mirror-backed tools
 * (get_current_chat, search_current_chat, get_qzone_*) need the chat / space page opened on the
 * watch first.
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
            Tool(
                name = "search_current_chat",
                description = "在当前手表打开的聊天页已加载的消息中按关键词搜索，返回含关键词的消息（发送者/文本/msgId/时间）。" +
                    "参数：keyword 必填；count 最多返回条数(默认 50, 最大 200)；" +
                    "loadAll 为 true 时先向上加载全部历史再搜索(较慢)。需先在手表上打开该聊天。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("keyword", JSONObject().apply { put("type", "string"); put("description", "搜索关键词") })
                        put("count", JSONObject().apply { put("type", "integer"); put("description", "最多返回条数 1-200") })
                        put("loadAll", JSONObject().apply { put("type", "boolean"); put("description", "是否先加载全部历史") })
                    })
                    put("required", JSONArray().put("keyword"))
                },
            ) { args ->
                searchCurrentChat(args.getString("keyword"), args.optInt("count", 50), args.optBoolean("loadAll", false))
            },
            Tool(
                name = "get_group_members",
                description = "获取群聊成员列表（uid / QQ 号 / 昵称 / 备注 / 群名片）。" +
                    "参数：peerUid 群号（即 get_recent_chats 里 chatType=2 的会话 peerUid）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("peerUid", JSONObject().apply { put("type", "string"); put("description", "群号") })
                    })
                    put("required", JSONArray().put("peerUid"))
                },
            ) { args -> getGroupMembers(args.getString("peerUid")) },
            Tool(
                name = "get_group_bulletin",
                description = "获取群公告（仅返回当前有效的公告）。" +
                    "参数：peerUid 群号（即 get_recent_chats 里 chatType=2 的会话 peerUid）。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("peerUid", JSONObject().apply { put("type", "string"); put("description", "群号") })
                    })
                    put("required", JSONArray().put("peerUid"))
                },
            ) { args -> getGroupBulletin(args.getString("peerUid")) },
            Tool(
                name = "get_qzone_feed",
                description = "读取 QQ 空间动态列表（作者 / 时间 / 正文 / 点赞数 / 评论数）。" +
                    "返回的 index 可用作 get_qzone_comments 的参数。需先在手表上打开一次空间页面（数据来自已加载的列表）。" +
                    "参数：count 条数(默认 20, 最大 50)。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("count", JSONObject().apply { put("type", "integer"); put("description", "返回的动态条数 1-50") })
                    })
                },
            ) { args -> getQzoneFeed(args.optInt("count", 20)) },
            Tool(
                name = "get_qzone_comments",
                description = "读取一条空间动态的评论（含楼中楼回复）。参数：index 即 get_qzone_feed 返回的 index。" +
                    "需先在手表上打开一次空间页面。",
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("index", JSONObject().apply { put("type", "integer"); put("description", "动态序号") })
                    })
                    put("required", JSONArray().put("index"))
                },
            ) { args -> getQzoneComments(args.getInt("index")) },
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
                put("atType", when (val a = c.atType) { is Number -> a.toLong(); else -> 0L })
                put("muted", RecentContacts.isDisturb(uid))
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
        val items = snapshotCurrent(loadAll)
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

    /**
     * Snapshot the open chat's loaded messages, paging in older history first when [loadAll].
     * Shared by [getCurrentChat] and [searchCurrentChat].
     */
    private fun snapshotCurrent(loadAll: Boolean): List<WatchAIOMsgItem> {
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
        return items
    }

    /** Keyword search over the open chat's loaded messages (see [snapshotCurrent]). */
    private fun searchCurrentChat(keyword: String, count: Int, loadAll: Boolean): String {
        if (keyword.isBlank()) throw ToolException("keyword 不能为空")
        val n = count.coerceIn(1, 200)
        val items = snapshotCurrent(loadAll)
        val hits = items.filter { SummaryMessages.textOf(it.d).contains(keyword, ignoreCase = true) }.takeLast(n)
        val arr = JSONArray()
        hits.forEach { item ->
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
            put("keyword", keyword)
            put("totalLoaded", items.size)
            put("matchCount", hits.size)
            put("messages", arr)
        }.toString()
    }

    /** Group member list through the kernel (same path the @mention picker uses). */
    private fun getGroupMembers(peerUid: String): String {
        val groupId = peerUid.toLongOrNull() ?: throw ToolException("peerUid 不是有效的群号")
        val latch = CountDownLatch(1)
        var infos: Map<String, com.tencent.qqnt.kernel.nativeinterface.MemberInfo>? = null
        QQNT.Group.getMemberList(groupId) { res ->
            infos = res.infos
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw ToolException("获取群成员超时")
        }
        val arr = JSONArray()
        infos.orEmpty().values.forEach { m ->
            arr.put(JSONObject().apply {
                put("uid", m.uid)
                put("uin", m.uin)
                put("nick", m.nick ?: "")
                put("remark", m.remark ?: "")
                put("cardName", m.cardName ?: "")
            })
        }
        return JSONObject().apply {
            put("groupId", groupId)
            put("count", arr.length())
            put("members", arr)
        }.toString()
    }

    /** Active group announcements (the watch build only serves active ones — see GroupBulletinApi). */
    private fun getGroupBulletin(peerUid: String): String {
        val groupCode = peerUid.toLongOrNull() ?: throw ToolException("peerUid 不是有效的群号")
        val latch = CountDownLatch(1)
        var items: List<GroupBulletinApi.Item> = emptyList()
        // fetch delivers on the UI thread; we block the socket thread on a latch (bounded).
        GroupBulletinApi.fetch(groupCode) {
            items = it
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw ToolException("获取群公告超时")
        }
        val arr = JSONArray()
        items.forEach { a ->
            arr.put(JSONObject().apply {
                put("feedId", a.feedId)
                put("fromUid", a.fromUid)
                put("time", a.time)
                put("pinned", a.pinned)
                put("text", a.text)
                put("images", JSONArray().apply {
                    a.images.forEach { img -> put(JSONObject().apply { put("url", img.url) }) }
                })
            })
        }
        return JSONObject().apply {
            put("groupId", groupCode)
            put("count", arr.length())
            put("bulletins", arr)
        }.toString()
    }

    /**
     * QZone feed from the M3 adapter mirror ([QzoneFeedM3.snapshot]) — the native engine owns
     * loading, so the space page must have been opened on the watch at least once (same pattern as
     * [getCurrentChat] needing the chat open). [index] here feeds [getQzoneComments].
     */
    private fun getQzoneFeed(count: Int): String {
        val n = count.coerceIn(1, 50)
        val items = QzoneFeedM3.snapshot()
        if (items.isEmpty()) throw ToolException("空间动态尚未加载，请先在手表上打开一次空间页面")
        val arr = JSONArray()
        items.take(n).forEachIndexed { index, data ->
            arr.put(JSONObject().apply {
                put("index", index)
                put("feedKey", runCatching { data.feedCommInfo?.feedskey }.getOrNull() ?: "")
                put("authorUin", runCatching { data.user?.uin }.getOrNull() ?: 0L)
                put("authorNick", runCatching { data.user?.nickName }.getOrNull() ?: "")
                put("time", runCatching { data.feedCommInfo?.displayTimeString }.getOrNull() ?: "")
                put("text", runCatching { data.cellSummaryV2?.summary }.getOrNull() ?: "")
                put("likeCount", runCatching { data.likeInfo?.likeNum ?: 0 }.getOrDefault(0))
                put("liked", runCatching { data.likeInfo?.isLiked == true }.getOrDefault(false))
                put("commentCount", runCatching { data.cellCommentInfo?.c?.size ?: 0 }.getOrDefault(0))
            })
        }
        return JSONObject().apply {
            put("count", arr.length())
            put("totalLoaded", items.size)
            put("feeds", arr)
        }.toString()
    }

    /** Comments (+ nested replies) of one feed from the same mirror (see [getQzoneFeed]). */
    private fun getQzoneComments(index: Int): String {
        val items = QzoneFeedM3.snapshot()
        val data = items.getOrNull(index)
            ?: throw ToolException("index 越界，共 ${items.size} 条动态（需先在手表上打开一次空间页面）")
        val comments = runCatching { data.cellCommentInfo?.c }.getOrNull().orEmpty()
        val arr = JSONArray()
        comments.forEach { c ->
            val replies = JSONArray()
            runCatching { c.replies }.getOrNull().orEmpty().forEach { r ->
                replies.put(JSONObject().apply {
                    put("author", runCatching { r.user?.nickName }.getOrNull() ?: "")
                    put("replyTo", runCatching { r.targetUser?.nickName }.getOrNull() ?: "")
                    put("text", runCatching { r.content }.getOrNull() ?: "")
                })
            }
            arr.put(JSONObject().apply {
                put("author", runCatching { c.user?.nickName }.getOrNull() ?: "")
                put("text", runCatching { c.comment }.getOrNull() ?: "")
                put("replyCount", replies.length())
                put("replies", replies)
            })
        }
        return JSONObject().apply {
            put("index", index)
            put("count", arr.length())
            put("comments", arr)
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
