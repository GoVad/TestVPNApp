package com.haruchka.testvpnapp.core.vpn.guardwire

import com.haruchka.testvpnapp.core.model.VpnTrafficStats
import java.util.concurrent.atomic.AtomicLong

/** Потокобезопасные счётчики трафика для туннеля. */
internal class TrafficMeter {

    private val bytesIn = AtomicLong()
    private val bytesOut = AtomicLong()
    private val packetsIn = AtomicLong()
    private val packetsOut = AtomicLong()

    fun onPacketIn(size: Int) {
        bytesIn.addAndGet(size.toLong())
        packetsIn.incrementAndGet()
    }

    fun onPacketOut(size: Int) {
        bytesOut.addAndGet(size.toLong())
        packetsOut.incrementAndGet()
    }

    fun snapshot(): VpnTrafficStats = VpnTrafficStats(
        bytesIn = bytesIn.get(),
        bytesOut = bytesOut.get(),
        packetsIn = packetsIn.get(),
        packetsOut = packetsOut.get(),
    )
}
