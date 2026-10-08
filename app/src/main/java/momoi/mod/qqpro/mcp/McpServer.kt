package momoi.mod.qqpro.mcp

import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A minimal MCP (Model Context Protocol) HTTP server exposing QQ Max's chat data + send capability
 * to local AI clients (Claude Desktop, MCP inspectors, etc.).
 *
 * Transport: MCP "Streamable HTTP" (protocol 2025-03-26), hand-rolled on a raw [ServerSocket] — the
 * repo deliberately ships no HTTP framework (see api/Http.kt for the same style). Each POST carries
 * one JSON-RPC 2.0 object. Responses are spec-legal either way: SSE-framed (`event: message` +
 * `data:`) when the client's Accept header lists text/event-stream, plain application/json otherwise
 * (org.json emits single-line JSON, so the SSE data field is always newline-safe). JSON-RPC
 * notifications get 202 Accepted with no body. The optional GET server-initiated stream is not
 * offered (405) — this server has no server-initiated messages to push.
 *
 * Security: binds the loopback interface ONLY (the AI client runs on the paired phone/computer and
 * reaches the watch via `adb reverse tcp:PORT tcp:PORT` — same channel adb logcat already uses).
 * Every request must carry `Authorization: Bearer <token>` matching Settings.mcpToken, which is
 * generated once on first enable. bind 127.0.0.1 + bearer token = no other app on the watch can call
 * in (loopback is not reachable from other device apps).
 *
 * Protocol: implements `initialize`, `ping`, `tools/list`, `tools/call`, `notifications/initialized`
 * (acked, no response). Responses always include `jsonrpc: "2.0"` and echo `id` (null for parse
 * errors, per JSON-RPC). Tools run on the socket thread except where noted in [McpTools].
 */
object McpServer {

    /** Name/version reported in `initialize`'s serverInfo. */
    private const val SERVER_NAME = "qqmax-mcp"
    private const val PROTOCOL_VERSION = "2025-03-26"

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var socket: ServerSocket? = null

    val isRunning: Boolean get() = running.get()

    /** Effective listen port (settings value, clamped to user range). */
    val port: Int get() = Settings.mcpPort.value.coerceIn(1024, 65535)

    /** The bearer token, generated on first need so the settings page can always show one. */
    fun token(): String {
        var t = Settings.mcpToken.value
        if (t.isBlank()) {
            t = UUID.randomUUID().toString().replace("-", "")
            Settings.mcpToken.value = t
        }
        return t
    }

    /**
     * Start (or restart after a port/token change) the accept loop on a daemon thread. Safe to call
     * repeatedly — no-ops when already running with the configured port.
     */
    @Synchronized
    fun start() {
        if (!Settings.mcpEnabled.value) return
        if (running.get()) return
        running.set(true)
        thread = thread(name = "qqpro-mcp", isDaemon = true) {
            try {
                ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).let { socket = it }
                Utils.log("McpServer: listening on 127.0.0.1:$port")
                while (running.get()) {
                    val client = socket?.accept() ?: break
                    // One thread per connection; MCP clients keep few long-lived connections.
                    Thread({ handle(client) }, "qqpro-mcp-conn").apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Utils.log("McpServer: accept loop failed: ${e.message}")
            } finally {
                socket?.close()
                socket = null
                running.set(false)
                Utils.log("McpServer: stopped")
            }
        }
    }

    /** Stop the server and close any accepted connection's parent socket. Idempotent. */
    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    /** Restart after settings changed (port/token/enabled). */
    fun restart() {
        stop()
        start()
    }

