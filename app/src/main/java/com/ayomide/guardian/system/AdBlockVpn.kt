package com.ayomide.guardian.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.ayomide.guardian.core.Blocklist
import com.ayomide.guardian.core.Dns
import com.ayomide.guardian.core.Packets
import com.ayomide.guardian.data.Prefs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * DNS-only VPN for the apps the user picked. Only lookups sent to our virtual
 * DNS address enter the tunnel: ad/tracker names get "no such domain", all
 * other lookups are forwarded to the phone's normal DNS server. Other traffic
 * never touches the VPN, and nothing is sent to any Guardian server.
 */
class AdBlockVpn : VpnService() {

    companion object {
        private const val TAG = "AdBlockVpn"
        const val ACTION_STOP = "stop"
        const val FULL_LIST_FILE = "hosts_full.txt"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"

        @Volatile var running = false
            private set

        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, AdBlockVpn::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
            false
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, AdBlockVpn::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w(TAG, "stop", e)
            }
        }

        /** Built-in list plus the optional downloaded full list. */
        fun loadBlocklist(ctx: Context, prefs: Prefs): Blocklist {
            val set = HashSet<String>()
            try {
                ctx.assets.open("blocklist.txt").bufferedReader().use { Blocklist.parse(it.readText(), set) }
            } catch (e: Exception) {
                Log.w(TAG, "built-in list", e)
            }
            val full = File(ctx.filesDir, FULL_LIST_FILE)
            if (prefs.useFullList && full.exists()) {
                try {
                    full.bufferedReader().useLines { lines -> for (l in lines) Blocklist.parse(l, set) }
                } catch (e: Exception) {
                    Log.w(TAG, "full list", e)
                }
            }
            return Blocklist(set, prefs.allowSites.map { it.lowercase().trim() }.toSet())
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val pendingCount = AtomicInteger()
    @Volatile private var blocklist = Blocklist(emptySet())
    @Volatile private var upstream: InetAddress? = null
    @Volatile private var upstreamCheckedAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.channels(this)
        Notifier.startForeground(this, Notifier.ID_VPN,
            Notifier.builder(this, Notifier.CH_GUARD, "Ad blocker on", "Blocking ads in the apps you chose.")
                .setOngoing(true).setContentIntent(Notifier.openApp(this, 2)).build())
        if (intent?.action == ACTION_STOP) {
            shutdown(); return START_NOT_STICKY
        }
        if (tun == null) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        val prefs = Prefs(this)
        val apps = prefs.adApps
        if (apps.isEmpty() || prepare(this) != null) {
            shutdown(); return
        }
        val builder = Builder()
            .setSession("Guardian ad blocker")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)
            .setMtu(1500)
        var added = 0
        for (pkg in apps) {
            try {
                builder.addAllowedApplication(pkg); added++
            } catch (e: PackageManager.NameNotFoundException) {
                // app was uninstalled
            }
        }
        if (added == 0) {
            shutdown(); return
        }
        val iface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.w(TAG, "establish", e); null
        } ?: return shutdown()
        tun = iface
        running = true
        Tiles.refreshAll(this)
        reader = Thread { loop(iface) }.apply { name = "dns-vpn"; start() }
    }

    private fun loop(iface: ParcelFileDescriptor) {
        blocklist = loadBlocklist(this, Prefs(this))
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n <= 0) continue
                val pkt = Packets.parseUdp(buf, n) ?: continue
                if (pkt.dstPort != 53) continue
                val name = Dns.queryName(pkt.payload) ?: continue
                if (blocklist.isBlocked(name)) {
                    val answer = Dns.nxdomain(pkt.payload) ?: continue
                    write(output, Packets.buildUdp(pkt.dstIp, pkt.srcIp, 53, pkt.srcPort, answer))
                    if (pendingCount.incrementAndGet() >= 20) flushCount()
                } else {
                    pool.execute { forward(output, pkt) }
                }
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "tunnel closed", e)
        }
    }

    private fun forward(output: FileOutputStream, pkt: Packets.UdpPacket) {
        val server = upstreamDns() ?: return
        try {
            DatagramSocket().use { sock ->
                protect(sock)
                sock.soTimeout = 5000
                sock.send(DatagramPacket(pkt.payload, pkt.payload.size, server, 53))
                val resp = ByteArray(4096)
                val dp = DatagramPacket(resp, resp.size)
                sock.receive(dp)
                write(output, Packets.buildUdp(pkt.dstIp, pkt.srcIp, 53, pkt.srcPort, resp.copyOf(dp.length)))
            }
        } catch (e: Exception) {
            // Timeout or network change: the app will simply retry.
        }
    }

    @Synchronized
    private fun write(output: FileOutputStream, bytes: ByteArray) {
        try {
            output.write(bytes)
        } catch (e: Exception) {
            // tunnel closing
        }
    }

    /** The phone's real (non-VPN) IPv4 DNS server, refreshed every 30 s. */
    private fun upstreamDns(): InetAddress? {
        val now = System.currentTimeMillis()
        if (upstream != null && now - upstreamCheckedAt < 30_000) return upstream
        upstreamCheckedAt = now
        val cm = getSystemService(ConnectivityManager::class.java)
        val found = try {
            cm?.allNetworks?.asSequence()
                ?.filter { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == false }
                ?.flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty().asSequence() }
                ?.firstOrNull { it is Inet4Address && !it.hostAddress.orEmpty().startsWith("10.111.222.") }
        } catch (e: Exception) {
            null
        }
        upstream = found ?: InetAddress.getByName("1.1.1.1")
        return upstream
    }

    private fun flushCount() {
        val n = pendingCount.getAndSet(0)
        if (n > 0) Prefs(this).addBlocked(n)
    }

    private fun shutdown() {
        running = false
        flushCount()
        reader?.interrupt()
        reader = null
        try {
            tun?.close()
        } catch (e: Exception) {
            // ignore
        }
        tun = null
        Tiles.refreshAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // The user turned the VPN off in system settings or another VPN took over.
        Prefs(this).adEnabled = false
        shutdown()
    }

    override fun onDestroy() {
        if (running) shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }
}
