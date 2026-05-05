package com.antigravity.pptremote

import android.os.Environment
import android.util.Log
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

class FtpServerManager {
    private var server: FtpServer? = null

    fun start(port: Int = 2121) {
        if (server != null) return

        try {
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = port

            serverFactory.addListener("default", listenerFactory.createListener())

            // Anonymous user with write access to external storage
            val user = BaseUser()
            user.name = "anonymous"
            user.homeDirectory = Environment.getExternalStorageDirectory().absolutePath
            
            val authorities = mutableListOf<Authority>()
            authorities.add(WritePermission())
            user.authorities = authorities

            serverFactory.userManager.save(user)

            server = serverFactory.createServer()
            server?.start()
            Log.i("FtpServerManager", "FTP Server started on port $port")
        } catch (e: Exception) {
            Log.e("FtpServerManager", "Failed to start FTP server", e)
            server = null
        }
    }

    fun stop() {
        try {
            server?.stop()
            server = null
            Log.i("FtpServerManager", "FTP Server stopped")
        } catch (e: Exception) {
            Log.e("FtpServerManager", "Failed to stop FTP server", e)
        }
    }

    fun isRunning(): Boolean = server != null && !server!!.isStopped
}
