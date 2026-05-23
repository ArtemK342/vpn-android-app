package ru.fsociety.vpn

import android.util.Log
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Минимальный tun2socks.
 * Читает IP-пакеты из TUN fd и пересылает:
 *   TCP  → SOCKS5 CONNECT → xray (127.0.0.1:10808) → VLESS → интернет
 *   UDP/53 → прямой DNS (наш UID исключён из VPN через addDisallowedApplication)
 */
class TunSocksForwarder(
    private val tunFd: Int,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 10808
) {
    companion object {
        private const val TAG = "Tun2Socks"
        fun ipStr(b: ByteArray) =
            "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"
    }

    private val pool = Executors.newCachedThreadPool()
    @Volatile var running = false

    private lateinit var tunOut: FileOutputStream
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val seqGen = AtomicInteger(0x5A4D1234)

    // ── Session ──────────────────────────────────────────────────────────────

    inner class TcpSession(
        val clientIp: ByteArray,
        val clientPort: Int,
        val serverIp: ByteArray,
        val serverPort: Int
    ) {
        val key = "${ipStr(clientIp)}:$clientPort>${ipStr(serverIp)}:$serverPort"
        @Volatile var clientSeq = 0   // next expected from client
        @Volatile var ourSeq    = 0   // next we'll send
        @Volatile var sock: Socket? = null
        @Volatile var established = false
        fun close() { try { sock?.close() } catch (_: Exception) {} }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        running = true
        val fd = makeFd(tunFd)
        tunOut = FileOutputStream(fd)
        pool.submit { readLoop(FileInputStream(fd)) }
        Log.i(TAG, "started (tunFd=$tunFd, socks=$socksHost:$socksPort)")
    }

    fun stop() {
        running = false
        sessions.values.forEach { it.close() }
        sessions.clear()
        pool.shutdownNow()
        Log.i(TAG, "stopped")
    }

    // ── Packet reading ───────────────────────────────────────────────────────

    private fun readLoop(tin: FileInputStream) {
        val buf = ByteArray(65535)
        while (running) {
            try {
                val n = tin.read(buf)
                if (n < 20) continue
                val pkt = buf.copyOf(n)
                if ((pkt[0].toInt() and 0xF0) ushr 4 == 4) processIPv4(pkt)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "read: ${e.message}")
            }
        }
    }

    private fun processIPv4(pkt: ByteArray) {
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.size < ihl + 8) return
        val proto = pkt[9].toInt() and 0xFF
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)
        when (proto) {
            6  -> onTcp(pkt, ihl, srcIp, dstIp)
            17 -> onUdp(pkt, ihl, srcIp, dstIp)
        }
    }

    // ── TCP ──────────────────────────────────────────────────────────────────

    private fun onTcp(pkt: ByteArray, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (pkt.size < ihl + 20) return
        val bb = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        val srcPort = bb.getShort(ihl).toInt() and 0xFFFF
        val dstPort = bb.getShort(ihl + 2).toInt() and 0xFFFF
        val seqNum  = bb.getInt(ihl + 4)
        val tcpHdr  = ((pkt[ihl + 12].toInt() and 0xF0) ushr 4) * 4
        val flags   = pkt[ihl + 13].toInt() and 0xFF
        val data    = pkt.copyOfRange(ihl + tcpHdr, pkt.size)

        val syn = flags and 0x02 != 0
        val ack = flags and 0x10 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0
        val key = "${ipStr(srcIp)}:$srcPort>${ipStr(dstIp)}:$dstPort"

        if (syn && !ack) {
            if (sessions.containsKey(key)) return  // уже подключаемся
            val sess = TcpSession(srcIp.clone(), srcPort, dstIp.clone(), dstPort)
            sess.clientSeq = seqNum + 1
            sess.ourSeq    = seqGen.getAndAdd(0x1249)
            sessions[key]  = sess
            pool.submit { connectSession(sess) }
            return
        }

        val sess = sessions[key] ?: return

        if (rst || fin) { sessions.remove(key); sess.close(); return }

        if (data.isNotEmpty() && sess.established) {
            pool.submit {
                try {
                    sess.sock?.getOutputStream()?.write(data)
                    sess.clientSeq += data.size
                    sendAck(sess)
                } catch (e: Exception) { sessions.remove(key); sess.close() }
            }
        }
    }

    private fun connectSession(sess: TcpSession) {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(socksHost, socksPort), 5000)
            socks5Connect(sock, sess.serverIp, sess.serverPort)
            sess.sock = sock
            sess.established = true
            sendSynAck(sess)
            pool.submit { serverToTun(sess) }
        } catch (e: Exception) {
            Log.w(TAG, "→ ${ipStr(sess.serverIp)}:${sess.serverPort}: ${e.message}")
            sessions.remove(sess.key); sess.close()
        }
    }

    private fun socks5Connect(sock: Socket, dstIp: ByteArray, dstPort: Int) {
        val ins  = sock.getInputStream()
        val outs = sock.getOutputStream()
        // Greeting: no auth
        outs.write(byteArrayOf(0x05, 0x01, 0x00)); outs.flush()
        val g = ByteArray(2); readFully(ins, g)
        check(g[1] == 0x00.toByte()) { "socks5 auth rejected" }
        // CONNECT IPv4
        outs.write(byteArrayOf(0x05, 0x01, 0x00, 0x01,
            dstIp[0], dstIp[1], dstIp[2], dstIp[3],
            (dstPort ushr 8).toByte(), dstPort.toByte()))
        outs.flush()
        val r = ByteArray(10); readFully(ins, r)
        check(r[1] == 0x00.toByte()) { "socks5 connect failed: ${r[1]}" }
    }

    private fun serverToTun(sess: TcpSession) {
        val buf = ByteArray(4096)
        try {
            val ins = sess.sock!!.getInputStream()
            while (running) {
                val n = ins.read(buf)
                if (n < 0) break
                val chunk = buf.copyOf(n)
                sendData(sess, chunk)
                sess.ourSeq += n
            }
        } catch (_: Exception) {
        } finally {
            sessions.remove(sess.key); sess.close()
        }
    }

    // ── TCP packet builders ──────────────────────────────────────────────────

    private fun sendSynAck(s: TcpSession) {
        writeTcp(s.serverIp, s.serverPort, s.clientIp, s.clientPort,
            s.ourSeq, s.clientSeq, 0x12, ByteArray(0))
        s.ourSeq += 1
    }

    private fun sendAck(s: TcpSession) =
        writeTcp(s.serverIp, s.serverPort, s.clientIp, s.clientPort,
            s.ourSeq, s.clientSeq, 0x10, ByteArray(0))

    private fun sendData(s: TcpSession, data: ByteArray) =
        writeTcp(s.serverIp, s.serverPort, s.clientIp, s.clientPort,
            s.ourSeq, s.clientSeq, 0x18, data)

    private fun writeTcp(srcIp: ByteArray, srcPort: Int,
                         dstIp: ByteArray, dstPort: Int,
                         seq: Int, ack: Int, flags: Int, data: ByteArray) {
        val tcp = ByteArray(20 + data.size)
        putU16(tcp, 0, srcPort); putU16(tcp, 2, dstPort)
        putU32(tcp, 4, seq);     putU32(tcp, 8, ack)
        tcp[12] = 0x50; tcp[13] = flags.toByte()
        putU16(tcp, 14, 65535)
        if (data.isNotEmpty()) System.arraycopy(data, 0, tcp, 20, data.size)
        putU16(tcp, 16, tcpChecksum(srcIp, dstIp, tcp))
        writeIp(srcIp, dstIp, 6, tcp)
    }

    // ── UDP / DNS ────────────────────────────────────────────────────────────

    private fun onUdp(pkt: ByteArray, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (pkt.size < ihl + 8) return
        val bb      = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        val srcPort = bb.getShort(ihl).toInt() and 0xFFFF
        val dstPort = bb.getShort(ihl + 2).toInt() and 0xFFFF
        val udpData = (bb.getShort(ihl + 4).toInt() and 0xFFFF) - 8
        if (udpData <= 0) return
        val payload = pkt.copyOfRange(ihl + 8, ihl + 8 + udpData)
        if (dstPort == 53) pool.submit { forwardDns(srcIp, srcPort, dstIp, dstPort, payload) }
    }

    private fun forwardDns(srcIp: ByteArray, srcPort: Int,
                            dstIp: ByteArray, dstPort: Int, query: ByteArray) {
        try {
            val ds = DatagramSocket()
            ds.soTimeout = 3000
            ds.send(DatagramPacket(query, query.size, InetAddress.getByAddress(dstIp), dstPort))
            val resp = DatagramPacket(ByteArray(512), 512)
            ds.receive(resp); ds.close()
            writeUdp(dstIp, dstPort, srcIp, srcPort, resp.data.copyOf(resp.length))
        } catch (e: Exception) { Log.d(TAG, "dns: ${e.message}") }
    }

    private fun writeUdp(srcIp: ByteArray, srcPort: Int,
                          dstIp: ByteArray, dstPort: Int, data: ByteArray) {
        val udp = ByteArray(8 + data.size)
        putU16(udp, 0, srcPort); putU16(udp, 2, dstPort)
        putU16(udp, 4, 8 + data.size)
        System.arraycopy(data, 0, udp, 8, data.size)
        writeIp(srcIp, dstIp, 17, udp)
    }

    // ── IP writer ────────────────────────────────────────────────────────────

    @Synchronized
    private fun writeIp(srcIp: ByteArray, dstIp: ByteArray, proto: Int, payload: ByteArray) {
        val total = 20 + payload.size
        val ip = ByteArray(total)
        ip[0] = 0x45; ip[8] = 64; ip[9] = proto.toByte()
        putU16(ip, 2, total)
        System.arraycopy(srcIp, 0, ip, 12, 4)
        System.arraycopy(dstIp, 0, ip, 16, 4)
        putU16(ip, 10, ipChecksum(ip, 20))
        System.arraycopy(payload, 0, ip, 20, payload.size)
        try { tunOut.write(ip) } catch (_: Exception) {}
    }

    // ── Checksums ────────────────────────────────────────────────────────────

    private fun ipChecksum(hdr: ByteArray, len: Int): Int {
        var s = 0; var i = 0
        while (i < len - 1) { s += ((hdr[i].toInt() and 0xFF) shl 8) or (hdr[i+1].toInt() and 0xFF); i += 2 }
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }

    private fun tcpChecksum(srcIp: ByteArray, dstIp: ByteArray, tcp: ByteArray): Int {
        val p = ByteArray(12 + tcp.size)
        System.arraycopy(srcIp, 0, p, 0, 4); System.arraycopy(dstIp, 0, p, 4, 4)
        p[9] = 6; putU16(p, 10, tcp.size)
        System.arraycopy(tcp, 0, p, 12, tcp.size)
        var s = 0; var i = 0
        while (i < p.size - 1) { s += ((p[i].toInt() and 0xFF) shl 8) or (p[i+1].toInt() and 0xFF); i += 2 }
        if (p.size % 2 == 1) s += (p[p.size - 1].toInt() and 0xFF) shl 8
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o+1] = v.toByte() }
    private fun putU32(b: ByteArray, o: Int, v: Int) {
        b[o]=(v ushr 24).toByte(); b[o+1]=(v ushr 16).toByte(); b[o+2]=(v ushr 8).toByte(); b[o+3]=v.toByte()
    }

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) { val n = ins.read(buf, off, buf.size - off); if (n < 0) throw EOFException(); off += n }
    }

    private fun makeFd(fd: Int): FileDescriptor {
        val fds = FileDescriptor()
        val f = FileDescriptor::class.java.getDeclaredField("descriptor")
        f.isAccessible = true; f.set(fds, fd)
        return fds
    }
}