    // ── per-connection handling ──────────────────────────────────────────────────────────────────

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30_000
            BufferedReader(InputStreamReader(client.inputStream, Charsets.UTF_8)).use { reader ->
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val httpMethod = parts.getOrElse(0) { "" }
                if (httpMethod.equals("GET", ignoreCase = true)) {
                    // Streamable HTTP's optional server-initiated SSE stream: we never push, so
                    // reject with 405 exactly as the spec instructs for unsupported GETs.
                    writeResponse(client, 405, "", sse = false)
                    return
                }
                if (parts.size < 2 || !httpMethod.equals("POST", ignoreCase = true)) {
                    writeResponse(client, 405, jsonRpcError(null, -32600, "仅支持 POST"), sse = false)
                    return
                }
                // Read headers; enforce the bearer token before touching the body.
                var auth = ""
                var contentLength = 0
                var acceptsSse = false
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val name = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    if (name == "authorization") auth = value
                    if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                    if (name == "accept" && value.lowercase().contains("text/event-stream")) acceptsSse = true
                }
                val expected = "Bearer ${token()}"
                if (auth != expected) {
                    Utils.log("McpServer: unauthorized request (${parts.getOrElse(1) { "?" }})")
                    writeResponse(client, 401, jsonRpcError(null, -32001, "未授权：Bearer 令牌不匹配"), sse = false)
                    return
                }
                if (contentLength <= 0 || contentLength > 1 shl 20) {
                    writeResponse(client, 400, jsonRpcError(null, -32600, "请求体为空或过大"), sse = false)
                    return
                }
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(body, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val response = dispatch(String(body, 0, read))
                // JSON-RPC notifications (no id) get 202 Accepted with no body per the MCP spec.
                if (response.isEmpty()) {
                    writeResponse(client, 202, "", sse = false)
                } else {
                    writeResponse(client, 200, response, sse = acceptsSse)
                }
            }
        } catch (e: Exception) {
            Utils.log("McpServer: connection failed: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** JSON-RPC dispatch for one request body. Always returns a JSON-RPC response object. */
    private fun dispatch(body: String): String {
        val req = runCatching { JSONObject(body) }.getOrElse {
            return jsonRpcError(null, -32700, "JSON 解析失败")
        }
        val id = req.opt("id")
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()
        val result: JSONObject = when (method) {
            "initialize" -> JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject().apply {
                    put("tools", JSONObject().apply { put("listChanged", false) })
                })
                put("serverInfo", JSONObject().apply {
                    put("name", SERVER_NAME)
                    put("version", momoi.mod.qqpro.BuildConfig.VERSION_NAME)
                })
            }
            "notifications/initialized" -> return "" // notification: no response body
            // @Suppress: a bare `JSONObject()` branch reads oddly but is the correct empty result.
            @Suppress("UseCheckOrError")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().apply {
                put("tools", JSONArray().apply {
                    McpTools.all.forEach { t ->
                        put(JSONObject().apply {
                            put("name", t.name)
                            put("description", t.description)
                            put("inputSchema", t.inputSchema)
                        })
                    }
                })
            }
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                try {
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", McpTools.invoke(name, args))
                            })
                        })
                        put("isError", false)
                    }
                } catch (e: McpTools.ToolException) {
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", e.message ?: "工具执行失败") })
                        })
                        put("isError", true)
                    }
                } catch (e: Exception) {
                    Utils.log("McpServer: tool '$name' crashed: $e")
                    JSONObject().apply {
                        put("content", JSONArray().apply {
                            put(JSONObject().apply { put("type", "text"); put("text", "工具内部错误: ${e.message}") })
                        })
                        put("isError", true)
                    }
                }
            }
            else -> return jsonRpcError(id, -32601, "未知方法: $method")
        }
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
        }.toString()
    }

    private fun jsonRpcError(id: Any?, code: Int, message: String): String = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", id ?: JSONObject.NULL)
        put("error", JSONObject().apply { put("code", code); put("message", message) })
    }.toString()

    /**
     * Write one HTTP response. With [sse] the body is wrapped in a single SSE event frame
     * (`event: message\ndata: <json>\n\n`) and served as text/event-stream — the JSON-RPC response
     * arrives in one flush, so this is framing, not streaming. Notifications ([body] empty) write
     * no body at all.
     */
    private fun writeResponse(client: Socket, status: Int, body: String, sse: Boolean) {
        val contentType = if (sse) "text/event-stream" else "application/json"
        val payload = when {
            body.isEmpty() -> ""
            sse -> "event: message\ndata: $body\n\n"
            else -> body
        }
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().use { out ->
            out.write((head + payload).toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        405 -> "Method Not Allowed"
        else -> "Error"
    }
}
