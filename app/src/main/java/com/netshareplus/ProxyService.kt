package com.netshareplus

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal explicit HTTP proxy. Supports HTTP requests and HTTPS CONNECT tunnels.
 * Clients must configure this proxy manually; this does not transparently route all traffic.
 */
class ProxyService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7, notification())
        if (!running) {
            running = true
            pool.execute { listen() }
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val channelId = "netshareplus"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "NetShare Plus", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
            return Notification.Builder(this, channelId).setContentTitle("NetShare Plus")
                .setContentText("Прокси-сервер работает").setSmallIcon(android.R.drawable.stat_sys_upload_done).build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this).setContentTitle("NetShare Plus").setContentText("Прокси-сервер работает")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done).build()
    }

    private fun listen() {
        try {
            server = ServerSocket(8282, 50, java.net.InetAddress.getByName("0.0.0.0"))
            while (running) {
                val client = server?.accept() ?: break
                pool.execute { handle(client) }
            }
        } catch (_: Exception) {
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 15000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val headerBytes = ByteArrayOutputStream()
            var matched = 0
            while (headerBytes.size() < 32768) {
                val b = input.read()
                if (b < 0) return
                headerBytes.write(b)
                matched = when {
                    matched == 0 && b == 13 -> 1
                    matched == 1 && b == 10 -> 2
                    matched == 2 && b == 13 -> 3
                    matched == 3 && b == 10 -> 4
                    b == 13 -> 1
                    else -> 0
                }
                if (matched == 4) break
            }
            val header = headerBytes.toString(Charsets.ISO_8859_1.name())
            val first = header.lineSequence().firstOrNull() ?: return
            val parts = first.split(" ")
            if (parts.size < 2) return
            if (parts[0].equals("CONNECT", true)) {
                val target = parts[1].substringBeforeLast(":")
                val port = parts[1].substringAfterLast(":", "443").toIntOrNull() ?: 443
                upstream = Socket(target, port).apply { soTimeout = 0 }
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
                val up = upstream
                val a = Thread { pipe(input, up.getOutputStream()) }
                val b = Thread { pipe(up.getInputStream(), output) }
                a.start()
                b.start()
                a.join()
                b.join()
            } else {
                val uri = java.net.URI(parts[1])
                val host = uri.host ?: header.lineSequence().firstOrNull { it.startsWith("Host:", true) }
                    ?.substringAfter(":")?.trim()?.substringBefore(":") ?: return
                val port = if (uri.port > 0) uri.port else 80
                upstream = Socket(host, port)
                val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val rewritten = header.replaceFirst(parts[1], path)
                upstream.getOutputStream().write(rewritten.toByteArray(Charsets.ISO_8859_1))
                upstream.getOutputStream().flush()
                pipe(input, upstream.getOutputStream(), closeWhenDone = false)
                pipe(upstream.getInputStream(), output)
            }
        } catch (_: Exception) {
            try { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray()) } catch (_: Exception) {}
        } finally {
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, closeWhenDone: Boolean = true) {
        try {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            if (closeWhenDone) try { output.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}