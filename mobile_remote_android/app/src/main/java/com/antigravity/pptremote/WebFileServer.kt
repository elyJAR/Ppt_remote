package com.antigravity.pptremote

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Lightweight HTTP server that lets any browser on the LAN browse, download,
 * upload and delete files from the phone's storage. Protected by a PIN.
 *
 * Uses only standard Java socket APIs — no sun.* or external dependencies.
 */
class WebFileServer(
    private val rootPath: String,
    private val pin: String,
    preferredPort: Int = 8686
) {
    var port: Int = preferredPort
        private set

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var activeSessionToken: String? = null
        private set

    private val executor = Executors.newFixedThreadPool(4)

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun start(): Boolean {
        if (serverSocket != null) return true
        var boundPort = port
        var ss: ServerSocket? = null
        for (attempt in 0..2) {
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(boundPort + attempt))
                boundPort += attempt
                break
            } catch (_: Exception) {
                ss = null
            }
        }
        if (ss == null) return false
        port = boundPort
        serverSocket = ss
        Log.i("WebFileServer", "Started on port $port, root=$rootPath")

        // Accept loop
        executor.submit {
            while (true) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                executor.submit { handleConnection(client) }
            }
        }
        return true
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        activeSessionToken = null
        Log.i("WebFileServer", "Stopped")
    }

    fun isRunning(): Boolean = serverSocket != null

    // ------------------------------------------------------------------
    // Raw HTTP connection handler
    // ------------------------------------------------------------------

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val ins = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()

            // Read request line
            val requestLine = readLine(ins) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawUri = parts[1]

            // Read headers
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(ins) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val questionMark = rawUri.indexOf('?')
            val path = if (questionMark >= 0) rawUri.substring(0, questionMark) else rawUri
            val query = if (questionMark >= 0) rawUri.substring(questionMark + 1) else ""

            val exchange = HttpCtx(method, path, query, headers, ins, out)

            when {
                path == "/" || path.startsWith("/?") -> handleRoot(exchange)
                path == "/login" -> handleLogin(exchange)
                path == "/api/files" -> handleList(exchange)
                path.startsWith("/download") -> handleDownload(exchange)
                path.startsWith("/upload") -> handleUpload(exchange)
                path.startsWith("/delete") -> handleDelete(exchange)
                else -> sendText(exchange, 404, "Not Found")
            }
        } catch (e: Exception) {
            Log.w("WebFileServer", "Connection error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Minimal HTTP context passed to each handler — mirrors com.sun.net.httpserver.HttpExchange */
    private inner class HttpCtx(
        val method: String,
        val path: String,
        val query: String,
        val requestHeaders: Map<String, String>,
        val bodyStream: InputStream,
        private val out: OutputStream
    ) {
        var responseCode: Int = 200
        val responseHeaders = mutableMapOf<String, MutableList<String>>()

        fun addResponseHeader(key: String, value: String) {
            responseHeaders.getOrPut(key) { mutableListOf() }.add(value)
        }

        fun sendResponse(status: Int, body: ByteArray) {
            responseCode = status
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $status ${statusText(status)}\r\n")
            sb.append("Connection: close\r\n")
            sb.append("Content-Length: ${body.size}\r\n")
            for ((k, values) in responseHeaders) {
                for (v in values) sb.append("$k: $v\r\n")
            }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
            out.write(body)
            out.flush()
        }

        fun sendResponseStream(status: Int, contentLength: Long, writeBody: (OutputStream) -> Unit) {
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $status ${statusText(status)}\r\n")
            sb.append("Connection: close\r\n")
            if (contentLength >= 0) sb.append("Content-Length: $contentLength\r\n")
            for ((k, values) in responseHeaders) {
                for (v in values) sb.append("$k: $v\r\n")
            }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
            writeBody(out)
            out.flush()
        }

        fun sendRedirect(location: String) {
            val sb = "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            out.write(sb.toByteArray(StandardCharsets.US_ASCII))
            out.flush()
        }

        private fun statusText(code: Int) = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
            302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 500 -> "Internal Server Error"
            else -> "Unknown"
        }
    }

    /** Read one CRLF-terminated line from a stream (no buffering past the line). */
    private fun readLine(ins: InputStream): String? {
        val buf = StringBuilder()
        var prev = -1
        while (true) {
            val b = ins.read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                // Remove the trailing CR
                if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
                return buf.toString()
            }
            buf.append(b.toChar())
            prev = b
        }
    }

    // ------------------------------------------------------------------
    // Auth helpers
    // ------------------------------------------------------------------

    private fun isPinRequired() = pin.isNotBlank()

    private fun sessionValid(exchange: HttpCtx): Boolean {
        if (!isPinRequired()) return true
        val token = activeSessionToken ?: return false
        val cookieHeader = exchange.requestHeaders["cookie"] ?: return false
        return cookieHeader.split(";").any { it.trim() == "session=$token" }
    }

    private fun requireAuth(exchange: HttpCtx): Boolean {
        if (sessionValid(exchange)) return true
        exchange.sendRedirect("/login")
        return false
    }

    // ------------------------------------------------------------------
    // Path safety
    // ------------------------------------------------------------------

    private fun safeResolve(rawPath: String?): File? {
        if (rawPath.isNullOrBlank()) return null
        val decoded = URLDecoder.decode(rawPath, "UTF-8")
        val resolved = File(rootPath, decoded).canonicalFile
        val root = File(rootPath).canonicalFile
        val rootPathWithSeparator = if (root.path.endsWith(File.separator)) root.path else root.path + File.separator
        return if (resolved.path == root.path || resolved.path.startsWith(rootPathWithSeparator)) resolved else null
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private fun handleRoot(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        val pathParam = exchange.query
            .split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
        val dir = if (!pathParam.isNullOrBlank()) safeResolve(pathParam) else File(rootPath).canonicalFile
        if (dir == null || !dir.exists() || !dir.isDirectory) {
            sendText(exchange, 400, "Invalid path")
            return
        }
        sendHtml(exchange, 200, buildBrowserHtml(dir))
    }

    private fun handleLogin(exchange: HttpCtx) {
        when (exchange.method) {
            "GET" -> sendHtml(exchange, 200, buildLoginHtml(false))
            "POST" -> {
                val contentLength = exchange.requestHeaders["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    exchange.bodyStream.readNBytes(contentLength).toString(StandardCharsets.UTF_8)
                } else ""
                val submitted = body.split("&")
                    .firstOrNull { it.startsWith("pin=") }
                    ?.removePrefix("pin=")
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                    .orEmpty()
                if (submitted == pin) {
                    val token = UUID.randomUUID().toString()
                    activeSessionToken = token
                    exchange.addResponseHeader("Set-Cookie", "session=$token; Path=/; HttpOnly")
                    exchange.sendRedirect("/")
                } else {
                    sendHtml(exchange, 401, buildLoginHtml(true))
                }
            }
            else -> sendText(exchange, 405, "Method Not Allowed")
        }
    }

    private fun handleList(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        val pathParam = exchange.query
            .split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
        val dir = if (!pathParam.isNullOrBlank()) safeResolve(pathParam) else File(rootPath).canonicalFile
        if (dir == null || !dir.exists() || !dir.isDirectory) {
            sendJson(exchange, 400, """{"error":"Invalid path"}""")
            return
        }
        val entries = (dir.listFiles() ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .joinToString(",") { f ->
                val size = if (f.isFile) f.length() else 0L
                val modified = f.lastModified()
                val encodedPath = URLEncoder.encode(
                    f.canonicalPath.removePrefix(File(rootPath).canonicalPath),
                    "UTF-8"
                )
                """{"name":${jsonStr(f.name)},"path":${jsonStr(encodedPath)},"isDir":${f.isDirectory},"size":$size,"modified":$modified}"""
            }
        sendJson(exchange, 200, "[$entries]")
    }

    private fun handleDownload(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        val pathParam = exchange.query
            .split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
        val file = safeResolve(pathParam)
        if (file == null || !file.exists() || !file.isFile) {
            sendText(exchange, 404, "File not found")
            return
        }
        val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        exchange.addResponseHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
        exchange.addResponseHeader("Content-Type", "application/octet-stream")
        exchange.sendResponseStream(200, file.length()) { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }

    private fun handleUpload(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        if (exchange.method != "POST") {
            sendText(exchange, 405, "Method Not Allowed")
            return
        }
        val pathParam = exchange.query
            .split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
        val dir = if (!pathParam.isNullOrBlank()) safeResolve(pathParam) else File(rootPath).canonicalFile
        if (dir == null || !dir.exists() || !dir.isDirectory) {
            sendJson(exchange, 400, """{"error":"Invalid target directory"}""")
            return
        }
        val contentType = exchange.requestHeaders["content-type"] ?: ""
        val boundaryMatch = Regex("boundary=(.+)").find(contentType)
        val boundary = boundaryMatch?.groupValues?.get(1)?.trim()
        if (boundary == null) {
            sendJson(exchange, 400, """{"error":"Not multipart"}""")
            return
        }
        try {
            val savedName = parseMultipartAndSave(exchange.bodyStream, boundary, dir)
            if (savedName != null) {
                sendJson(exchange, 200, """{"ok":true,"name":${jsonStr(savedName)}}""")
            } else {
                sendJson(exchange, 400, """{"error":"No file found or invalid upload format"}""")
            }
        } catch (e: Exception) {
            Log.e("WebFileServer", "Upload failed", e)
            sendJson(exchange, 500, """{"error":"Upload failed: ${e.message}"}""")
        }
    }

    private fun parseMultipartAndSave(input: InputStream, boundary: String, dir: File): String? {
        val separator = "\r\n--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
        val headerStream = java.io.ByteArrayOutputStream()
        var b: Int
        var c1 = -1; var c2 = -1; var c3 = -1; var c4 = -1
        while (true) {
            b = input.read()
            if (b == -1) break
            headerStream.write(b)
            c1 = c2; c2 = c3; c3 = c4; c4 = b
            if (c1 == 13 && c2 == 10 && c3 == 13 && c4 == 10) break
        }
        val headers = headerStream.toString("ISO-8859-1")
        val filenameMatch = Regex("""filename="([^"]+)"""").find(headers) ?: return null
        val filename = filenameMatch.groupValues[1]
        if (filename.isBlank()) return null
        val dest = File(dir, File(filename).name)

        val out = FileOutputStream(dest)
        try {
            val window = ByteArray(separator.size)
            var windowLen = 0
            while (windowLen < window.size) {
                val next = input.read()
                if (next == -1) break
                window[windowLen++] = next.toByte()
            }
            while (true) {
                if (windowLen == window.size && window.contentEquals(separator)) break
                val endSep = "--$boundary--".toByteArray(StandardCharsets.ISO_8859_1)
                if (windowLen >= endSep.size && window.sliceArray(0 until endSep.size).contentEquals(endSep)) break
                if (windowLen == 0) break
                out.write(window[0].toInt())
                System.arraycopy(window, 1, window, 0, windowLen - 1)
                val next = input.read()
                if (next == -1) {
                    windowLen--
                    for (i in 0 until windowLen) out.write(window[i].toInt())
                    break
                } else {
                    window[window.size - 1] = next.toByte()
                }
            }
        } finally {
            out.close()
        }
        return dest.name
    }

    private fun handleDelete(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        if (exchange.method != "DELETE") {
            sendText(exchange, 405, "Method Not Allowed")
            return
        }
        val pathParam = exchange.query
            .split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
        val file = safeResolve(pathParam)
        if (file == null || !file.exists()) {
            sendJson(exchange, 404, """{"error":"Not found"}""")
            return
        }
        if (file.canonicalPath == File(rootPath).canonicalPath) {
            sendJson(exchange, 400, """{"error":"Cannot delete root"}""")
            return
        }
        val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (deleted) sendJson(exchange, 200, """{"ok":true}""")
        else sendJson(exchange, 500, """{"error":"Delete failed"}""")
    }

    // ------------------------------------------------------------------
    // Response helpers
    // ------------------------------------------------------------------

    private fun sendRedirect(exchange: HttpCtx, location: String) = exchange.sendRedirect(location)

    private fun sendText(exchange: HttpCtx, status: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.addResponseHeader("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponse(status, bytes)
    }

    private fun sendHtml(exchange: HttpCtx, status: Int, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.addResponseHeader("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponse(status, bytes)
    }

    private fun sendJson(exchange: HttpCtx, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.addResponseHeader("Content-Type", "application/json; charset=utf-8")
        exchange.addResponseHeader("Access-Control-Allow-Origin", "*")
        exchange.sendResponse(status, bytes)
    }

    private fun jsonStr(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }

    // ------------------------------------------------------------------
    // HTML generation
    // ------------------------------------------------------------------

    private fun buildLoginHtml(error: Boolean): String {
        val errorMsg = if (error) "<p class='err'>❌ Incorrect PIN, please try again.</p>" else ""
        return """<!DOCTYPE html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PPT Remote — Unlock Files</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap" rel="stylesheet">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:radial-gradient(circle at top right, rgba(29, 78, 216, 0.12), transparent 45%), #0d1117;color:#e6edf3;font-family:'Outfit',system-ui,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh}
.card{background:rgba(22, 27, 34, 0.6);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);border:1px solid rgba(255, 255, 255, 0.08);border-radius:16px;padding:2.5rem 2rem;width:340px;text-align:center;box-shadow:0 16px 40px rgba(0,0,0,0.4)}
h2{color:#58a6ff;margin-bottom:0.5rem;font-weight:700;font-size:1.6rem;letter-spacing:-0.02em}
.subtitle{color:#8b949e;font-size:0.9rem;margin-bottom:1.5rem}
input{width:100%;padding:.75rem 1rem;background:rgba(13, 17, 23, 0.8);border:1px solid rgba(255, 255, 255, 0.1);border-radius:8px;color:#fff;font-size:1.25rem;margin:.5rem 0;text-align:center;letter-spacing:.4em;transition:border-color 0.2s, box-shadow 0.2s}
input:focus{outline:none;border-color:#58a6ff;box-shadow:0 0 0 3px rgba(88, 166, 255, 0.25)}
button{width:100%;padding:.8rem;background:linear-gradient(135deg, #1f6feb, #388bfd);border:none;border-radius:8px;color:#fff;font-size:1rem;font-weight:600;cursor:pointer;margin-top:.8rem;transition:transform 0.1s, filter 0.2s}
button:hover{filter:brightness(1.15)}
button:active{transform:scale(0.98)}
.err{color:#f85149;margin:.5rem 0;font-size:.9rem;background:rgba(248, 81, 73, 0.1);padding:0.5rem;border-radius:6px;border:1px solid rgba(248, 81, 73, 0.2)}
p.hint{color:#8b949e;font-size:.8rem;margin-top:1.5rem;line-height:1.4}
</style></head><body>
<div class="card">
<h2>📁 File Transfer</h2>
<p class="subtitle">Access files on this device</p>
$errorMsg
<form method="post" action="/login">
<input type="password" name="pin" placeholder="PIN" autofocus inputmode="numeric" maxlength="8">
<button type="submit">Unlock Files</button>
</form>
<p class="hint">Enter the access PIN displayed on the PPT Remote mobile app.</p>
</div></body></html>"""
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }

    private fun buildBrowserHtml(dir: File): String {
        val root = File(rootPath).canonicalFile
        val canonical = dir.canonicalFile
        val relPath = canonical.path.removePrefix(root.path).ifEmpty { "/" }

        val parts = relPath.split("/").filter { it.isNotEmpty() }
        val breadcrumbs = buildString {
            append("<a href='/?path=' class='bc-item'>📱 Storage</a>")
            var accumulated = ""
            parts.forEach { seg ->
                accumulated += "/$seg"
                val enc = URLEncoder.encode(accumulated, "UTF-8")
                append("<span class='bc-sep'>/</span><a href='/?path=$enc' class='bc-item'>$seg</a>")
            }
        }

        val encodedPath = URLEncoder.encode(relPath.ifEmpty { "/" }, "UTF-8")
        val files = (canonical.listFiles() ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })

        val rows = files.joinToString("\n") { f ->
            val fRelPath = f.canonicalPath.removePrefix(root.path)
            val enc = URLEncoder.encode(fRelPath, "UTF-8")
            val icon = if (f.isDirectory) "📁" else "📄"
            val size = if (f.isDirectory) "—" else formatSize(f.length())
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val date = sdf.format(Date(f.lastModified()))
            val nameCell = if (f.isDirectory)
                "<a href='/?path=$enc' class='folder-link'>${icon} ${f.name}</a>"
            else
                "<span class='file-name'>${icon} ${f.name}</span>"
            val actions = if (f.isDirectory) {
                "<button class='del' onclick=\"delItem('$enc','${f.name}',true)\">🗑 Delete</button>"
            } else {
                "<a class='btn' href='/download?path=$enc' download>⬇ Download</a> " +
                "<button class='del' onclick=\"delItem('$enc','${f.name}',false)\">🗑 Delete</button>"
            }
            "<tr><td>$nameCell</td><td>$size</td><td>$date</td><td class='act'>$actions</td></tr>"
        }

        val emptyRow = if (files.isEmpty()) "<tr><td colspan='4' class='empty'>📁 This folder is empty</td></tr>" else ""
        val parentLink = if (relPath != "/" && relPath.isNotEmpty()) {
            val parentRel = File(relPath).parent ?: "/"
            val enc = URLEncoder.encode(parentRel, "UTF-8")
            "<tr><td><a href='/?path=$enc' class='folder-link'>📁 ..</a></td><td>—</td><td>—</td><td></td></tr>"
        } else ""

        return buildBrowserHtmlPage(breadcrumbs, parentLink + rows + emptyRow, encodedPath)
    }

    @Suppress("LongMethod")
    private fun buildBrowserHtmlPage(breadcrumbs: String, rows: String, encodedPath: String): String = """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PPT Remote — Files</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap" rel="stylesheet">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:radial-gradient(circle at top right, rgba(29, 78, 216, 0.08), transparent 45%), #0a0d16;color:#e6edf3;font-family:'Outfit',system-ui,sans-serif;font-size:14px;min-height:100vh}
header{background:rgba(22, 27, 34, 0.8);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);border-bottom:1px solid rgba(255,255,255,0.05);padding:1rem 1.5rem;display:flex;align-items:center;gap:1.5rem;position:sticky;top:0;z-index:100;box-shadow:0 4px 20px rgba(0,0,0,0.15)}
header h1{color:#58a6ff;font-size:1.25rem;font-weight:700;letter-spacing:-0.01em;display:flex;align-items:center;gap:0.5rem}
.breadcrumb{color:#8b949e;font-size:.9rem;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;display:flex;align-items:center}
.bc-item{color:#58a6ff;text-decoration:none;transition:color 0.2s}
.bc-item:hover{color:#79c0ff;text-decoration:underline}
.bc-sep{margin:0 0.5rem;color:#30363d}
main{max-width:1100px;margin:2rem auto;padding:0 1.5rem}
.table-container{background:rgba(22, 27, 34, 0.4);border:1px solid rgba(255,255,255,0.08);border-radius:12px;overflow:hidden;box-shadow:0 8px 30px rgba(0,0,0,0.25)}
table{width:100%;border-collapse:collapse}
th{text-align:left;padding:1rem 1.25rem;color:#8b949e;border-bottom:1px solid rgba(255,255,255,0.08);font-weight:600;font-size:0.85rem;text-transform:uppercase;letter-spacing:0.05em}
td{padding:0.85rem 1.25rem;border-bottom:1px solid rgba(255,255,255,0.04);vertical-align:middle}
tr:last-child td{border-bottom:none}
tr:hover td{background:rgba(255,255,255,0.02)}
td a.folder-link{color:#58a6ff;text-decoration:none;font-weight:500;transition:color 0.2s;display:inline-flex;align-items:center;gap:0.35rem}
td a.folder-link:hover{color:#79c0ff;text-decoration:underline}
.file-name{color:#e6edf3;font-weight:450;display:inline-flex;align-items:center;gap:0.35rem}
.act{white-space:nowrap;text-align:right}
.btn,.del{display:inline-block;padding:.4rem .8rem;border-radius:6px;font-size:.85rem;font-weight:500;cursor:pointer;text-decoration:none;border:none;transition:all 0.2s}
.btn{background:linear-gradient(135deg, #238636, #2ea043);color:#fff}
.btn:hover{filter:brightness(1.15);transform:translateY(-1px)}
.btn:active{transform:translateY(0)}
.del{background:rgba(248, 81, 73, 0.05);color:#f85149;border:1px solid rgba(248, 81, 73, 0.15);margin-left:0.35rem}
.del:hover{background:rgba(248, 81, 73, 0.15);border-color:rgba(248, 81, 73, 0.4)}
.del:active{transform:scale(0.98)}
.empty{color:#8b949e;text-align:center;padding:3rem 1rem;font-size:1rem}
.upload-area{margin-top:2rem;background:rgba(22, 27, 34, 0.35);border:2px dashed rgba(88, 166, 255, 0.25);border-radius:12px;padding:2rem;text-align:center;transition:all 0.3s ease}
.upload-area.dragover{border-color:#58a6ff;background:rgba(88, 166, 255, 0.06);box-shadow:inset 0 0 16px rgba(88,166,255,0.05)}
.upload-area h3{color:#e6edf3;margin-bottom:1rem;font-size:1.1rem;font-weight:600}
.upload-row{display:flex;justify-content:center;align-items:center;gap:.75rem;flex-wrap:wrap;max-width:600px;margin:0 auto}
.file-input-wrapper{position:relative;overflow:hidden;display:inline-block}
.file-input-btn{border:1px solid rgba(255,255,255,0.1);background:rgba(13,17,23,0.6);color:#e6edf3;padding:0.5rem 1rem;border-radius:6px;font-size:0.9rem;font-weight:500;cursor:pointer;transition:border-color 0.2s}
.file-input-wrapper:hover .file-input-btn{border-color:rgba(255,255,255,0.25)}
.file-input-wrapper input[type=file]{font-size:100px;position:absolute;left:0;top:0;opacity:0;cursor:pointer}
.upload-btn{padding:.5rem 1.25rem;background:linear-gradient(135deg, #1f6feb, #388bfd);border:none;border-radius:6px;color:#fff;cursor:pointer;font-size:.9rem;font-weight:600;transition:all 0.2s}
.upload-btn:hover{filter:brightness(1.15);transform:translateY(-1px)}
.upload-btn:active{transform:translateY(0)}
.progress{margin-top:1rem;font-size:.9rem;color:#8b949e;font-weight:500}
.toast{position:fixed;bottom:2rem;right:2rem;background:#238636;color:#fff;padding:.75rem 1.5rem;border-radius:8px;display:none;font-size:.9rem;font-weight:600;z-index:9999;box-shadow:0 8px 24px rgba(0,0,0,0.3);animation:slideUp 0.3s ease}
@keyframes slideUp { from{transform:translateY(20px);opacity:0} to{transform:translateY(0);opacity:1} }
</style></head>
<body>
<header>
<h1><span>📁</span> PPT Remote Files</h1>
<span class="breadcrumb">$breadcrumbs</span>
</header>
<main>
<div class="table-container">
<table>
<thead><tr><th>Name</th><th>Size</th><th>Modified</th><th></th></tr></thead>
<tbody id="rows">$rows</tbody>
</table>
</div>
<div class="upload-area" id="dropZone">
<h3>Upload Files to this Folder</h3>
<div class="upload-row">
<div class="file-input-wrapper">
<button class="file-input-btn">📂 Choose Files</button>
<input type="file" id="fileInput" multiple onchange="updateSelectedFilesText()">
</div>
<button class="upload-btn" onclick="uploadFiles()">⬆ Upload</button>
</div>
<div class="progress" id="prog">Drag &amp; drop files here or click Choose Files</div>
</div>
</main>
<div class="toast" id="toast"></div>
<script>
var currentPath = decodeURIComponent("$encodedPath");
function toast(msg,ok){
  var t=document.getElementById("toast");
  t.textContent=msg;t.style.background=ok?"#238636":"#da3633";t.style.display="block";
  setTimeout(function(){t.style.display="none"},2500);
}
function updateSelectedFilesText() {
  var inp = document.getElementById("fileInput");
  var prog = document.getElementById("prog");
  if(inp.files.length) {
    prog.textContent = inp.files.length + " file(s) selected - click Upload to send";
  }
}
function delItem(enc,name,isDir){
  var what=isDir?"folder":"file";
  if(!confirm("Delete "+what+" \""+name+"\"? This cannot be undone."))return;
  fetch("/delete?path="+enc,{method:"DELETE"})
    .then(function(r){return r.json()})
    .then(function(j){if(j.ok){toast("Deleted: "+name,true);setTimeout(function(){location.reload()},600)}else{toast("Error: "+j.error,false)}})
    .catch(function(){toast("Delete failed",false)});
}
function uploadFiles(){
  var inp=document.getElementById("fileInput");
  var prog=document.getElementById("prog");
  if(!inp.files.length){toast("Select at least one file",false);return;}
  var files=Array.from(inp.files);var done=0;
  prog.textContent="Uploading "+files.length+" file(s)...";
  files.forEach(function(file){
    var fd=new FormData();fd.append("file",file);
    fetch("/upload?path="+encodeURIComponent(currentPath),{method:"POST",body:fd})
      .then(function(r){return r.json()})
      .then(function(j){
        done++;
        if(j.ok){prog.textContent="Uploaded "+done+"/"+files.length+" — "+j.name;}
        else{prog.textContent="Error on "+file.name+": "+j.error;}
        if(done===files.length){toast("Upload complete",true);setTimeout(function(){location.reload()},800);}
      })
      .catch(function(){done++;prog.textContent="Upload failed for "+file.name;});
  });
}
var dropZone = document.getElementById("dropZone");
var fileInput = document.getElementById("fileInput");
['dragenter','dragover'].forEach(function(ev){
  dropZone.addEventListener(ev,function(e){e.preventDefault();dropZone.classList.add('dragover');},false);
});
['dragleave','drop'].forEach(function(ev){
  dropZone.addEventListener(ev,function(e){e.preventDefault();dropZone.classList.remove('dragover');},false);
});
dropZone.addEventListener('drop',function(e){
  var dt=e.dataTransfer;fileInput.files=dt.files;updateSelectedFilesText();
},false);
</script>
</body></html>"""
}
