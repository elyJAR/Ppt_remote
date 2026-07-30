package com.antigravity.pptremote

import android.content.Context
import android.content.Intent
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
    private val context: Context,
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

    private class ApprovedDownload(val clientIp: String, val fileName: String, var timestamp: Long)
    private val recentApprovals = mutableListOf<ApprovedDownload>()
    private class ApprovedUpload(val clientIp: String, var timestamp: Long)
    private val recentUploadApprovals = mutableListOf<ApprovedUpload>()
    private val approvalsLock = Any()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun start(): Boolean {
        if (serverSocket != null) return true
        var boundPort = port
        var ss: ServerSocket? = null
        val useHttps = RemotePrefs.isHttpsEnabled(context)
        for (attempt in 0..2) {
            try {
                if (useHttps) {
                    val sslContext = SslHelper.getSSLContext(context)
                    ss = sslContext.serverSocketFactory.createServerSocket()
                } else {
                    ss = ServerSocket()
                }
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
        Log.i("WebFileServer", "Started ${if (useHttps) "HTTPS" else "HTTP"} on port $port, root=$rootPath")

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

            val exchange = HttpCtx(method, path, query, headers, ins, out, socket.inetAddress.hostAddress ?: "unknown")

            when {
                path == "/" || path.startsWith("/?") -> handleRoot(exchange)
                path == "/login" -> handleLogin(exchange)
                path == "/api/files" -> handleList(exchange)
                path == "/download-zip" -> handleDownloadZip(exchange)
                path.startsWith("/stream") -> handleStream(exchange)
                path.startsWith("/subtitle") -> handleSubtitle(exchange)
                path.startsWith("/api/subtitles") -> handleApiSubtitles(exchange)
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
        private val out: OutputStream,
        val clientIp: String
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
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
            302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"
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
                    if (!requestConnectionPermission(exchange.clientIp)) {
                        sendHtml(exchange, 401, buildLoginHtml(false, "Connection request denied by user."))
                        return
                    }
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
        if (!requestDownloadPermission(exchange.clientIp, file.name)) {
            sendText(exchange, 403, "Download request denied by user")
            return
        }
        val fallbackName = file.name.map { c ->
            if (c.code in 32..126 && c != '"' && c != '\\' && c != ';') c else '_'
        }.joinToString("")
        val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        exchange.addResponseHeader("Content-Disposition", "attachment; filename=\"$fallbackName\"; filename*=UTF-8''$encodedName")
        exchange.addResponseHeader("Content-Type", "application/octet-stream")
        exchange.sendResponseStream(200, file.length()) { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }

    private fun handleStream(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        val pathParam = exchange.query
            .split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
        val file = safeResolve(pathParam)
        if (file == null || !file.exists() || !file.isFile) {
            sendText(exchange, 404, "File not found")
            return
        }
        if (!requestDownloadPermission(exchange.clientIp, file.name)) {
            sendText(exchange, 403, "Stream request denied by user")
            return
        }

        val mimeType = getMimeType(file.name)
        val fallbackName = file.name.map { c ->
            if (c.code in 32..126 && c != '"' && c != '\\' && c != ';') c else '_'
        }.joinToString("")
        val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")

        val isInline = exchange.query.contains("inline=true") || mimeType.startsWith("video/") || mimeType.startsWith("audio/") || mimeType.startsWith("image/")
        val dispositionType = if (isInline) "inline" else "attachment"

        exchange.addResponseHeader("Content-Disposition", "$dispositionType; filename=\"$fallbackName\"; filename*=UTF-8''$encodedName")
        exchange.addResponseHeader("Content-Type", mimeType)
        exchange.addResponseHeader("Accept-Ranges", "bytes")
        exchange.addResponseHeader("Access-Control-Allow-Origin", "*")

        val fileLength = file.length()
        val rangeHeader = exchange.requestHeaders["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeValue = rangeHeader.removePrefix("bytes=").trim()
            val dashPos = rangeValue.indexOf('-')
            if (dashPos != -1) {
                var start = 0L
                var end = fileLength - 1

                try {
                    val startStr = rangeValue.substring(0, dashPos).trim()
                    val endStr = rangeValue.substring(dashPos + 1).trim()

                    if (startStr.isNotEmpty()) {
                        start = startStr.toLong()
                    } else if (endStr.isNotEmpty()) {
                        val suffixLen = endStr.toLong()
                        start = fileLength - suffixLen
                    }

                    if (endStr.isNotEmpty() && startStr.isNotEmpty()) {
                        end = endStr.toLong()
                    }
                } catch (_: NumberFormatException) {
                    sendText(exchange, 400, "Bad Range Header")
                    return
                }

                if (start < 0) start = 0
                if (end >= fileLength) end = fileLength - 1

                if (start > end || start >= fileLength) {
                    exchange.addResponseHeader("Content-Range", "bytes */$fileLength")
                    sendText(exchange, 416, "Range Not Satisfiable")
                    return
                }

                val contentLength = end - start + 1
                exchange.addResponseHeader("Content-Range", "bytes $start-$end/$fileLength")
                exchange.addResponseHeader("Connection", "keep-alive")
                exchange.addResponseHeader("Keep-Alive", "timeout=30, max=1000")
                exchange.addResponseHeader("Cache-Control", "no-cache, private")

                exchange.sendResponseStream(206, contentLength) { out ->
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(start)
                        val buffer = ByteArray(64 * 1024)
                        var bytesRemaining = contentLength
                        while (bytesRemaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                            val read = raf.read(buffer, 0, toRead)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            bytesRemaining -= read
                        }
                    }
                }
                return
            }
        }

        exchange.sendResponseStream(200, fileLength) { out ->
            file.inputStream().use { it.copyTo(out) }
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp", "3g2" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "ogv" -> "video/ogg"
            "flv" -> "video/x-flv"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "opus" -> "audio/opus"
            "wma" -> "audio/x-ms-wma"
            "mid", "midi" -> "audio/midi"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    private fun handleSubtitle(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        val pathParam = exchange.query
            .split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
        val file = safeResolve(pathParam)
        if (file == null || !file.exists() || !file.isFile) {
            sendText(exchange, 404, "Subtitle not found")
            return
        }

        val ext = file.name.substringAfterLast('.', "").lowercase()
        val content = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (_: Exception) {
            sendText(exchange, 500, "Could not read subtitle file")
            return
        }

        val vttContent = if (ext == "vtt") {
            content
        } else {
            convertSrtToVtt(content)
        }

        val bytes = vttContent.toByteArray(StandardCharsets.UTF_8)
        exchange.addResponseHeader("Content-Type", "text/vtt; charset=utf-8")
        exchange.addResponseHeader("Access-Control-Allow-Origin", "*")
        exchange.sendResponse(200, bytes)
    }

    private fun convertSrtToVtt(srt: String): String {
        val sb = StringBuilder()
        sb.append("WEBVTT\n\n")
        val lines = srt.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        
        val isAss = lines.any { it.trim().startsWith("[Script Info]") || it.trim().startsWith("Dialogue:") }
        
        for (line in lines) {
            val trimmed = line.trim()
            
            if (isAss) {
                if (trimmed.startsWith("Dialogue:")) {
                    val parts = trimmed.substringAfter("Dialogue:").split(",", limit = 10)
                    if (parts.size >= 10) {
                        val start = parts[1].trim()
                        val end = parts[2].trim()
                        var text = parts[9].replace(Regex("\\{.*?\\}"), "").trim()
                        text = text.replace("\\N", "\n").replace("\\n", "\n")
                        
                        val formatTime = { t: String ->
                            val tParts = t.split(":")
                            if (tParts.size == 3) {
                                val h = tParts[0].padStart(2, '0')
                                val m = tParts[1].padStart(2, '0')
                                val sCs = tParts[2].split(".")
                                val s = sCs[0].padStart(2, '0')
                                val ms = if (sCs.size > 1) sCs[1].padEnd(3, '0').take(3) else "000"
                                "$h:$m:$s.$ms"
                            } else t
                        }
                        
                        sb.append("${formatTime(start)} --> ${formatTime(end)}\n$text\n\n")
                    }
                }
            } else {
                if (trimmed.contains("-->")) {
                    val converted = trimmed.replace(Regex("(\\d{1,2}:\\d{2}:\\d{2}),(\\d{2,3})"), "$1.$2")
                    sb.append(converted).append("\n")
                } else {
                    val cleanLine = line.replace(Regex("<font.*?>", RegexOption.IGNORE_CASE), "")
                                        .replace(Regex("</font>", RegexOption.IGNORE_CASE), "")
                    sb.append(cleanLine).append("\n")
                }
            }
        }
        return sb.toString()
    }

    private fun handleApiSubtitles(exchange: HttpCtx) {
        if (!requireAuth(exchange)) return
        val pathParam = exchange.query
            .split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
        val target = safeResolve(pathParam)
        if (target == null || !target.exists()) {
            sendJson(exchange, 200, "{\"items\":[]}")
            return
        }

        val targetDir = if (target.isDirectory) target else (target.parentFile ?: target)
        val videoBaseName = if (target.isFile) target.nameWithoutExtension.lowercase() else ""
        val subExtensions = setOf("srt", "vtt", "ass", "sub")

        val list = mutableListOf<String>()
        val canonicalDir = targetDir.canonicalFile

        val activeRootPath = allowedRoots.firstOrNull { canonicalDir.path.startsWith(File(it).canonicalPath) } ?: rootPath
        val isAtRoot = canonicalDir.canonicalPath == File(activeRootPath).canonicalPath

        val parent = canonicalDir.parentFile
        if (!isAtRoot && parent != null) {
            val parentEnc = URLEncoder.encode(parent.canonicalPath, "UTF-8")
            list.add("""{"path":${jsonStr(parentEnc)},"name":".. (Parent Folder)","type":"folder","label":"📁 .. (Parent Folder)"}""")
        }

        val allFiles = (canonicalDir.listFiles() ?: emptyArray()).sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        )

        allFiles.forEach { f ->
            val enc = URLEncoder.encode(f.canonicalPath, "UTF-8")
            if (f.isDirectory) {
                list.add("""{"path":${jsonStr(enc)},"name":${jsonStr(f.name)},"type":"folder","label":${jsonStr("📁 " + f.name)}}""")
            } else if (f.isFile && f.extension.lowercase() in subExtensions) {
                val isMatch = videoBaseName.isNotEmpty() && (f.nameWithoutExtension.lowercase() == videoBaseName || f.nameWithoutExtension.lowercase().startsWith("$videoBaseName."))
                val prefix = if (isMatch) "⭐ " else "📄 "
                val label = prefix + f.name
                list.add("""{"path":${jsonStr(enc)},"name":${jsonStr(f.name)},"type":"file","label":${jsonStr(label)},"ext":${jsonStr(f.extension)}}""")
            }
        }

        val dirEnc = URLEncoder.encode(canonicalDir.canonicalPath, "UTF-8")
        sendJson(exchange, 200, """{"currentPath":${jsonStr(dirEnc)},"items":[${list.joinToString(",")}]}""")
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
        if (!requestDownloadPermission(exchange.clientIp, zipName)) {
            sendText(exchange, 403, "Download request denied by user")
            return
        }
        val fallbackZipName = zipName.map { c ->
            if (c.code in 32..126 && c != '"' && c != '\\' && c != ';') c else '_'
        }.joinToString("")
        val encodedZipName = URLEncoder.encode(zipName, "UTF-8").replace("+", "%20")
        exchange.addResponseHeader("Content-Disposition", "attachment; filename=\"$fallbackZipName\"; filename*=UTF-8''$encodedZipName")
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
        val conflictParam = exchange.query
            .split("&").firstOrNull { it.startsWith("conflict=") }
            ?.removePrefix("conflict=") ?: "rename"

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
            val contentLength = exchange.requestHeaders["content-length"]?.toLongOrNull() ?: -1L
            val savedName = parseMultipartAndSave(exchange.bodyStream, boundary, dir, exchange.clientIp, contentLength, conflictParam)
            if (savedName != null) {
                sendJson(exchange, 200, """{"ok":true,"name":${jsonStr(savedName)}}""")
            } else {
                sendJson(exchange, 400, """{"error":"No file found or invalid upload format"}""")
            }
        } catch (e: Exception) {
            Log.e("WebFileServer", "Upload failed", e)
            val msg = if (e.message == "Upload request denied by user") "Upload request denied by user." else "Upload failed: ${e.message}"
            sendJson(exchange, 403, """{"error":"$msg"}""")
        }
    }

    private fun getUniqueDestinationFile(dir: File, originalName: String): File {
        var dest = File(dir, originalName)
        if (!dest.exists()) return dest

        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex != -1) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex != -1) originalName.substring(dotIndex) else ""

        var counter = 1
        while (dest.exists()) {
            val newName = "$baseName ($counter)$extension"
            dest = File(dir, newName)
            counter++
        }
        return dest
    }

    private fun parseMultipartAndSave(
        input: InputStream,
        boundary: String,
        dir: File,
        clientIp: String,
        totalRequestBytes: Long,
        conflictMode: String
    ): String? {
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

        if (!requestUploadPermission(clientIp, filename)) {
            throw java.io.IOException("Upload request denied by user")
        }

        val originalName = File(filename).name
        val rawFile = File(dir, originalName)
        val dest = if (rawFile.exists() && conflictMode == "replace") {
            try { rawFile.delete() } catch (_: Exception) {}
            rawFile
        } else if (rawFile.exists()) {
            getUniqueDestinationFile(dir, originalName)
        } else {
            rawFile
        }
        val out = FileOutputStream(dest)

        RemoteControlService.isUploadCancelled = false
        RemoteControlService.activeUploadName = filename
        RemoteControlService.activeUploadTotal = totalRequestBytes
        RemoteControlService.activeUploadBytes = 0L
        RemoteControlService.activeUploadProgress = 0f

        var totalBytesRead = headerStream.size().toLong()

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
                        totalBytesRead += read
                        RemoteControlService.activeUploadBytes = totalBytesRead
                        if (totalRequestBytes > 0) {
                            val progress = totalBytesRead.toFloat() / totalRequestBytes.toFloat()
                            RemoteControlService.activeUploadProgress = progress.coerceIn(0f, 1f)
                        }
                    } else if (read == -1 && bufferLen == 0) {
                        break
                    }
                }

                if (RemoteControlService.isUploadCancelled) {
                    throw java.io.IOException("Upload cancelled by user on phone")
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
            try { out.close() } catch (_: Exception) {}
            if (RemoteControlService.isUploadCancelled) {
                try { dest.delete() } catch (_: Exception) {}
            }
            RemoteControlService.activeUploadName = null
            RemoteControlService.activeUploadTotal = 0L
            RemoteControlService.activeUploadBytes = 0L
            RemoteControlService.activeUploadProgress = 0f
        }
        return dest.name
    }

    private fun requestConnectionPermission(clientIp: String): Boolean {
        val decision = RemoteControlService.SecurityDecision()
        val requestId = java.util.UUID.randomUUID().toString()

        RemoteControlService.showPermissionRequestNotification(
            context, clientIp, "Connection Request", requestId, "Pairing Connection"
        ) { approved -> decision.setDecision(approved) }

        val activeListener = RemoteControlService.securityListener
        if (activeListener != null) {
            activeListener.onRequestConnection(clientIp) { approved ->
                RemoteControlService.resolveRequest(requestId, approved, context)
            }
        }
        return decision.getDecision()
    }

    private fun requestDeletePermission(clientIp: String, fileName: String): Boolean {
        val decision = RemoteControlService.SecurityDecision()
        val requestId = java.util.UUID.randomUUID().toString()

        RemoteControlService.showPermissionRequestNotification(
            context, clientIp, fileName, requestId, "Delete"
        ) { approved -> decision.setDecision(approved) }

        val activeListener = RemoteControlService.securityListener
        if (activeListener != null) {
            activeListener.onRequestDelete(clientIp, fileName) { approved ->
                RemoteControlService.resolveRequest(requestId, approved, context)
            }
        }
        return decision.getDecision()
    }

    private fun requestUploadPermission(clientIp: String, fileName: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(approvalsLock) {
            recentUploadApprovals.removeAll { now - it.timestamp > 300000L }
            val existing = recentUploadApprovals.firstOrNull { it.clientIp == clientIp }
            if (existing != null) {
                existing.timestamp = now
                return true
            }
        }

        val decision = RemoteControlService.SecurityDecision()
        val requestId = java.util.UUID.randomUUID().toString()

        RemoteControlService.showPermissionRequestNotification(
            context, clientIp, fileName, requestId, "Upload"
        ) { approved -> decision.setDecision(approved) }

        val activeListener = RemoteControlService.securityListener
        if (activeListener != null) {
            activeListener.onRequestUpload(clientIp, fileName) { approved ->
                RemoteControlService.resolveRequest(requestId, approved, context)
            }
        }

        val result = decision.getDecision()
        if (result) {
            synchronized(approvalsLock) {
                recentUploadApprovals.add(ApprovedUpload(clientIp, System.currentTimeMillis()))
            }
        }
        return result
    }

    private fun requestDownloadPermission(clientIp: String, fileName: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(approvalsLock) {
            recentApprovals.removeAll { now - it.timestamp > 7200000L }
            val existing = recentApprovals.firstOrNull { it.clientIp == clientIp }
            if (existing != null) {
                existing.timestamp = now
                return true
            }
        }

        val decision = RemoteControlService.SecurityDecision()
        val requestId = java.util.UUID.randomUUID().toString()

        RemoteControlService.showPermissionRequestNotification(
            context, clientIp, fileName, requestId, "Stream/Download"
        ) { approved -> decision.setDecision(approved) }

        val activeListener = RemoteControlService.securityListener
        if (activeListener != null) {
            activeListener.onRequestDownload(clientIp, fileName) { approved ->
                RemoteControlService.resolveRequest(requestId, approved, context)
            }
        }

        val result = decision.getDecision()
        if (result) {
            synchronized(approvalsLock) {
                recentApprovals.add(ApprovedDownload(clientIp, fileName, System.currentTimeMillis()))
            }
        }
        return result
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
        if (!requestDeletePermission(exchange.clientIp, file.name)) {
            sendJson(exchange, 403, """{"error":"Delete request denied by user."}""")
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
    private fun buildLoginHtml(error: Boolean, customError: String? = null): String {
        val errorMsg = when {
            customError != null -> "<p class='err'>&#x274C; $customError</p>"
            error -> "<p class='err'>&#x274C; Incorrect PIN, please try again.</p>"
            else -> ""
        }
        return """<!DOCTYPE html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PPT Remote &mdash; Unlock Files</title>
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><rect width=%22100%22 height=%22100%22 rx=%2220%22 fill=%22%231f6feb%22/><text x=%2250%22 y=%2270%22 font-family=%22sans-serif%22 font-size=%2260%22 font-weight=%22bold%22 fill=%22white%22 text-anchor=%22middle%22>P</text></svg>">
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

.overlay {
  position: fixed;
  top: 0; left: 0; width: 100%; height: 100%;
  background: rgba(10, 13, 22, 0.8);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.3s ease;
}
.overlay-card {
  background: rgba(22, 27, 34, 0.85);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 2.5rem 2rem;
  width: 320px;
  text-align: center;
  box-shadow: 0 24px 50px rgba(0,0,0,0.5);
  animation: scaleUp 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.overlay-card h3 {
  color: #58a6ff;
  font-size: 1.3rem;
  margin: 1rem 0 0.5rem;
  font-weight: 700;
}
.overlay-card p {
  color: #8b949e;
  font-size: 0.9rem;
  margin-bottom: 1.5rem;
}
.overlay-close-btn {
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #8b949e;
  padding: 0.5rem 1.25rem;
  border-radius: 6px;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.2s;
}
.overlay-close-btn:hover {
  background: rgba(255, 255, 255, 0.05);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.2);
}
.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid rgba(88, 166, 255, 0.1);
  border-left-color: #58a6ff;
  border-radius: 50%;
  margin: 0 auto;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes scaleUp {
  from { transform: scale(0.9); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}
</style></head><body>
<div class="card">
<h2>&#x1F4C1; File Transfer</h2>
<p class="subtitle">Access files on this device</p>
$errorMsg
<form method="post" action="/login" onsubmit="showApprovalOverlay('Waiting for Connection','Please approve the connection request on your phone app.')">
<input type="password" name="pin" placeholder="PIN" autofocus inputmode="numeric" maxlength="8">
<button type="submit">Unlock Files</button>
</form>
<p class="hint">Enter the access PIN displayed on the PPT Remote mobile app.</p>
</div>
<div id="approvalOverlay" class="overlay" style="display:none;">
  <div class="overlay-card">
    <div class="spinner"></div>
    <h3 id="approvalTitle">Waiting for Approval</h3>
    <p id="approvalMsg">Please approve the connection request on your phone app.</p>
    <button class="overlay-close-btn" onclick="hideApprovalOverlay()">Dismiss</button>
  </div>
</div>
<script>
function showApprovalOverlay(actionText, msg) {
  var overlay = document.getElementById('approvalOverlay');
  var title = document.getElementById('approvalTitle');
  var p = document.getElementById('approvalMsg');
  if (actionText) title.textContent = actionText;
  if (msg) p.textContent = msg;
  overlay.style.display = 'flex';
}
function hideApprovalOverlay() {
  document.getElementById('approvalOverlay').style.display = 'none';
}
</script>
</body></html>"""
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

            val ext = f.name.substringAfterLast('.', "").lowercase()
            val isMedia = !f.isDirectory && ext in listOf(
                "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "ogv", "flv",
                "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma",
                "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp"
            )

            // Table row (list view)
            val nameCell = if (f.isDirectory)
                "<a href='/?path=$enc' class='folder-link'>$icon ${f.name}</a>"
            else
                "<span class='file-name'>$icon ${f.name}</span>"

            val streamBtn = if (isMedia)
                "<button class='btn btn-stream' onclick=\"openMediaPlayer('$enc', '$safeName', '$ext')\">&#x25B6; Stream</button>"
            else ""

            val actions = if (f.isDirectory)
                "<a class='btn' href='/download-zip?path=$enc' download onclick=\"showApprovalOverlay('Approving Download', 'Please approve the download request on your phone.'); setTimeout(hideApprovalOverlay, 6000);\">&#x2B07; Download</a><button class='del' onclick=\"delItem('$enc','$safeName',true)\">&#x1F5D1;</button>"
            else
                "$streamBtn<a class='btn' href='/download?path=$enc' download onclick=\"showApprovalOverlay('Approving Download', 'Please approve the download request on your phone.'); setTimeout(hideApprovalOverlay, 6000);\">&#x2B07; Download</a><button class='del' onclick=\"delItem('$enc','$safeName',false)\">&#x1F5D1;</button>"

            tableRows.append("<tr><td class='cb-col'><input type='checkbox' class='item-cb' data-path='$enc' data-name='$safeName' data-type='$type' data-size='${if (f.isDirectory) -1 else f.length()}' data-modified='${f.lastModified()}' onchange='updateActionBar()'></td><td>$nameCell</td><td>$size</td><td>$date</td><td class='act'>$actions</td></tr>\n")

            // Grid item
            val gridClick = if (f.isDirectory) "if(!event.target.matches('input,a,button')){window.location='/?path=$enc'}" else if (isMedia) "if(!event.target.matches('input,a,button')){openMediaPlayer('$enc', '$safeName', '$ext')}" else ""
            val gridStream = if (isMedia) "<button class='btn-sm btn-stream-sm' onclick=\"event.stopPropagation();openMediaPlayer('$enc', '$safeName', '$ext')\">&#x25B6;</button>" else ""
            val gridDownload = "$gridStream<a class='btn-sm' href='${if (f.isDirectory) "/download-zip?path=" else "/download?path="}$enc' download onclick=\"event.stopPropagation(); showApprovalOverlay('Approving Download', 'Please approve the download request on your phone.'); setTimeout(hideApprovalOverlay, 6000);\">&#x2B07;</a><button class='del-sm' onclick=\"event.stopPropagation();delItem('$enc','$safeName',${f.isDirectory})\">&#x1F5D1;</button>"
            gridItems.append("<div class='grid-item $type' onclick=\"$gridClick\"><input type='checkbox' class='item-cb grid-cb' data-path='$enc' data-name='$safeName' data-type='$type' data-size='${if (f.isDirectory) -1 else f.length()}' data-modified='${f.lastModified()}' onchange='event.stopPropagation();updateActionBar()'><div class='grid-icon'>$icon</div><div class='grid-name' title='${f.name}'>${f.name}</div><div class='grid-size'>$size</div><div class='grid-actions'>$gridDownload</div></div>\n")
        }

        val emptyRow = "<tr id='tableEmptyRow' style='${if (files.isEmpty()) "" else "display:none;"}'><td></td><td colspan='4' class='empty empty-text'>&#x1F4C1; This folder is empty</td></tr>"
        val emptyGrid = "<div class='grid-empty' style='${if (files.isEmpty()) "display:flex;" else "display:none;"}'>&#x1F4C1; This folder is empty</div>"

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
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 100 100%22><rect width=%22100%22 height=%22100%22 rx=%2220%22 fill=%22%231f6feb%22/><text x=%2250%22 y=%2270%22 font-family=%22sans-serif%22 font-size=%2260%22 font-weight=%22bold%22 fill=%22white%22 text-anchor=%22middle%22>P</text></svg>">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:radial-gradient(circle at top right, rgba(29,78,216,0.08), transparent 45%), #0a0d16;color:#e6edf3;font-family:'Outfit',system-ui,sans-serif;font-size:14px;min-height:100vh}
header{background:rgba(22,27,34,0.85);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);border-bottom:1px solid rgba(255,255,255,0.05);padding:0.85rem 1.5rem;display:flex;align-items:center;gap:1rem;position:sticky;top:0;z-index:100;box-shadow:0 4px 20px rgba(0,0,0,0.15);flex-wrap:wrap}
header h1{color:#58a6ff;font-size:1.15rem;font-weight:700;letter-spacing:-0.01em;display:flex;align-items:center;gap:0.4rem;white-space:nowrap}
.breadcrumb{color:#8b949e;font-size:.85rem;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;display:flex;align-items:center;min-width:0}
.current-location-bar{display:flex;align-items:center;gap:0.6rem;background:rgba(22,27,34,0.6);border:1px solid rgba(255,255,255,0.08);border-radius:10px;padding:0.65rem 1rem;margin-bottom:1.25rem;font-size:0.9rem;color:#e6edf3;box-shadow:0 4px 16px rgba(0,0,0,0.15);flex-wrap:wrap}
.location-icon{font-size:1.1rem}
.location-label{color:#8b949e;font-weight:600;font-size:0.85rem;text-transform:uppercase;letter-spacing:0.04em}
.location-breadcrumbs{display:flex;align-items:center;flex-wrap:wrap;gap:0.3rem}
.bc-item{color:#58a6ff;text-decoration:none;background:rgba(88,166,255,0.1);border:1px solid rgba(88,166,255,0.2);padding:3px 9px;border-radius:6px;font-weight:500;font-size:0.85rem;transition:all 0.2s;display:inline-flex;align-items:center}
.bc-item:hover{background:rgba(88,166,255,0.25);border-color:#58a6ff;color:#fff;text-decoration:none}
.bc-sep{color:#484f58;font-weight:bold;margin:0 2px}
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
.table-container{background:rgba(22,27,34,0.4);border:1px solid rgba(255,255,255,0.08);border-radius:12px;overflow-x:auto;box-shadow:0 8px 30px rgba(0,0,0,0.25)}
table{width:100%;border-collapse:collapse}
th{text-align:left;padding:0.9rem 1.1rem;color:#8b949e;border-bottom:1px solid rgba(255,255,255,0.08);font-weight:600;font-size:0.82rem;text-transform:uppercase;letter-spacing:0.05em}
th.sortable{cursor:pointer;user-select:none;transition:background 0.2s,color 0.2s}
th.sortable:hover{background:rgba(255,255,255,0.03);color:#e6edf3}
.sort-icon{margin-left:0.35rem;font-size:0.75rem;opacity:0.6}
td{padding:0.75rem 1.1rem;border-bottom:1px solid rgba(255,255,255,0.04);vertical-align:middle}
tr:last-child td{border-bottom:none}
tr:hover td{background:rgba(255,255,255,0.02)}
td a.folder-link{color:#58a6ff;text-decoration:none;font-weight:500;transition:color 0.2s;display:inline-flex;align-items:center;gap:0.3rem;word-break:break-all;white-space:normal;flex-wrap:wrap}
td a.folder-link:hover{color:#79c0ff;text-decoration:underline}
.file-name{color:#e6edf3;font-weight:450;display:inline-flex;align-items:center;gap:0.3rem;word-break:break-all;white-space:normal;flex-wrap:wrap}
.act{white-space:nowrap;text-align:right;display:flex;gap:0.4rem;justify-content:flex-end;align-items:center}
.btn,.del{display:inline-flex;align-items:center;padding:.35rem .7rem;border-radius:6px;font-size:.82rem;font-weight:500;cursor:pointer;text-decoration:none;border:none;transition:all 0.2s;white-space:nowrap}
.btn{background:linear-gradient(135deg,#238636,#2ea043);color:#fff}
.btn:hover{filter:brightness(1.15);transform:translateY(-1px)}
.del{background:rgba(248,81,73,0.06);color:#f85149;border:1px solid rgba(248,81,73,0.15)}
.del:hover{background:rgba(248,81,73,0.15);border-color:rgba(248,81,73,0.4)}
.btn-stream{background:linear-gradient(135deg,#1f6feb,#388bfd);color:#fff}
.btn-stream:hover{filter:brightness(1.15);transform:translateY(-1px)}
.btn-stream-sm{background:linear-gradient(135deg,#1f6feb,#388bfd);color:#fff;display:inline-flex;align-items:center}
.btn-stream-sm:hover{filter:brightness(1.15)}
.media-modal-card {
  background: rgba(22, 27, 34, 0.95);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 16px;
  padding: 1.5rem;
  width: 90%;
  max-width: 860px;
  max-height: 95vh;
  overflow-y: auto;
  box-shadow: 0 24px 60px rgba(0,0,0,0.6);
  animation: scaleUp 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
  display: flex;
  flex-direction: column;
}
.media-container {
  position: relative;
  display: flex;
  justify-content: center;
  align-items: center;
  background: #000;
  border-radius: 10px;
  overflow: hidden;
  min-height: 220px;
}
.media-container.fullscreen-mode,
.media-container:fullscreen,
.media-container:-webkit-full-screen {
  position: fixed !important;
  top: 0 !important;
  left: 0 !important;
  right: 0 !important;
  bottom: 0 !important;
  width: 100vw !important;
  height: 100vh !important;
  max-width: 100vw !important;
  max-height: 100vh !important;
  border-radius: 0 !important;
  background: #000 !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  padding: 0 !important;
  margin: 0 !important;
  z-index: 999999 !important;
}
.media-container.fullscreen-mode #mediaVideoPlayer,
.media-container:fullscreen #mediaVideoPlayer,
.media-container:-webkit-full-screen #mediaVideoPlayer {
  width: 100vw !important;
  height: 100vh !important;
  max-width: 100vw !important;
  max-height: 100vh !important;
  object-fit: contain !important;
  border-radius: 0 !important;
}
.media-container.fullscreen-mode .video-custom-controls,
.media-container:fullscreen .video-custom-controls,
.media-container:-webkit-full-screen .video-custom-controls {
  border-radius: 0 !important;
  border: none !important;
  background: linear-gradient(to top, rgba(0,0,0,0.95), rgba(0,0,0,0.5) 70%, transparent) !important;
  padding: 1rem 1.5rem !important;
  z-index: 1000000 !important;
}
body.v-fullscreen-active {
  overflow: hidden !important;
}
.gesture-overlay {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  z-index: 10;
  pointer-events: auto;
  touch-action: none;
  user-select: none;
}
.gesture-badge {
  position: absolute;
  top: 50%;
  transform: translateY(-50%) scale(0);
  background: rgba(15, 23, 42, 0.85);
  color: #58a6ff;
  border: 1.5px solid rgba(88, 166, 255, 0.5);
  border-radius: 50%;
  width: 72px; height: 72px;
  display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 1.1rem;
  pointer-events: none;
  transition: transform 0.22s cubic-bezier(0.34, 1.56, 0.64, 1), opacity 0.2s ease;
  opacity: 0;
  box-shadow: 0 0 20px rgba(88,166,255,0.3);
}
.gesture-badge.show {
  transform: translateY(-50%) scale(1);
  opacity: 1;
}
.gesture-left { left: 15%; }
.gesture-center { left: 50%; transform: translate(-50%, -50%) scale(0); }
.gesture-center.show { transform: translate(-50%, -50%) scale(1); opacity: 1; }
.gesture-right { right: 15%; }
.gesture-hud {
  position: absolute;
  top: 15px; left: 50%;
  transform: translateX(-50%);
  background: rgba(15, 20, 30, 0.9);
  border: 1px solid rgba(88,166,255,0.4);
  border-radius: 20px;
  padding: 5px 16px;
  color: #58a6ff;
  font-weight: 600;
  font-size: 0.85rem;
  display: none;
  pointer-events: none;
  z-index: 15;
  box-shadow: 0 4px 16px rgba(0,0,0,0.4);
}
.video-custom-controls {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  background: rgba(13,17,23,0.95);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 10px;
  padding: 0.6rem 1rem;
  box-shadow: 0 4px 16px rgba(0,0,0,0.3);
  width: 100%;
  flex-wrap: wrap;
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 50;
  transition: opacity 0.3s ease;
}
.media-container:fullscreen .video-custom-controls,
.media-container:-webkit-full-screen .video-custom-controls {
  border-radius: 0 !important;
  border: none !important;
  background: linear-gradient(to top, rgba(0,0,0,0.95), rgba(0,0,0,0.5) 70%, transparent) !important;
  padding: 1rem 1.5rem !important;
}
.v-btn{background:rgba(88,166,255,0.15);border:1px solid rgba(88,166,255,0.3);color:#58a6ff;border-radius:6px;width:34px;height:34px;display:inline-flex;align-items:center;justify-content:center;font-size:1rem;cursor:pointer;transition:all 0.2s;flex-shrink:0}
.v-btn:hover{background:rgba(88,166,255,0.3);color:#fff}
.v-time{color:#8b949e;font-family:monospace;font-size:0.85rem;white-space:nowrap;flex-shrink:0}
.v-seek-bar{flex:1;min-width:120px;height:6px;border-radius:3px;background:rgba(255,255,255,0.15);outline:none;cursor:pointer;accent-color:#58a6ff}
.v-vol-bar{width:70px;height:6px;border-radius:3px;background:rgba(255,255,255,0.15);outline:none;cursor:pointer;accent-color:#58a6ff;flex-shrink:0}
.media-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1rem;
}
.media-modal-header h3 {
  color: #58a6ff;
  font-size: 1.15rem;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 80%;
}
.media-container {
  display: flex;
  justify-content: center;
  align-items: center;
  background: #000;
  border-radius: 10px;
  overflow: hidden;
  min-height: 200px;
}
.audio-player-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: rgba(15, 20, 30, 0.96);
  backdrop-filter: blur(20px);
  border-top: 1px solid rgba(88, 166, 255, 0.3);
  padding: 0.75rem 1.5rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1.5rem;
  z-index: 900;
  box-shadow: 0 -4px 20px rgba(0,0,0,0.4);
  animation: slideUp 0.3s ease;
}
.audio-track-info {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  min-width: 0;
  flex: 1;
}
.audio-icon {
  font-size: 1.4rem;
  color: #58a6ff;
}
.audio-title {
  color: #e6edf3;
  font-weight: 600;
  font-size: 0.9rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
#mediaAudioPlayer {
  flex: 2;
  max-width: 600px;
  height: 40px;
  outline: none;
}
.audio-close-btn {
  background: transparent;
  border: none;
  color: #8b949e;
  font-size: 1.2rem;
  cursor: pointer;
  padding: 0.3rem 0.6rem;
  border-radius: 6px;
  transition: all 0.2s;
}
.audio-close-btn:hover {
  color: #f85149;
  background: rgba(248, 81, 73, 0.15);
}
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
.upload-area{margin-top:1.5rem;margin-bottom:1.5rem;background:rgba(22,27,34,0.35);border:2px dashed rgba(88,166,255,0.25);border-radius:12px;padding:1.75rem;text-align:center;transition:all 0.3s ease}
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
.search-bar-container {
  display: flex;
  align-items: center;
  background: rgba(22, 27, 34, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 0.55rem 0.85rem;
  margin: 1.25rem 1.25rem 0 1.25rem;
  max-width: 400px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.search-bar-container:focus-within {
  border-color: #58a6ff;
  box-shadow: 0 0 0 3px rgba(88, 166, 255, 0.15);
}
.search-icon {
  color: #8b949e;
  font-size: 0.95rem;
  margin-right: 0.5rem;
  user-select: none;
}
#searchInput {
  background: transparent;
  border: none;
  color: #e6edf3;
  font-family: inherit;
  font-size: 0.9rem;
  width: 100%;
  outline: none;
}
#searchInput::placeholder {
  color: #8b949e;
}

.overlay {
  position: fixed;
  top: 0; left: 0; width: 100%; height: 100%;
  background: rgba(10, 13, 22, 0.8);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.3s ease;
}
.overlay-card {
  background: rgba(22, 27, 34, 0.85);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 16px;
  padding: 2.5rem 2rem;
  width: 320px;
  text-align: center;
  box-shadow: 0 24px 50px rgba(0,0,0,0.5);
  animation: scaleUp 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.overlay-card h3 {
  color: #58a6ff;
  font-size: 1.3rem;
  margin: 1rem 0 0.5rem;
  font-weight: 700;
}
.overlay-card p {
  color: #8b949e;
  font-size: 0.9rem;
  margin-bottom: 1.5rem;
}
.overlay-close-btn {
  background: transparent;
  border: 1px solid rgba(255, 255, 255, 0.1);
  color: #8b949e;
  padding: 0.5rem 1.25rem;
  border-radius: 6px;
  font-size: 0.85rem;
  cursor: pointer;
  transition: all 0.2s;
}
.overlay-close-btn:hover {
  background: rgba(255, 255, 255, 0.05);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.2);
}
.spinner {
  width: 48px;
  height: 48px;
  border: 4px solid rgba(88, 166, 255, 0.1);
  border-left-color: #58a6ff;
  border-radius: 50%;
  margin: 0 auto;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes scaleUp {
  from { transform: scale(0.9); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}
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
  <div class="current-location-bar">
    <span class="location-icon">&#x1F4C2;</span>
    <span class="location-label">Current Location:</span>
    <div class="location-breadcrumbs">$breadcrumbs</div>
  </div>
  <div class="search-bar-container" style="margin: 0 0 1.25rem 0; max-width: 100%;">
    <span class="search-icon">&#x1F50D;</span>
    <input type="text" id="searchInput" placeholder="Search files and folders in current directory..." oninput="filterItems()">
  </div>
  $drivesHtml
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
  <!-- List view -->
  <div id="listView">
    <div class="table-container">
      <table>
        <thead><tr>
          <th class="cb-col"><input type="checkbox" id="selectAll" class="item-cb" onchange="toggleAll(this)" title="Select all"></th>
          <th onclick="sortTable('name')" class="sortable" id="th-name">Name <span class="sort-icon">⇅</span></th>
          <th onclick="sortTable('size')" class="sortable" id="th-size">Size <span class="sort-icon">⇅</span></th>
          <th onclick="sortTable('modified')" class="sortable" id="th-modified">Modified <span class="sort-icon">⇅</span></th>
          <th></th>
        </tr></thead>
        <tbody id="rows">$rows</tbody>
      </table>
    </div>
  </div>
  <!-- Grid view -->
  <div id="gridView" style="display:none">
    <div class="grid-container" id="gridContainer">$gridItems</div>
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

<!-- Media Modal Player -->
<div id="mediaModal" class="overlay" style="display:none;">
  <div class="media-modal-card">
    <div class="media-modal-header">
      <h3 id="mediaTitle">&#x1F3AC; Media Player</h3>
      <div style="display:flex;gap:0.5rem;align-items:center;">
        <button class="btn-sm" style="background:rgba(46,160,67,0.2);color:#3fb950;border:1px solid rgba(46,160,67,0.4);padding:4px 10px;font-weight:600;display:inline-flex;align-items:center;gap:4px;cursor:pointer;" onclick="openInVlcPlayer()" title="Open stream in external player like VLC or MX Player">🚀 Open in VLC / App</button>
        <button class="overlay-close-btn" onclick="closeMediaModal()">&#x2715; Close</button>
      </div>
    </div>
    <div class="media-container" id="mediaContainer">
      <video id="mediaVideoPlayer" autoplay style="display:none;width:100%;max-height:70vh;border-radius:10px;background:#000;"></video>
      <img id="mediaImageViewer" style="display:none;max-width:100%;max-height:70vh;border-radius:10px;object-fit:contain;" />
      <div id="gestureOverlay" class="gesture-overlay" style="display:none;">
        <div id="gestureBadgeLeft" class="gesture-badge gesture-left">-10s</div>
        <div id="gestureBadgeCenter" class="gesture-badge gesture-center">⏯</div>
        <div id="gestureBadgeRight" class="gesture-badge gesture-right">+10s</div>
        <div id="gestureHUD" class="gesture-hud"></div>
      </div>
      <!-- Custom Onscreen Video Player Bar inside mediaContainer -->
      <div id="videoCustomControls" class="video-custom-controls" style="display:none;">
        <button id="vBtnPlayPause" class="v-btn" onclick="togglePlayPause()" title="Play / Pause">▶</button>
        <span id="vTimeDisplay" class="v-time">00:00 / 00:00</span>
        <input type="range" id="vSeekSlider" class="v-seek-bar" min="0" max="100" value="0" step="0.1" oninput="onSeekInput(this.value)" onchange="onSeekChange(this.value)">
        <button id="vBtnMute" class="v-btn" onclick="toggleMute()" title="Mute / Unmute">🔊</button>
        <input type="range" id="vVolumeSlider" class="v-vol-bar" min="0" max="1" value="1" step="0.05" oninput="onVolumeChange(this.value)">
        <button id="vBtnFullscreen" class="v-btn" onclick="toggleVideoFullscreen()" title="Fullscreen">⛶</button>
      </div>
    </div>
    <div id="videoErrorNotice" style="display:none;background:rgba(218,54,51,0.15);border:1px solid rgba(248,81,73,0.4);color:#f85149;padding:0.75rem 1rem;border-radius:8px;margin-top:0.75rem;font-size:0.88rem;text-align:center;">
      ⚠️ Your browser cannot decode this MKV video/audio stream natively.<br>
      <button class="btn-sm" style="background:#2ea043;color:#fff;border:none;margin-top:8px;padding:6px 14px;font-weight:600;cursor:pointer;" onclick="openInVlcPlayer()">🚀 Open Stream in VLC / External Player</button>
      <a id="videoDownloadLink" href="#" download class="btn-sm" style="background:rgba(255,255,255,0.1);color:#fff;text-decoration:none;padding:6px 14px;display:inline-block;margin-top:8px;margin-left:6px;">📥 Download File</a>
    </div>
    <div class="media-controls-extra" id="mediaExtraControls" style="display:none;margin-top:0.75rem;align-items:center;justify-content:center;gap:1rem;flex-wrap:wrap;">
      <label style="color:#8b949e;font-size:0.85rem;display:flex;align-items:center;gap:0.4rem;">Speed: 
        <select id="speedSelect" onchange="changePlaybackSpeed(this.value)" style="background:rgba(13,17,23,0.8);color:#fff;border:1px solid rgba(255,255,255,0.1);border-radius:6px;padding:3px 8px;font-size:0.85rem;cursor:pointer;">
          <option value="0.5">0.5x</option>
          <option value="0.75">0.75x</option>
          <option value="1.0" selected>1.0x (Normal)</option>
          <option value="1.25">1.25x</option>
          <option value="1.5">1.5x</option>
          <option value="2.0">2.0x</option>
        </select>
      </label>
      <label style="color:#8b949e;font-size:0.85rem;display:flex;align-items:center;gap:0.4rem;">Subtitles: 
        <select id="subSelect" onchange="changeSubtitleTrack(this.value)" style="background:rgba(13,17,23,0.8);color:#fff;border:1px solid rgba(255,255,255,0.1);border-radius:6px;padding:3px 8px;font-size:0.85rem;cursor:pointer;">
          <option value="off">Off</option>
        </select>
      </label>
      <button class="btn-sm" style="background:rgba(88,166,255,0.15);color:#58a6ff;border:1px solid rgba(88,166,255,0.3);padding:3px 10px;" onclick="document.getElementById('subFileInput').click()">📁 Load Local Subtitle</button>
      <button class="btn-sm" style="background:rgba(46,160,67,0.15);color:#3fb950;border:1px solid rgba(46,160,67,0.3);padding:3px 10px;" onclick="browseServerSubtitles()">🌐 Browse Server Subtitle</button>
      <input type="file" id="subFileInput" accept=".srt,.vtt" style="display:none" onchange="handleLocalSubtitle(event)">
    </div>
  </div>
</div>

<!-- Sticky Bottom Audio Player Bar -->
<div id="audioPlayerBar" class="audio-player-bar" style="display:none;">
  <div class="audio-track-info">
    <span class="audio-icon">&#x1F3B5;</span>
    <span id="audioTrackTitle" class="audio-title">Track Name</span>
  </div>
  <audio id="mediaAudioPlayer" controls autoplay></audio>
  <button onclick="closeAudioPlayer()" class="audio-close-btn" title="Close player">&#x2715;</button>
</div>

<!-- Server Subtitle File Picker Modal -->
<div id="serverSubModal" class="overlay" style="display:none;z-index:1100;">
  <div class="media-modal-card" style="max-width:480px;">
    <div class="media-modal-header">
      <h3>🌐 Server Subtitles</h3>
      <button class="overlay-close-btn" onclick="closeServerSubModal()">&#x2715; Close</button>
    </div>
    <div style="margin-top:1rem;" id="serverSubList"></div>
  </div>
</div>

<!-- Upload File Conflict Modal -->
<div id="uploadConflictModal" class="overlay" style="display:none;z-index:1150;">
  <div class="overlay-card" style="width:380px;text-align:left;padding:2rem;">
    <h3 style="color:#f2cc60;margin-bottom:0.75rem;display:flex;align-items:center;gap:0.5rem;font-size:1.2rem;">
      <span>⚠️</span> File Already Exists
    </h3>
    <p id="uploadConflictText" style="color:#8b949e;font-size:0.9rem;line-height:1.5;margin-bottom:1.25rem;">
      A file with the same name already exists in this folder. What would you like to do?
    </p>
    <div style="display:flex;flex-direction:column;gap:0.6rem;">
      <button class="btn" style="background:linear-gradient(135deg,#1f6feb,#388bfd);color:#fff;padding:0.6rem 1rem;font-weight:600;border-radius:8px;cursor:pointer;" onclick="resolveUploadConflict('rename')">
        📄 Keep Both (Auto-Rename)
      </button>
      <button class="btn" style="background:rgba(248,81,73,0.15);color:#f85149;border:1px solid rgba(248,81,73,0.3);padding:0.6rem 1rem;font-weight:600;border-radius:8px;cursor:pointer;" onclick="resolveUploadConflict('replace')">
        🔄 Replace Existing File
      </button>
      <button class="btn-clear" style="background:transparent;color:#8b949e;border:1px solid rgba(255,255,255,0.1);padding:0.5rem 1rem;border-radius:8px;cursor:pointer;margin-top:0.2rem;" onclick="resolveUploadConflict('cancel')">
        ✕ Cancel Upload
      </button>
    </div>
  </div>
</div>

<div id="approvalOverlay" class="overlay" style="display:none;">
  <div class="overlay-card">
    <div class="spinner"></div>
    <h3 id="approvalTitle">Waiting for Approval</h3>
    <p id="approvalMsg">Please approve this action on your phone.</p>
    <button class="overlay-close-btn" onclick="hideApprovalOverlay()">Dismiss</button>
  </div>
</div>
<script>
function showApprovalOverlay(actionText, msg) {
  var overlay = document.getElementById('approvalOverlay');
  var title = document.getElementById('approvalTitle');
  var p = document.getElementById('approvalMsg');
  if (actionText) title.textContent = actionText;
  if (msg) p.textContent = msg;
  overlay.style.display = 'flex';
}
function hideApprovalOverlay() {
  document.getElementById('approvalOverlay').style.display = 'none';
}
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
  
  showApprovalOverlay('Approving Download', 'Please approve the download request on your phone.');
  setTimeout(hideApprovalOverlay, 6000);

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
  showApprovalOverlay('Approving Deletion', 'Please approve deleting "' + name + '" on your phone.');
  fetch('/delete?path=' + enc, {method:'DELETE'})
    .then(function(r){ return r.json(); })
    .then(function(j){
      hideApprovalOverlay();
      if (j.ok) { toast('Deleted: ' + name, true); setTimeout(function(){ location.reload(); }, 700); }
      else { toast('Error: ' + j.error, false); }
    })
    .catch(function(){ hideApprovalOverlay(); toast('Delete failed', false); });
}
function deleteSelected() {
  var checked = Array.from(document.querySelectorAll('.item-cb:checked'));
  if (!checked.length) return;
  if (!confirm('Delete ' + checked.length + ' item(s)? This cannot be undone.')) return;
  var total = checked.length; var done = 0;
  showApprovalOverlay('Approving Deletion', 'Please approve the deletion requests on your phone.');
  checked.forEach(function(cb) {
    fetch('/delete?path=' + cb.dataset.path, {method:'DELETE'})
      .then(function(r){ return r.json(); })
      .then(function(){ done++; if (done === total) { hideApprovalOverlay(); toast('Deleted ' + total + ' item(s)', true); setTimeout(function(){ location.reload(); }, 700); } })
      .catch(function(){ done++; if (done === total) { hideApprovalOverlay(); } });
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
var pendingUploadConflictCallback = null;

function resolveUploadConflict(mode) {
  if (pendingUploadConflictCallback) {
    var cb = pendingUploadConflictCallback;
    pendingUploadConflictCallback = null;
    cb(mode);
  }
}

function uploadFiles() {
  var inp = document.getElementById('fileInput');
  var prog = document.getElementById('prog');
  var container = document.getElementById('progContainer');
  var bar = document.getElementById('progBar');
  if (!inp.files.length) { toast('Select at least one file', false); return; }
  
  var files = Array.from(inp.files);

  fetch('/api/files?path=' + encodeURIComponent(currentPath))
    .then(function(r){ return r.json(); })
    .then(function(items){
      var existingNames = (items || []).map(function(it){ return (it.name || '').toLowerCase(); });
      var conflictingFiles = files.filter(function(f){
        return existingNames.indexOf(f.name.toLowerCase()) !== -1;
      });

      if (conflictingFiles.length > 0) {
        var conflictModal = document.getElementById('uploadConflictModal');
        var conflictText = document.getElementById('uploadConflictText');
        var namesStr = conflictingFiles.map(function(f){ return '"' + f.name + '"'; }).join(', ');
        conflictText.innerHTML = 'The file ' + namesStr + ' already exists in this folder.<br>Choose whether to replace the existing file or keep both files (new file will be auto-renamed):';
        conflictModal.style.display = 'flex';

        pendingUploadConflictCallback = function(mode) {
          conflictModal.style.display = 'none';
          if (mode === 'cancel') return;
          startUploadingQueue(files, mode);
        };
      } else {
        startUploadingQueue(files, 'rename');
      }
    })
    .catch(function(){
      startUploadingQueue(files, 'rename');
    });
}

function startUploadingQueue(files, conflictMode) {
  var prog = document.getElementById('prog');
  var container = document.getElementById('progContainer');
  var bar = document.getElementById('progBar');
  var currentIndex = 0;
  
  container.style.display = 'block';
  bar.style.width = '0%';
  showApprovalOverlay('Approving Upload', 'Please approve uploading files on your phone.');
  
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
    xhr.open('POST', '/upload?path=' + encodeURIComponent(currentPath) + '&conflict=' + conflictMode, true);
    
    xhr.upload.onprogress = function(e) {
      hideApprovalOverlay();
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

function filterItems() {
  var query = document.getElementById('searchInput').value.toLowerCase().trim();
  
  // 1. Filter table rows
  var rows = document.querySelectorAll('#rows tr');
  var visibleRows = 0;
  rows.forEach(function(row) {
    if (row.id === 'tableEmptyRow') return;
    var link = row.querySelector('.folder-link');
    if (link && link.textContent.trim() === '..') return;
    
    var name = '';
    var nameSpan = row.querySelector('.file-name');
    if (nameSpan) name = nameSpan.textContent;
    else if (link) name = link.textContent;
    
    name = name.toLowerCase();
    if (name.includes(query)) {
      row.style.display = '';
      visibleRows++;
    } else {
      row.style.display = 'none';
    }
  });

  var tableEmptyRow = document.getElementById('tableEmptyRow');
  if (tableEmptyRow) {
    if (visibleRows === 0 && query !== "") {
      tableEmptyRow.style.display = '';
      tableEmptyRow.querySelector('.empty-text').textContent = '🔍 No matching files found';
    } else if (rows.length === 0 || (rows.length === 1 && rows[0].querySelector('.folder-link') && rows[0].querySelector('.folder-link').textContent.trim() === '..')) {
      tableEmptyRow.style.display = '';
      tableEmptyRow.querySelector('.empty-text').innerHTML = '&#x1F4C1; This folder is empty';
    } else {
      tableEmptyRow.style.display = 'none';
    }
  }

  // 2. Filter grid items
  var gridItems = document.querySelectorAll('#gridContainer .grid-item');
  var visibleGridItems = 0;
  gridItems.forEach(function(item) {
    var nameDiv = item.querySelector('.grid-name');
    var name = nameDiv ? nameDiv.textContent.toLowerCase() : "";
    if (name.includes(query)) {
      item.style.display = '';
      visibleGridItems++;
    } else {
      item.style.display = 'none';
    }
  });

  var gridEmpty = document.querySelector('#gridContainer .grid-empty');
  if (gridEmpty) {
    if (visibleGridItems === 0 && query !== "") {
      gridEmpty.style.display = 'flex';
      gridEmpty.textContent = '🔍 No matching files found';
    } else if (gridItems.length === 0) {
      gridEmpty.style.display = 'flex';
      gridEmpty.innerHTML = '&#x1F4C1; This folder is empty';
    } else {
      gridEmpty.style.display = 'none';
    }
  }
}

var currentSort = {
  column: 'name',
  asc: true
};

function sortTable(col) {
  if (currentSort.column === col) {
    currentSort.asc = !currentSort.asc;
  } else {
    currentSort.column = col;
    currentSort.asc = true;
  }
  
  // Update header icons
  document.querySelectorAll('thead th .sort-icon').forEach(function(el) {
    el.textContent = '⇅';
  });
  var activeHeaderIcon = document.querySelector('#th-' + col + ' .sort-icon');
  if (activeHeaderIcon) {
    activeHeaderIcon.textContent = currentSort.asc ? '▲' : '▼';
  }

  // 1. Sort Table List Rows
  var tbody = document.getElementById('rows');
  if (tbody) {
    var rowsArray = Array.from(tbody.querySelectorAll('tr'));
    var parentRow = null;
    var emptyRow = null;
    var itemRows = [];
    
    rowsArray.forEach(function(row) {
      if (row.id === 'tableEmptyRow') {
        emptyRow = row;
      } else {
        var link = row.querySelector('.folder-link');
        if (link && link.textContent.trim().endsWith('..')) {
          parentRow = row;
        } else {
          itemRows.push(row);
        }
      }
    });

    itemRows.sort(function(a, b) {
      var cbA = a.querySelector('.item-cb');
      var cbB = b.querySelector('.item-cb');
      if (!cbA || !cbB) return 0;
      
      var isDirA = cbA.dataset.type === 'folder';
      var isDirB = cbB.dataset.type === 'folder';
      
      if (isDirA && !isDirB) return -1;
      if (!isDirA && isDirB) return 1;
      
      var valA, valB;
      if (col === 'name') {
        valA = cbA.dataset.name.toLowerCase();
        valB = cbB.dataset.name.toLowerCase();
      } else if (col === 'size') {
        valA = parseInt(cbA.dataset.size || '0');
        valB = parseInt(cbB.dataset.size || '0');
      } else if (col === 'modified') {
        valA = parseInt(cbA.dataset.modified || '0');
        valB = parseInt(cbB.dataset.modified || '0');
      }
      
      if (valA < valB) return currentSort.asc ? -1 : 1;
      if (valA > valB) return currentSort.asc ? 1 : -1;
      return 0;
    });
    
    tbody.innerHTML = '';
    if (parentRow) tbody.appendChild(parentRow);
    itemRows.forEach(function(row) {
      tbody.appendChild(row);
    });
    if (emptyRow) tbody.appendChild(emptyRow);
  }

  // 2. Sort Grid View Items
  var gridContainer = document.getElementById('gridContainer');
  if (gridContainer) {
    var gridArray = Array.from(gridContainer.querySelectorAll('.grid-item'));
    var gridEmpty = gridContainer.querySelector('.grid-empty');
    
    gridArray.sort(function(a, b) {
      var cbA = a.querySelector('.item-cb');
      var cbB = b.querySelector('.item-cb');
      if (!cbA || !cbB) return 0;
      
      var isDirA = cbA.dataset.type === 'folder';
      var isDirB = cbB.dataset.type === 'folder';
      
      if (isDirA && !isDirB) return -1;
      if (!isDirA && isDirB) return 1;
      
      var valA, valB;
      if (col === 'name') {
        valA = cbA.dataset.name.toLowerCase();
        valB = cbB.dataset.name.toLowerCase();
      } else if (col === 'size') {
        valA = parseInt(cbA.dataset.size || '0');
        valB = parseInt(cbB.dataset.size || '0');
      } else if (col === 'modified') {
        valA = parseInt(cbA.dataset.modified || '0');
        valB = parseInt(cbB.dataset.modified || '0');
      }
      
      if (valA < valB) return currentSort.asc ? -1 : 1;
      if (valA > valB) return currentSort.asc ? 1 : -1;
      return 0;
    });
    
    // Re-append items in order
    gridArray.forEach(function(item) {
      gridContainer.appendChild(item);
    });
    if (gridEmpty) gridContainer.appendChild(gridEmpty);
  }
}

// --- Media Player Functions -----------------------------------
var videoExtensions = ['mp4','mkv','avi','mov','webm','3gp','m4v','ts','ogv','flv'];
var audioExtensions = ['mp3','wav','flac','aac','ogg','m4a','opus','wma','mid','midi'];
var imageExtensions = ['jpg','jpeg','png','gif','webp','svg','bmp'];

var activeVideoEnc = '';

function openMediaPlayer(enc, name, ext) {
  ext = (ext || '').toLowerCase();
  activeVideoEnc = enc;
  showApprovalOverlay('Approving Stream', 'Please approve media stream request on your phone.');
  setTimeout(hideApprovalOverlay, 4000);

  var streamUrl = '/stream?path=' + enc + '&inline=true';

  if (audioExtensions.indexOf(ext) !== -1) {
    closeMediaModal();
    var audioBar = document.getElementById('audioPlayerBar');
    var audioEl = document.getElementById('mediaAudioPlayer');
    var titleEl = document.getElementById('audioTrackTitle');
    
    titleEl.textContent = name;
    audioEl.src = streamUrl;
    audioBar.style.display = 'flex';
    audioEl.play().catch(function(_){});
    toast('Streaming audio: ' + name, true);
  } else if (videoExtensions.indexOf(ext) !== -1 || imageExtensions.indexOf(ext) !== -1) {
    var modal = document.getElementById('mediaModal');
    var title = document.getElementById('mediaTitle');
    var vPlayer = document.getElementById('mediaVideoPlayer');
    var imgViewer = document.getElementById('mediaImageViewer');
    var extraCtrl = document.getElementById('mediaExtraControls');
    var gOverlay = document.getElementById('gestureOverlay');

    title.textContent = name;

    if (videoExtensions.indexOf(ext) !== -1) {
      imgViewer.style.display = 'none';
      vPlayer.style.display = 'block';
      extraCtrl.style.display = 'flex';
      gOverlay.style.display = 'block';
      var customCtrl = document.getElementById('videoCustomControls');
      if (customCtrl) customCtrl.style.display = 'flex';
      document.getElementById('speedSelect').value = '1.0';
      var qSel = document.getElementById('qualitySelect');
      if (qSel) qSel.value = 'original';
      vPlayer.crossOrigin = 'anonymous';
      vPlayer.preload = 'metadata';
      vPlayer.src = streamUrl;

      var errNotice = document.getElementById('videoErrorNotice');
      if (errNotice) errNotice.style.display = 'none';

      var stallTimer = null;
      vPlayer.onwaiting = function() {
        if (stallTimer) clearTimeout(stallTimer);
        stallTimer = setTimeout(function() {
          if (vPlayer.paused) return;
          var currTime = vPlayer.currentTime;
          toast('Hotspot connection recovering...', true);
          vPlayer.src = streamUrl;
          vPlayer.currentTime = currTime;
          vPlayer.play().catch(function(_){});
        }, 3500);
      };
      vPlayer.onplaying = function() {
        if (stallTimer) clearTimeout(stallTimer);
      };

      vPlayer.onerror = function() {
        if (errNotice) {
          errNotice.style.display = 'block';
          var dlLink = document.getElementById('videoDownloadLink');
          if (dlLink) dlLink.href = streamUrl;
        }
      };

      if (ext === 'mkv') {
        toast('MKV file: If browser fails, tap 🚀 Open in VLC', true);
      }

      clearSubtitleTracks();
      fetchServerSubtitles(enc);
      initGestureOverlay();
      initVideoControls();
      vPlayer.play().catch(function(_){});
    } else {
      vPlayer.pause();
      vPlayer.style.display = 'none';
      extraCtrl.style.display = 'none';
      gOverlay.style.display = 'none';
      var customCtrl = document.getElementById('videoCustomControls');
      if (customCtrl) customCtrl.style.display = 'none';
      imgViewer.style.display = 'block';
      imgViewer.src = streamUrl;
    }
    modal.style.display = 'flex';
  } else {
    window.open(streamUrl, '_blank');
  }
}

function clearSubtitleTracks() {
  var vPlayer = document.getElementById('mediaVideoPlayer');
  var tracks = vPlayer.querySelectorAll('track');
  tracks.forEach(function(t){ t.remove(); });
  var subSel = document.getElementById('subSelect');
  subSel.innerHTML = '<option value="off">Off</option>';
}

function fetchServerSubtitles(enc) {
  fetch('/api/subtitles?path=' + enc)
    .then(function(r){ return r.json(); })
    .then(function(data){
      var subSel = document.getElementById('subSelect');
      var vPlayer = document.getElementById('mediaVideoPlayer');
      var items = (data && data.items) ? data.items : [];
      var subFiles = items.filter(function(it){ return it.type === 'file'; });

      if (subFiles.length > 0) {
        subFiles.forEach(function(item, idx){
          var opt = document.createElement('option');
          opt.value = item.path;
          opt.textContent = item.label;
          subSel.appendChild(opt);
          
          var track = document.createElement('track');
          track.kind = 'subtitles';
          track.label = item.label;
          track.src = '/subtitle?path=' + item.path;
          if (idx === 0) {
            track.default = true;
            opt.selected = true;
          }
          vPlayer.appendChild(track);
        });

        setTimeout(function() {
          if (vPlayer.textTracks && vPlayer.textTracks.length > 0) {
            for (var i = 0; i < vPlayer.textTracks.length; i++) {
              vPlayer.textTracks[i].mode = (i === 0) ? 'showing' : 'disabled';
            }
          }
        }, 500);

        toast('Found ' + subFiles.length + ' subtitle track(s) — Auto-enabled', true);
      }
    })
    .catch(function(_){});
}

function changeSubtitleTrack(val) {
  var vPlayer = document.getElementById('mediaVideoPlayer');
  var tracks = vPlayer.textTracks;
  for (var i = 0; i < tracks.length; i++) {
    if (val === 'off') {
      tracks[i].mode = 'disabled';
    } else {
      var trackSrc = tracks[i].src || '';
      if (trackSrc.indexOf(val) !== -1 || val.indexOf(trackSrc) !== -1) {
        tracks[i].mode = 'showing';
      } else {
        tracks[i].mode = 'disabled';
      }
    }
  }
}

function handleLocalSubtitle(e) {
  var file = e.target.files[0];
  if (!file) return;
  var reader = new FileReader();
  reader.onload = function(evt) {
    var text = evt.target.result;
    var vttText = text;
    if (file.name.toLowerCase().endsWith('.srt')) {
      vttText = 'WEBVTT\n\n' + text.replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2');
    }
    var blob = new Blob([vttText], { type: 'text/vtt' });
    var blobUrl = URL.createObjectURL(blob);

    var vPlayer = document.getElementById('mediaVideoPlayer');
    var track = document.createElement('track');
    track.kind = 'subtitles';
    track.label = 'Local: ' + file.name;
    track.src = blobUrl;
    track.default = true;
    vPlayer.appendChild(track);

    var subSel = document.getElementById('subSelect');
    var opt = document.createElement('option');
    opt.value = blobUrl;
    opt.textContent = 'Local: ' + file.name;
    opt.selected = true;
    subSel.appendChild(opt);

    for (var i = 0; i < vPlayer.textTracks.length; i++) {
      vPlayer.textTracks[i].mode = 'disabled';
    }
    vPlayer.textTracks[vPlayer.textTracks.length - 1].mode = 'showing';
    toast('Loaded subtitle: ' + file.name, true);
  };
  reader.readAsText(file);
}

function closeMediaModal() {
  var modal = document.getElementById('mediaModal');
  var vPlayer = document.getElementById('mediaVideoPlayer');
  var container = document.getElementById('mediaContainer');
  if (container) container.classList.remove('fullscreen-mode');
  document.body.classList.remove('v-fullscreen-active');
  if (vPlayer) { vPlayer.pause(); vPlayer.src = ''; }
  var customCtrl = document.getElementById('videoCustomControls');
  if (customCtrl) customCtrl.style.display = 'none';
  modal.style.display = 'none';
}

function openInVlcPlayer() {
  if (!activeVideoEnc) return;
  var streamUrl = window.location.origin + '/stream?path=' + activeVideoEnc + '&inline=true';
  var vlcUrl = 'vlc://' + streamUrl;
  var win = window.open(vlcUrl, '_blank');
  setTimeout(function() {
    window.open(streamUrl, '_blank');
  }, 800);
}

function closeAudioPlayer() {
  var audioBar = document.getElementById('audioPlayerBar');
  var audioEl = document.getElementById('mediaAudioPlayer');
  if (audioEl) { audioEl.pause(); audioEl.src = ''; }
  audioBar.style.display = 'none';
}

function changePlaybackSpeed(val) {
  var vPlayer = document.getElementById('mediaVideoPlayer');
  if (vPlayer) { vPlayer.playbackRate = parseFloat(val); }
}



var isSeeking = false;
var hideControlsTimer = null;

function resetControlsTimeout() {
  var ctrl = document.getElementById('videoCustomControls');
  if (!ctrl) return;
  ctrl.style.opacity = '1';
  if (hideControlsTimer) clearTimeout(hideControlsTimer);
  var vPlayer = document.getElementById('mediaVideoPlayer');
  if (vPlayer && !vPlayer.paused) {
    hideControlsTimer = setTimeout(function() {
      if (!isSeeking) ctrl.style.opacity = '0';
    }, 3500);
  }
}

function initVideoControls() {
  var vPlayer = document.getElementById('mediaVideoPlayer');
  var container = document.getElementById('mediaContainer');
  var playBtn = document.getElementById('vBtnPlayPause');
  var timeDisp = document.getElementById('vTimeDisplay');
  var seekBar = document.getElementById('vSeekSlider');
  var muteBtn = document.getElementById('vBtnMute');
  var volBar = document.getElementById('vVolumeSlider');

  if (!vPlayer) return;

  if (container) {
    container.onmousemove = resetControlsTimeout;
    container.onclick = resetControlsTimeout;
    container.ontouchstart = resetControlsTimeout;
  }

  vPlayer.ontimeupdate = function() {
    if (isSeeking) return;
    if (vPlayer.duration) {
      var pct = (vPlayer.currentTime / vPlayer.duration) * 100;
      if (seekBar) seekBar.value = pct;
      if (timeDisp) timeDisp.textContent = formatTimeSec(vPlayer.currentTime) + ' / ' + formatTimeSec(vPlayer.duration);
    }
  };

  vPlayer.onplay = function() {
    if (playBtn) playBtn.textContent = '⏸';
    resetControlsTimeout();
  };

  vPlayer.onpause = function() {
    if (playBtn) playBtn.textContent = '▶';
    var ctrl = document.getElementById('videoCustomControls');
    if (ctrl) ctrl.style.opacity = '1';
  };

  vPlayer.onvolumechange = function() {
    if (!vPlayer) return;
    if (vPlayer.muted || vPlayer.volume === 0) {
      if (muteBtn) muteBtn.textContent = '🔇';
      if (volBar) volBar.value = 0;
    } else {
      if (muteBtn) muteBtn.textContent = '🔊';
      if (volBar) volBar.value = vPlayer.volume;
    }
  };
}

function formatTimeSec(sec) {
  if (isNaN(sec)) return '00:00';
  var m = Math.floor(sec / 60);
  var s = Math.floor(sec % 60);
  if (m < 10) m = '0' + m;
  if (s < 10) s = '0' + s;
  return m + ':' + s;
}

function togglePlayPause() {
  var vPlayer = document.getElementById('mediaVideoPlayer');
  if (!vPlayer) return;
  if (vPlayer.paused) {
    vPlayer.play().catch(function(_){});
  } else {
    vPlayer.pause();
  }
}

function onSeekInput(val) {
  isSeeking = true;
}

function onSeekChange(val) {
  var vPlayer = document.getElementById('mediaVideoPlayer');
  if (vPlayer && vPlayer.duration) {
    vPlayer.currentTime = (val / 100) * vPlayer.duration;
  }
  isSeeking = false;
}

function toggleMute() {
  var vPlayer = document.getElementById('mediaVideoPlayer');
  if (vPlayer) vPlayer.muted = !vPlayer.muted;
}

function onVolumeChange(val) {
  var vPlayer = document.getElementById('mediaVideoPlayer');
  if (!vPlayer) return;
  vPlayer.volume = parseFloat(val);
  vPlayer.muted = (parseFloat(val) === 0);
}

function toggleVideoFullscreen() {
  var container = document.getElementById('mediaContainer');
  if (!container) return;
  var isFs = container.classList.contains('fullscreen-mode') || document.fullscreenElement || document.webkitFullscreenElement;
  if (!isFs) {
    container.classList.add('fullscreen-mode');
    document.body.classList.add('v-fullscreen-active');
    if (container.requestFullscreen) {
      container.requestFullscreen().catch(function(_){});
    } else if (container.webkitRequestFullscreen) {
      container.webkitRequestFullscreen();
    }
  } else {
    container.classList.remove('fullscreen-mode');
    document.body.classList.remove('v-fullscreen-active');
    if (document.exitFullscreen) {
      document.exitFullscreen().catch(function(_){});
    } else if (document.webkitExitFullscreen) {
      document.webkitExitFullscreen();
    }
  }
}

// --- Gesture Controls -----------------------------------------
var lastTapTime = 0;
var lastTapX = 0;
var touchStartX = 0;
var touchStartY = 0;
var isSwiping = false;
var hudTimer = null;

function showGestureBadge(badgeId) {
  var badge = document.getElementById(badgeId);
  if (!badge) return;
  badge.classList.remove('show');
  void badge.offsetWidth;
  badge.classList.add('show');
  setTimeout(function(){ badge.classList.remove('show'); }, 600);
}

function showHUD(text) {
  var hud = document.getElementById('gestureHUD');
  if (!hud) return;
  hud.textContent = text;
  hud.style.display = 'block';
  clearTimeout(hudTimer);
  hudTimer = setTimeout(function(){ hud.style.display = 'none'; }, 1500);
}

var isPointerPressed = false;

function initGestureOverlay() {
  var overlay = document.getElementById('gestureOverlay');
  var vPlayer = document.getElementById('mediaVideoPlayer');
  if (!overlay || !vPlayer) return;

  overlay.onpointerdown = function(e) {
    isPointerPressed = true;
    touchStartX = e.clientX;
    touchStartY = e.clientY;
    isSwiping = false;
    try { overlay.setPointerCapture(e.pointerId); } catch(_){}
  };

  overlay.onpointerup = function(e) {
    isPointerPressed = false;
    try { overlay.releasePointerCapture(e.pointerId); } catch(_){}
  };

  overlay.onpointercancel = function(e) {
    isPointerPressed = false;
  };

  overlay.onpointermove = function(e) {
    if (!isPointerPressed || (e.pointerType === 'mouse' && e.buttons === 0)) return;

    var dx = e.clientX - touchStartX;
    var dy = e.clientY - touchStartY;

    if (Math.abs(dy) > 30 && Math.abs(dy) > Math.abs(dx)) {
      isSwiping = true;
      var rect = overlay.getBoundingClientRect();
      var isRightSide = (touchStartX - rect.left) > (rect.width / 2);

      if (isRightSide) {
        var deltaVol = -dy / rect.height;
        var newVol = Math.max(0, Math.min(1, vPlayer.volume + deltaVol * 0.1));
        vPlayer.volume = newVol;
        showHUD('🔊 Volume: ' + Math.round(newVol * 100) + '%');
        touchStartY = e.clientY;
      }
    } else if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy)) {
      isSwiping = true;
      var deltaSeek = (dx / overlay.clientWidth) * 60;
      var targetTime = Math.max(0, Math.min(vPlayer.duration || 0, vPlayer.currentTime + deltaSeek * 0.05));
      vPlayer.currentTime = targetTime;
      var sign = deltaSeek > 0 ? '+' : '';
      showHUD('⏩ Seek: ' + sign + Math.round(deltaSeek) + 's');
      touchStartX = e.clientX;
    }
  };

  overlay.onclick = function(e) {
    if (isSwiping) return;
    var now = Date.now();
    var rect = overlay.getBoundingClientRect();
    var clickX = e.clientX - rect.left;
    var width = rect.width;

    if (now - lastTapTime < 300 && Math.abs(clickX - lastTapX) < 100) {
      if (clickX < width * 0.35) {
        vPlayer.currentTime = Math.max(0, vPlayer.currentTime - 10);
        showGestureBadge('gestureBadgeLeft');
      } else if (clickX > width * 0.65) {
        vPlayer.currentTime = Math.min(vPlayer.duration || 0, vPlayer.currentTime + 10);
        showGestureBadge('gestureBadgeRight');
      } else {
        if (vPlayer.paused) vPlayer.play(); else vPlayer.pause();
        showGestureBadge('gestureBadgeCenter');
      }
      lastTapTime = 0;
    } else {
      lastTapTime = now;
      lastTapX = clickX;
    }
  };
}

function browseServerSubtitles(targetPath) {
  var serverSubModal = document.getElementById('serverSubModal');
  if (!serverSubModal) return;
  serverSubModal.style.display = 'flex';
  var listEl = document.getElementById('serverSubList');
  listEl.innerHTML = '<div style="color:#8b949e;padding:1rem;">Scanning folder for subtitles...</div>';

  var pathEnc = targetPath ? targetPath : (activeVideoEnc ? activeVideoEnc : encodeURIComponent(currentPath));

  fetch('/api/subtitles?path=' + pathEnc)
    .then(function(r){ return r.json(); })
    .then(function(data){
      var items = (data && data.items) ? data.items : [];
      if (!items.length) {
        listEl.innerHTML = '<div style="color:#8b949e;padding:1rem;">No subtitle files or subfolders found in this directory.</div>';
        return;
      }

      var html = '<div style="display:flex;flex-direction:column;gap:0.5rem;max-height:300px;overflow-y:auto;">';
      items.forEach(function(f){
        if (f.type === 'folder') {
          html += '<button class="btn-sm" style="background:rgba(88,166,255,0.1);color:#58a6ff;border:1px solid rgba(88,166,255,0.2);padding:8px 12px;text-align:left;width:100%;display:flex;justify-content:space-between;align-items:center;cursor:pointer;" onclick="browseServerSubtitles(\'' + f.path + '\')">';
          html += '<span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-right:8px;font-weight:600;">' + f.label + '</span>';
          html += '<span style="color:#58a6ff;font-size:0.75rem;flex-shrink:0;">Open 📁</span>';
          html += '</button>';
        } else {
          html += '<button class="btn-sm" style="background:rgba(255,255,255,0.05);color:#e6edf3;border:1px solid rgba(255,255,255,0.1);padding:8px 12px;text-align:left;width:100%;display:flex;justify-content:space-between;align-items:center;cursor:pointer;" onclick="selectServerSubtitleFile(\'' + f.path + '\', \'' + f.name.replace(/'/g, "\\'") + '\')">';
          html += '<span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-right:8px;">' + f.label + '</span>';
          html += '<span style="color:#3fb950;font-size:0.75rem;flex-shrink:0;">Select</span>';
          html += '</button>';
        }
      });
      html += '</div>';
      listEl.innerHTML = html;
    })
    .catch(function(){
      listEl.innerHTML = '<div style="color:#f85149;padding:1rem;">Failed to read server directory.</div>';
    });
}

function closeServerSubModal() {
  document.getElementById('serverSubModal').style.display = 'none';
}

function selectServerSubtitleFile(encPath, name) {
  closeServerSubModal();
  var subUrl = '/subtitle?path=' + encPath;
  var vPlayer = document.getElementById('mediaVideoPlayer');
  
  var track = document.createElement('track');
  track.kind = 'subtitles';
  track.label = 'Server: ' + name;
  track.src = subUrl;
  track.default = true;
  vPlayer.appendChild(track);

  var subSel = document.getElementById('subSelect');
  var opt = document.createElement('option');
  opt.value = subUrl;
  opt.textContent = 'Server: ' + name;
  opt.selected = true;
  subSel.appendChild(opt);

  for (var i = 0; i < vPlayer.textTracks.length; i++) {
    vPlayer.textTracks[i].mode = 'disabled';
  }
  if (vPlayer.textTracks.length > 0) {
    vPlayer.textTracks[vPlayer.textTracks.length - 1].mode = 'showing';
  }
  toast('Loaded server subtitle: ' + name, true);
}

document.addEventListener('keydown', function(e) {
  if (e.key === 'Escape') {
    var container = document.getElementById('mediaContainer');
    var isFs = (container && container.classList.contains('fullscreen-mode')) || document.fullscreenElement || document.webkitFullscreenElement;
    if (isFs) {
      if (container) container.classList.remove('fullscreen-mode');
      document.body.classList.remove('v-fullscreen-active');
      if (document.exitFullscreen) document.exitFullscreen().catch(function(_){});
      else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
    } else {
      closeMediaModal();
      closeServerSubModal();
    }
  }
  
  var video = document.getElementById('mediaVideoPlayer');
  var modal = document.getElementById('mediaModal');
  if (video && modal && modal.style.display !== 'none') {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

    switch(e.key) {
      case ' ':
      case 'k':
      case 'K':
        e.preventDefault();
        if (video.paused) video.play(); else video.pause();
        break;
      case 'ArrowRight':
      case 'l':
      case 'L':
        e.preventDefault();
        video.currentTime += 5;
        break;
      case 'ArrowLeft':
      case 'j':
      case 'J':
        e.preventDefault();
        video.currentTime -= 5;
        break;
      case 'ArrowUp':
        e.preventDefault();
        video.volume = Math.min(1, video.volume + 0.05);
        break;
      case 'ArrowDown':
        e.preventDefault();
        video.volume = Math.max(0, video.volume - 0.05);
        break;
      case 'm':
      case 'M':
        e.preventDefault();
        video.muted = !video.muted;
        break;
      case 'f':
      case 'F':
        e.preventDefault();
        toggleVideoFullscreen();
        break;
    }
    
    // 0-9 seeking
    if (e.key >= '0' && e.key <= '9') {
        e.preventDefault();
        var percent = parseInt(e.key) / 10;
        if (!isNaN(video.duration)) {
            video.currentTime = video.duration * percent;
        }
    }
  }
});

document.addEventListener('fullscreenchange', function() {
  if (!document.fullscreenElement) {
    var container = document.getElementById('mediaContainer');
    if (container) container.classList.remove('fullscreen-mode');
    document.body.classList.remove('v-fullscreen-active');
  }
});
document.addEventListener('webkitfullscreenchange', function() {
  if (!document.webkitFullscreenElement) {
    var container = document.getElementById('mediaContainer');
    if (container) container.classList.remove('fullscreen-mode');
    document.body.classList.remove('v-fullscreen-active');
  }
});
</script>
</body></html>"""
}
