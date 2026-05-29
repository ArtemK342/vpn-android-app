package ru.fsociety.vpn

import android.net.LocalServerSocket
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SocketProtectServer {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(service: XrayVpnService): String {
        val socketName = service.filesDir.absolutePath + "/protect_path"
        File(socketName).delete()

        val latch = CountDownLatch(1)
        running = true
        thread?.interrupt()

        thread = Thread {
            try {
                val server = LocalServerSocket(socketName)
                latch.countDown()

                while (running && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = server.accept() ?: continue
                        val fds: Array<FileDescriptor>? = client.ancillaryFileDescriptors
                        fds?.forEach { fd ->
                            val fdInt = getFdInt(fd)
                            if (fdInt > 0) service.protect(fdInt)
                        }
                        client.close()
                    } catch (_: Exception) { }
                }
                server.close()
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        thread!!.isDaemon = true
        thread!!.start()

        // Ждём максимум 3 секунды пока сервер начнёт слушать
        latch.await(3, TimeUnit.SECONDS)
        return socketName
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun getFdInt(fd: FileDescriptor): Int {
        return try {
            val f = FileDescriptor::class.java.getDeclaredField("descriptor")
            f.isAccessible = true
            f.getInt(fd)
        } catch (_: Exception) { -1 }
    }
}
