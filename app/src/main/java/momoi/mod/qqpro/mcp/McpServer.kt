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
 * Transport: MCP "Streamable HTTP" subset, hand-rolled on a raw [ServerSocket] — the repo deliberately
 * ships no HTTP framework (see api/Http.kt for the same style). Requests are single JSON-RPC 2.0
 * objects (POST, Content-Type: application/json); responses are one JSON object (no SSE streaming —
 * our tools are request/response only). Clients that only need tools work fine against this.
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
                if (parts.size < 2 || !parts[0].equals("POST", ignoreCase = true)) {
                    writeResponse(client, 405, jsonRpcError(null, -32600, "仅支持 POST"))
                    return
                }
                // Read headers; enforce the bearer token before touching the body.
                var auth = ""
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val name = line.substring(0, idx).trim().lowercase()
                    val value = line.substring(idx + 1).trim()
                    if (name == "authorization") auth = value
                    if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                }
                val expected = "Bearer ${token()}"
                if (auth != expected) {
                    Utils.log("McpServer: unauthorized request (${parts[1]})")
                    writeResponse(client, 401, jsonRpcError(null, -32001, "未授权：Bearer 令牌不匹配"))
                    return
                }
                if (contentLength <= 0 || contentLength > 1 shl 20) {
                    writeResponse(client, 400, jsonRpcError(null, -32600, "请求体为空或过大"))
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
                    writeResponse(client, 202, "")
                } else {
                    writeResponse(client, 200, response)
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

    /** Write one HTTP response; `body` empty means 204-style no content (JSON-RPC notification ack). */
    private fun writeResponse(client: Socket, status: Int, body: String) {
        val payload = if (body.isEmpty()) "" else body
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: application/json\r\n")
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
