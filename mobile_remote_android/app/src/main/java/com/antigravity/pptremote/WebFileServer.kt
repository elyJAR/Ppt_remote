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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lightweight HTTP server that lets any browser on the LAN browse, download,
 * upload and delete files from the phone's storage. Protected by a PIN.
 *
 * Uses only standard Java socket APIs ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â no sun.* or external dependencies.
 */
class WebFileServer(
    private val rootPath: String,
    private val pin: String,
    preferredPort: Int = 8686,
    private val allowedRoots: List<String> = listOf(rootPath)
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
                path == "/download-zip" -> handleDownloadZip(exchange)
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

    /** Minimal HTTP context passed to each handler ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â mirrors com.sun.net.httpserver.HttpExchange */
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
            val sb = StringBuilder()
            sb.append("HTTP/1.1 302 Found\r\n")
            sb.append("Location: $location\r\n")
            sb.append("Content-Length: 0\r\n")
            sb.append("Connection: close\r\n")
            for ((k, values) in responseHeaders) {
                for (v in values) sb.append("$k: $v\r\n")
            }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
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
        val resolved = if (decoded.startsWith("/")) {
            File(decoded).canonicalFile
        } else {
            File(rootPath, decoded).canonicalFile
        }
        for (allowed in allowedRoots) {
            val rootFile = File(allowed).canonicalFile
            val rootPathWithSeparator = if (rootFile.path.endsWith(File.separator)) rootFile.path else rootFile.path + File.separator
            if (resolved.path == rootFile.path || resolved.path.startsWith(rootPathWithSeparator)) {
                return resolved
            }
        }
        return null
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
                    val buf = ByteArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = exchange.bodyStream.read(buf, offset, contentLength - offset)
                        if (read == -1) break
                        offset += read
                    }
                    buf.toString(StandardCharsets.UTF_8)
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

    private fun handleDownloadZip(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        val paths = exchange.query
            .split("&")
            .filter { it.startsWith("path=") }
            .map { URLDecoder.decode(it.removePrefix("path="), "UTF-8") }

        val files = paths.mapNotNull { safeResolve(it) }.filter { it.exists() }
        if (files.isEmpty()) {
            sendText(exchange, 404, "No files found")
            return
        }

        val zipName = if (files.size == 1) {
            "${files[0].name}.zip"
        } else {
            "ppt_remote_files.zip"
        }
        val encodedZipName = URLEncoder.encode(zipName, "UTF-8").replace("+", "%20")
        exchange.addResponseHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedZipName")
        exchange.addResponseHeader("Content-Type", "application/zip")

        exchange.sendResponseStream(200, -1) { out ->
            ZipOutputStream(out).use { zos ->
                files.forEach { file ->
                    addToZip(file, "", zos)
                }
            }
        }
    }

    private fun addToZip(file: File, parentPath: String, zos: ZipOutputStream) {
        val entryPath = if (parentPath.isEmpty()) file.name else "$parentPath/${file.name}"
        if (file.isDirectory) {
            val children = file.listFiles() ?: emptyArray()
            if (children.isEmpty()) {
                val entry = ZipEntry("$entryPath/")
                zos.putNextEntry(entry)
                zos.closeEntry()
            } else {
                children.forEach { child ->
                    addToZip(child, entryPath, zos)
                }
            }
        } else {
            val entry = ZipEntry(entryPath)
            zos.putNextEntry(entry)
            file.inputStream().use { input ->
                input.copyTo(zos)
            }
            zos.closeEntry()
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
        val boundaryStr = "\r\n--$boundary"
        val boundaryBytes = boundaryStr.toByteArray(StandardCharsets.ISO_8859_1)
        val bis = java.io.BufferedInputStream(input, 64 * 1024)

        val headerStream = java.io.ByteArrayOutputStream()
        var b: Int
        var c1 = -1; var c2 = -1; var c3 = -1; var c4 = -1
        while (true) {
            b = bis.read()
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
            val bufferSize = 64 * 1024
            val buffer = ByteArray(bufferSize + boundaryBytes.size)
            var bufferLen = 0

            while (true) {
                val space = bufferSize - bufferLen
                if (space > 0) {
                    val read = bis.read(buffer, bufferLen, space)
                    if (read > 0) {
                        bufferLen += read
                    } else if (read == -1 && bufferLen == 0) {
                        break
                    }
                }

                if (bufferLen == 0) break

                var boundaryIdx = -1
                val searchLimit = bufferLen - boundaryBytes.size
                for (i in 0..searchLimit) {
                    var match = true
                    for (j in boundaryBytes.indices) {
                        if (buffer[i + j] != boundaryBytes[j]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        boundaryIdx = i
                        break
                    }
                }

                if (boundaryIdx != -1) {
                    if (boundaryIdx > 0) {
                        out.write(buffer, 0, boundaryIdx)
                    }
                    break
                } else {
                    val safeWriteLen = bufferLen - boundaryBytes.size + 1
                    if (safeWriteLen > 0) {
                        out.write(buffer, 0, safeWriteLen)
                        val remaining = bufferLen - safeWriteLen
                        System.arraycopy(buffer, safeWriteLen, buffer, 0, remaining)
                        bufferLen = remaining
                    } else {
                        out.write(buffer, 0, bufferLen)
                        break
                    }
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
    // ------------------------------------------------------------
    private fun buildLoginHtml(error: Boolean): String {
        val errorMsg = if (error) "<p class='err'>&#x274C; Incorrect PIN, please try again.</p>" else ""
        return """<!DOCTYPE html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PPT Remote &mdash; Unlock Files</title>
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
<h2>&#x1F4C1; File Transfer</h2>
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

    private fun fileIcon(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg","jpeg","png","gif","webp","bmp","heic","svg" -> "&#x1F5BC;"
            "mp4","mkv","avi","mov","webm","3gp" -> "&#x1F3AC;"
            "mp3","wav","flac","aac","ogg","m4a","opus" -> "&#x1F3B5;"
            "pdf" -> "&#x1F4D5;"
            "zip","rar","7z","tar","gz","bz2" -> "&#x1F5DC;"
            "apk" -> "&#x1F4E6;"
            "doc","docx" -> "&#x1F4DD;"
            "xls","xlsx","csv" -> "&#x1F4CA;"
            "ppt","pptx" -> "&#x1F4CA;"
            "txt","md","log","json","xml","yaml","yml" -> "&#x1F4C4;"
            else -> "&#x1F4C4;"
        }
    }

    private fun getDriveName(path: String): String {
        return if (path.contains("emulated") || path.endsWith("/0")) {
            "&#x1F4F1; Internal Storage"
        } else {
            "&#x1F4BE; SD Card"
        }
    }

    private fun buildBrowserHtml(dir: File): String {
        val canonical = dir.canonicalFile
        val activeRootPath = allowedRoots.firstOrNull { canonical.path.startsWith(File(it).canonicalPath) } ?: rootPath
        val activeRootDisplay = getDriveName(activeRootPath).replace("&#x1F4F1; ", "").replace("&#x1F4BE; ", "")
        val relPath = canonical.path.removePrefix(File(activeRootPath).canonicalPath).ifEmpty { "/" }

        val parts = relPath.split("/").filter { it.isNotEmpty() }
        val breadcrumbs = buildString {
            val rootEnc = URLEncoder.encode(activeRootPath, "UTF-8")
            append("<a href='/?path=$rootEnc' class='bc-item'>&#x1F4F1; $activeRootDisplay</a>")
            var accumulated = activeRootPath
            parts.forEach { seg ->
                accumulated = File(accumulated, seg).path
                val enc = URLEncoder.encode(accumulated, "UTF-8")
                append("<span class='bc-sep'>/</span><a href='/?path=$enc' class='bc-item'>$seg</a>")
            }
        }

        val encodedPath = URLEncoder.encode(canonical.path, "UTF-8")
        val files = (canonical.listFiles() ?: emptyArray())
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })

        val tableRows = StringBuilder()
        val gridItems = StringBuilder()

        files.forEach { f ->
            val enc = URLEncoder.encode(f.canonicalPath, "UTF-8")
            val icon = if (f.isDirectory) "&#x1F4C1;" else fileIcon(f.name)
            val size = if (f.isDirectory) "&mdash;" else formatSize(f.length())
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val date = sdf.format(Date(f.lastModified()))
            val safeName = f.name.replace("\\", "\\\\").replace("'", "\\'")
            val type = if (f.isDirectory) "folder" else "file"

            // Table row (list view)
            val nameCell = if (f.isDirectory)
                "<a href='/?path=$enc' class='folder-link'>$icon ${f.name}</a>"
            else
                "<span class='file-name'>$icon ${f.name}</span>"
            val actions = if (f.isDirectory)
                "<a class='btn' href='/download-zip?path=$enc' download>&#x2B07; Download</a><button class='del' onclick=\"delItem('$enc','$safeName',true)\">&#x1F5D1;</button>"
            else
                "<a class='btn' href='/download?path=$enc' download>&#x2B07; Download</a><button class='del' onclick=\"delItem('$enc','$safeName',false)\">&#x1F5D1;</button>"
            tableRows.append("<tr><td class='cb-col'><input type='checkbox' class='item-cb' data-path='$enc' data-name='$safeName' data-type='$type' onchange='updateActionBar()'></td><td>$nameCell</td><td>$size</td><td>$date</td><td class='act'>$actions</td></tr>\n")

            // Grid item
            val gridClick = if (f.isDirectory) "if(!event.target.matches('input,a,button')){window.location='/?path=$enc'}" else ""
            val gridDownload = "<a class='btn-sm' href='${if (f.isDirectory) "/download-zip?path=" else "/download?path="}$enc' download onclick='event.stopPropagation()'>&#x2B07;</a><button class='del-sm' onclick=\"event.stopPropagation();delItem('$enc','$safeName',${f.isDirectory})\">&#x1F5D1;</button>"
            gridItems.append("<div class='grid-item $type' onclick=\"$gridClick\"><input type='checkbox' class='item-cb grid-cb' data-path='$enc' data-name='$safeName' data-type='$type' onchange='event.stopPropagation();updateActionBar()'><div class='grid-icon'>$icon</div><div class='grid-name' title='${f.name}'>${f.name}</div><div class='grid-size'>$size</div><div class='grid-actions'>$gridDownload</div></div>\n")
        }

        val emptyRow = if (files.isEmpty()) "<tr><td></td><td colspan='4' class='empty'>&#x1F4C1; This folder is empty</td></tr>" else ""
        val emptyGrid = if (files.isEmpty()) "<div class='grid-empty'>&#x1F4C1; This folder is empty</div>" else ""

        val parentLink = if (canonical.path != File(activeRootPath).canonicalPath) {
            val parentFile = canonical.parentFile ?: File(activeRootPath)
            val enc = URLEncoder.encode(parentFile.canonicalPath, "UTF-8")
            "<tr><td class='cb-col'></td><td><a href='/?path=$enc' class='folder-link'>&#x1F4C1; ..</a></td><td>&mdash;</td><td>&mdash;</td><td></td></tr>\n"
        } else ""

        val rootDisplay = activeRootPath.let { if (it.length > 38) "&hellip;${it.takeLast(38)}" else it }

        val drivesHtml = if (allowedRoots.size > 1) {
            buildString {
                append("<div class='drives-bar'>")
                allowedRoots.forEach { path ->
                    val driveName = getDriveName(path)
                    val enc = URLEncoder.encode(path, "UTF-8")
                    val isCurrent = canonical.path.startsWith(File(path).canonicalPath)
                    val activeClass = if (isCurrent) "active-drive" else ""
                    append("<a href='/?path=$enc' class='drive-btn $activeClass'>$driveName</a>")
                }
                append("</div>")
            }
        } else ""

        return buildBrowserHtmlPage(breadcrumbs, parentLink + tableRows + emptyRow, gridItems.toString() + emptyGrid, encodedPath, rootDisplay, drivesHtml)
    }

    @Suppress("LongMethod")
    private fun buildBrowserHtmlPage(
        breadcrumbs: String,
        rows: String,
        gridItems: String,
        encodedPath: String,
        rootDisplay: String,
        drivesHtml: String
    ): String = """<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PPT Remote &mdash; Files</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap" rel="stylesheet">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:radial-gradient(circle at top right, rgba(29,78,216,0.08), transparent 45%), #0a0d16;color:#e6edf3;font-family:'Outfit',system-ui,sans-serif;font-size:14px;min-height:100vh}
header{background:rgba(22,27,34,0.85);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);border-bottom:1px solid rgba(255,255,255,0.05);padding:0.85rem 1.5rem;display:flex;align-items:center;gap:1rem;position:sticky;top:0;z-index:100;box-shadow:0 4px 20px rgba(0,0,0,0.15);flex-wrap:wrap}
header h1{color:#58a6ff;font-size:1.15rem;font-weight:700;letter-spacing:-0.01em;display:flex;align-items:center;gap:0.4rem;white-space:nowrap}
.breadcrumb{color:#8b949e;font-size:.85rem;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;display:flex;align-items:center;min-width:0}
.bc-item{color:#58a6ff;text-decoration:none;transition:color 0.2s;flex-shrink:0}
.bc-item:hover{color:#79c0ff;text-decoration:underline}
.bc-sep{margin:0 0.4rem;color:#30363d;flex-shrink:0}
.root-badge{font-size:0.7rem;color:#8b949e;background:rgba(255,255,255,0.04);border:1px solid rgba(255,255,255,0.08);border-radius:5px;padding:2px 7px;font-family:monospace;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:220px;cursor:default}
.view-toggle{display:flex;gap:3px;background:rgba(255,255,255,0.05);border-radius:8px;padding:3px;flex-shrink:0}
.view-btn{background:transparent;border:none;color:#8b949e;padding:5px 9px;border-radius:5px;cursor:pointer;font-size:1rem;transition:all 0.2s;line-height:1}
.view-btn.active{background:rgba(88,166,255,0.15);color:#58a6ff}
main{max-width:1200px;margin:1.5rem auto;padding:0 1.5rem 6rem}
/* Drives Bar */
.drives-bar{display:flex;gap:0.6rem;margin-bottom:1.25rem;flex-wrap:wrap}
.drive-btn{background:rgba(22,27,34,0.6);border:1px solid rgba(255,255,255,0.08);color:#8b949e;padding:0.5rem 0.9rem;border-radius:8px;text-decoration:none;font-size:0.85rem;font-weight:500;transition:all 0.2s}
.drive-btn:hover{background:rgba(255,255,255,0.1);color:#e6edf3;border-color:rgba(255,255,255,0.15)}
.drive-btn.active-drive{background:rgba(88,166,255,0.12);border-color:#58a6ff;color:#58a6ff}
.table-container{background:rgba(22,27,34,0.4);border:1px solid rgba(255,255,255,0.08);border-radius:12px;overflow:hidden;box-shadow:0 8px 30px rgba(0,0,0,0.25)}
table{width:100%;border-collapse:collapse}
th{text-align:left;padding:0.9rem 1.1rem;color:#8b949e;border-bottom:1px solid rgba(255,255,255,0.08);font-weight:600;font-size:0.82rem;text-transform:uppercase;letter-spacing:0.05em}
td{padding:0.75rem 1.1rem;border-bottom:1px solid rgba(255,255,255,0.04);vertical-align:middle}
tr:last-child td{border-bottom:none}
tr:hover td{background:rgba(255,255,255,0.02)}
td a.folder-link{color:#58a6ff;text-decoration:none;font-weight:500;transition:color 0.2s;display:inline-flex;align-items:center;gap:0.3rem}
td a.folder-link:hover{color:#79c0ff;text-decoration:underline}
.file-name{color:#e6edf3;font-weight:450;display:inline-flex;align-items:center;gap:0.3rem}
.act{white-space:nowrap;text-align:right;display:flex;gap:0.4rem;justify-content:flex-end;align-items:center}
.btn,.del{display:inline-flex;align-items:center;padding:.35rem .7rem;border-radius:6px;font-size:.82rem;font-weight:500;cursor:pointer;text-decoration:none;border:none;transition:all 0.2s;white-space:nowrap}
.btn{background:linear-gradient(135deg,#238636,#2ea043);color:#fff}
.btn:hover{filter:brightness(1.15);transform:translateY(-1px)}
.del{background:rgba(248,81,73,0.06);color:#f85149;border:1px solid rgba(248,81,73,0.15)}
.del:hover{background:rgba(248,81,73,0.15);border-color:rgba(248,81,73,0.4)}
.empty{color:#8b949e;text-align:center;padding:3rem 1rem;font-size:1rem}
.cb-col{width:44px;text-align:center}
.item-cb{width:16px;height:16px;cursor:pointer;accent-color:#58a6ff;vertical-align:middle}
/* Grid view */
.grid-container{display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:0.85rem;padding:1.25rem}
.grid-item{position:relative;background:rgba(22,27,34,0.5);border:1px solid rgba(255,255,255,0.07);border-radius:10px;padding:1rem 0.75rem 0.75rem;text-align:center;cursor:pointer;transition:all 0.2s;user-select:none}
.grid-item:hover{background:rgba(88,166,255,0.07);border-color:rgba(88,166,255,0.25);transform:translateY(-2px);box-shadow:0 4px 16px rgba(0,0,0,0.25)}
.grid-cb{position:absolute;top:7px;left:7px;opacity:0;transition:opacity 0.15s;z-index:2}
.grid-item:hover .grid-cb,.grid-cb:checked{opacity:1}
.grid-icon{font-size:2.4rem;line-height:1;margin-bottom:0.5rem;display:block;pointer-events:none}
.grid-name{font-size:0.78rem;color:#e6edf3;word-break:break-all;line-height:1.3;max-height:2.6em;overflow:hidden;pointer-events:none}
.grid-size{font-size:0.72rem;color:#8b949e;margin-top:0.3rem;pointer-events:none}
.grid-actions{margin-top:0.5rem;display:flex;justify-content:center;gap:0.4rem}
.btn-sm,.del-sm{font-size:0.75rem;padding:0.25rem 0.55rem;border-radius:5px;cursor:pointer;text-decoration:none;border:none;transition:all 0.2s}
.btn-sm{background:linear-gradient(135deg,#238636,#2ea043);color:#fff;display:inline-flex;align-items:center}
.btn-sm:hover{filter:brightness(1.15)}
.del-sm{background:rgba(248,81,73,0.08);color:#f85149;border:1px solid rgba(248,81,73,0.2)}
.del-sm:hover{background:rgba(248,81,73,0.18)}
.grid-empty{color:#8b949e;text-align:center;padding:3rem;font-size:1rem;grid-column:1/-1}
/* Action bar */
.action-bar{position:fixed;bottom:1.5rem;left:50%;transform:translateX(-50%);background:rgba(15,20,30,0.97);backdrop-filter:blur(20px);border:1px solid rgba(88,166,255,0.35);border-radius:14px;padding:0.75rem 1.25rem;display:none;align-items:center;gap:1rem;z-index:500;box-shadow:0 8px 40px rgba(0,0,0,0.5);white-space:nowrap;animation:slideUp 0.25s ease}
.action-bar.visible{display:flex}
.sel-count{color:#58a6ff;font-weight:600;font-size:0.9rem;min-width:80px}
.bar-btn{display:inline-flex;align-items:center;gap:0.35rem;padding:0.45rem 0.9rem;border-radius:7px;font-size:0.85rem;font-weight:600;cursor:pointer;border:none;transition:all 0.2s}
.bar-dl{background:linear-gradient(135deg,#1f6feb,#388bfd);color:#fff}
.bar-dl:hover{filter:brightness(1.1)}
.bar-del{background:rgba(248,81,73,0.1);color:#f85149;border:1px solid rgba(248,81,73,0.25)}
.bar-del:hover{background:rgba(248,81,73,0.22)}
.bar-clear{background:transparent;color:#8b949e;border:1px solid rgba(255,255,255,0.1)}
.bar-clear:hover{color:#e6edf3;border-color:rgba(255,255,255,0.3)}
/* Upload area */
.upload-area{margin-top:1.5rem;background:rgba(22,27,34,0.35);border:2px dashed rgba(88,166,255,0.25);border-radius:12px;padding:1.75rem;text-align:center;transition:all 0.3s ease}
.upload-area.dragover{border-color:#58a6ff;background:rgba(88,166,255,0.06)}
.upload-area h3{color:#e6edf3;margin-bottom:0.85rem;font-size:1rem;font-weight:600}
.upload-row{display:flex;justify-content:center;align-items:center;gap:.75rem;flex-wrap:wrap;max-width:600px;margin:0 auto}
.file-input-wrapper{position:relative;overflow:hidden;display:inline-block}
.file-input-btn{border:1px solid rgba(255,255,255,0.1);background:rgba(13,17,23,0.6);color:#e6edf3;padding:0.45rem 1rem;border-radius:6px;font-size:0.9rem;font-weight:500;cursor:pointer;transition:border-color 0.2s}
.file-input-wrapper:hover .file-input-btn{border-color:rgba(255,255,255,0.25)}
.file-input-wrapper input[type=file]{font-size:100px;position:absolute;left:0;top:0;opacity:0;cursor:pointer}
.upload-btn{padding:.45rem 1.1rem;background:linear-gradient(135deg,#1f6feb,#388bfd);border:none;border-radius:6px;color:#fff;cursor:pointer;font-size:.9rem;font-weight:600;transition:all 0.2s}
.upload-btn:hover{filter:brightness(1.15)}
.progress{margin-top:0.85rem;font-size:.85rem;color:#8b949e;font-weight:500}
.toast{position:fixed;bottom:2rem;right:2rem;padding:.75rem 1.5rem;border-radius:8px;display:none;font-size:.9rem;font-weight:600;z-index:9999;box-shadow:0 8px 24px rgba(0,0,0,0.3);animation:slideUp 0.3s ease}
@keyframes slideUp{from{transform:translateY(20px) translateX(-50%);opacity:0}to{transform:translateY(0) translateX(-50%);opacity:1}}
.toast{animation:none}@keyframes toastIn{from{transform:translateY(20px);opacity:0}to{transform:translateY(0);opacity:1}}
.toast.show{animation:toastIn 0.3s ease}
@media(max-width:600px){.grid-container{grid-template-columns:repeat(auto-fill,minmax(100px,1fr))}.root-badge{display:none}}
</style></head>
<body>
<header>
  <h1><span>&#x1F4C1;</span> PPT Remote Files</h1>
  <span class="breadcrumb">$breadcrumbs</span>
  <span class="root-badge" title="Shared folder: $rootDisplay">&#x1F512; $rootDisplay</span>
  <div class="view-toggle">
    <button class="view-btn" id="btnList" onclick="setViewMode('list')" title="List view">&#x2630;</button>
    <button class="view-btn" id="btnGrid" onclick="setViewMode('grid')" title="Grid view">&#x229E;</button>
  </div>
</header>
<main>
  $drivesHtml
  <!-- List view -->
  <div id="listView">
    <div class="table-container">
      <table>
        <thead><tr>
          <th class="cb-col"><input type="checkbox" id="selectAll" class="item-cb" onchange="toggleAll(this)" title="Select all"></th>
          <th>Name</th><th>Size</th><th>Modified</th><th></th>
        </tr></thead>
        <tbody id="rows">$rows</tbody>
      </table>
    </div>
  </div>
  <!-- Grid view -->
  <div id="gridView" style="display:none">
    <div class="grid-container" id="gridContainer">$gridItems</div>
  </div>
  <!-- Upload area -->
  <div class="upload-area" id="dropZone">
    <h3>Upload Files to this Folder</h3>
    <div class="upload-row">
      <div class="file-input-wrapper">
        <button class="file-input-btn">&#x1F4C2; Choose Files</button>
        <input type="file" id="fileInput" multiple onchange="updateSelectedFilesText()">
      </div>
      <button class="upload-btn" onclick="uploadFiles()">&#x2B06; Upload</button>
    </div>
    <div class="progress" id="prog">Drag &amp; drop files here or click Choose Files</div>
    <div id="progContainer" style="display:none;margin:1rem auto 0;background:rgba(255,255,255,0.06);border-radius:6px;height:8px;overflow:hidden;border:1px solid rgba(255,255,255,0.08);max-width:500px;">
      <div id="progBar" style="width:0%;height:100%;background:linear-gradient(90deg, #1f6feb, #388bfd);transition:width 0.1s ease"></div>
    </div>
  </div>
</main>
<!-- Floating action bar -->
<div class="action-bar" id="actionBar">
  <span class="sel-count" id="selCount">0 selected</span>
  <button class="bar-btn bar-dl" onclick="downloadSelected()">&#x2B07; Download</button>
  <button class="bar-btn bar-del" onclick="deleteSelected()">&#x1F5D1; Delete</button>
  <button class="bar-btn bar-clear" onclick="clearSelection()">&#x2715; Clear</button>
</div>
<div class="toast" id="toast"></div>
<script>
var currentPath = decodeURIComponent("$encodedPath");
// --- Toast --------------------------------------------------
function toast(msg, ok) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.style.background = ok ? '#238636' : '#da3633';
  t.style.color = '#fff';
  t.style.display = 'block';
  t.classList.remove('show'); void t.offsetWidth; t.classList.add('show');
  clearTimeout(t._timer);
  t._timer = setTimeout(function(){ t.style.display = 'none'; }, 2800);
}
// --- View mode ----------------------------------------------
var viewMode = localStorage.getItem('pptFileView') || 'list';
function setViewMode(mode) {
  viewMode = mode;
  localStorage.setItem('pptFileView', mode);
  document.getElementById('listView').style.display = mode === 'list' ? '' : 'none';
  document.getElementById('gridView').style.display = mode === 'grid' ? '' : 'none';
  document.getElementById('btnList').classList.toggle('active', mode === 'list');
  document.getElementById('btnGrid').classList.toggle('active', mode === 'grid');
  updateActionBar();
}
setViewMode(viewMode);
// --- Selection ----------------------------------------------
function updateActionBar() {
  var checked = document.querySelectorAll('.item-cb:checked');
  var bar = document.getElementById('actionBar');
  document.getElementById('selCount').textContent = checked.length + (checked.length === 1 ? ' selected' : ' selected');
  bar.className = 'action-bar' + (checked.length > 0 ? ' visible' : '');
  var sa = document.getElementById('selectAll');
  if (sa) {
    var all = document.querySelectorAll('.item-cb:not(#selectAll)');
    sa.indeterminate = checked.length > 0 && checked.length < all.length;
    sa.checked = all.length > 0 && checked.length === all.length;
  }
}
function toggleAll(master) {
  document.querySelectorAll('.item-cb:not(#selectAll)').forEach(function(cb){ cb.checked = master.checked; });
  updateActionBar();
}
function clearSelection() {
  document.querySelectorAll('.item-cb').forEach(function(cb){ cb.checked = false; });
  updateActionBar();
}
// --- Multi-download -----------------------------------------
function downloadSelected() {
  var checked = Array.from(document.querySelectorAll('.item-cb:checked'));
  if (!checked.length) return;
  
  if (checked.length === 1 && checked[0].dataset.type === 'file') {
    var a = document.createElement('a');
    a.href = '/download?path=' + checked[0].dataset.path;
    a.download = checked[0].dataset.name;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    toast('Downloading file...', true);
  } else {
    var query = checked.map(function(cb){ return 'path=' + cb.dataset.path; }).join('&');
    var a = document.createElement('a');
    a.href = '/download-zip?' + query;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    toast('Preparing zip download...', true);
  }
}
// --- Delete -------------------------------------------------
function delItem(enc, name, isDir) {
  var what = isDir ? 'folder' : 'file';
  if (!confirm('Delete ' + what + ' "' + name + '"? This cannot be undone.')) return;
  fetch('/delete?path=' + enc, {method:'DELETE'})
    .then(function(r){ return r.json(); })
    .then(function(j){
      if (j.ok) { toast('Deleted: ' + name, true); setTimeout(function(){ location.reload(); }, 700); }
      else { toast('Error: ' + j.error, false); }
    })
    .catch(function(){ toast('Delete failed', false); });
}
function deleteSelected() {
  var checked = Array.from(document.querySelectorAll('.item-cb:checked'));
  if (!checked.length) return;
  if (!confirm('Delete ' + checked.length + ' item(s)? This cannot be undone.')) return;
  var total = checked.length; var done = 0;
  checked.forEach(function(cb) {
    fetch('/delete?path=' + cb.dataset.path, {method:'DELETE'})
      .then(function(r){ return r.json(); })
      .then(function(){ done++; if (done === total) { toast('Deleted ' + total + ' item(s)', true); setTimeout(function(){ location.reload(); }, 700); } })
      .catch(function(){ done++; });
  });
}
// --- Upload -------------------------------------------------
function formatSizeJs(bytes) {
  if (bytes < 1024) return bytes + ' B';
  else if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  else return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}
function updateSelectedFilesText() {
  var inp = document.getElementById('fileInput');
  var prog = document.getElementById('prog');
  if (inp.files.length === 0) {
    prog.textContent = 'Drag & drop files here or click Choose Files';
    return;
  }
  var html = '<div style="text-align:left;max-width:500px;margin:0 auto;background:rgba(255,255,255,0.02);border:1px solid rgba(255,255,255,0.05);padding:1rem;border-radius:8px;">';
  html += '<div style="font-weight:600;margin-bottom:0.5rem;color:#58a6ff;">Selected ' + inp.files.length + ' file(s):</div>';
  html += '<ul style="list-style:none;padding-left:0;max-height:150px;overflow-y:auto;font-size:0.85rem;line-height:1.5;">';
  for (var i = 0; i < inp.files.length; i++) {
    var f = inp.files[i];
    html += '<li style="display:flex;justify-content:space-between;border-bottom:1px solid rgba(255,255,255,0.03);padding:3px 0;"><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-right:1rem;">&#x1F4C4; ' + f.name + '</span><span style="color:#8b949e;flex-shrink:0;">' + formatSizeJs(f.size) + '</span></li>';
  }
  html += '</ul>';
  html += '<div style="margin-top:0.75rem;font-size:0.8rem;color:#8b949e;text-align:center;">Click <strong>Upload</strong> to start sending.</div>';
  html += '</div>';
  prog.innerHTML = html;
}
function uploadFiles() {
  var inp = document.getElementById('fileInput');
  var prog = document.getElementById('prog');
  var container = document.getElementById('progContainer');
  var bar = document.getElementById('progBar');
  if (!inp.files.length) { toast('Select at least one file', false); return; }
  
  var files = Array.from(inp.files);
  var currentIndex = 0;
  
  container.style.display = 'block';
  bar.style.width = '0%';
  
  function uploadNext() {
    if (currentIndex >= files.length) {
      toast('Upload complete!', true);
      setTimeout(function(){ location.reload(); }, 900);
      return;
    }
    
    var file = files[currentIndex];
    var fd = new FormData();
    fd.append('file', file);
    
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/upload?path=' + encodeURIComponent(currentPath), true);
    
    xhr.upload.onprogress = function(e) {
      if (e.lengthComputable) {
        var pct = Math.round((e.loaded / e.total) * 100);
        bar.style.width = pct + '%';
        prog.innerHTML = '<div style="text-align:center;font-weight:500;">Uploading file ' + (currentIndex + 1) + ' of ' + files.length + ':<br><span style="color:#58a6ff;">' + file.name + '</span> (' + pct + '%)</div>';
      }
    };
    
    xhr.onload = function() {
      if (xhr.status === 200) {
        currentIndex++;
        uploadNext();
      } else {
        var res = JSON.parse(xhr.responseText || '{}');
        prog.innerHTML = '<span style="color:#f85149">Upload failed: ' + (res.error || 'Server error') + '</span>';
        container.style.display = 'none';
      }
    };
    
    xhr.onerror = function() {
      prog.innerHTML = '<span style="color:#f85149">Upload network failure</span>';
      container.style.display = 'none';
    };
    
    xhr.send(fd);
  }
  
  uploadNext();
}
// --- Drag & drop upload -------------------------------------
var dropZone = document.getElementById('dropZone');
var fileInput = document.getElementById('fileInput');
['dragenter','dragover'].forEach(function(ev){
  dropZone.addEventListener(ev, function(e){ e.preventDefault(); dropZone.classList.add('dragover'); }, false);
});
['dragleave','drop'].forEach(function(ev){
  dropZone.addEventListener(ev, function(e){ e.preventDefault(); dropZone.classList.remove('dragover'); }, false);
});
dropZone.addEventListener('drop', function(e){
  fileInput.files = e.dataTransfer.files; updateSelectedFilesText();
}, false);
</script>
</body></html>"""
}
