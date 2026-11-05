package com.haruchka.testvpnapp.core.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import com.haruchka.testvpnapp.core.model.VpnTrafficStats
import com.haruchka.testvpnapp.core.protocol.GuardWireConnection
import com.haruchka.testvpnapp.core.protocol.GuardWireException
import com.haruchka.testvpnapp.core.protocol.GuardWireFrameType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мост между TUN-интерфейсом Android и GuardWire-соединением.
 *
 * Две блокирующие корутины перекачивают пакеты в обе стороны, ещё две
 * отвечают за keep-alive и публикацию счётчиков.
 */
internal class GuardWireTunnel(
    private val context: Context,
    private val scope: CoroutineScope,
    private val descriptor: ParcelFileDescriptor,
    private val connection: GuardWireConnection,
    private val keepAliveMillis: Long,
    private val onStats: (VpnTrafficStats) -> Unit,
    private val onFailure: (String) -> Unit,
) : Closeable {

    private val tunInput = FileInputStream(descriptor.fileDescriptor)
    private val tunOutput = FileOutputStream(descriptor.fileDescriptor)
    private val meter = TrafficMeter()
    private val jobs = mutableListOf<Job>()
    private val failureReported = AtomicBoolean(false)
    private val packetBuffer = ByteArray(MAX_PACKET_SIZE)

    @Volatile
    private var closed = false

    fun start() {
        jobs += scope.launch { pumpTunToServer() }
        jobs += scope.launch { pumpServerToTun() }
        jobs += scope.launch { keepAliveLoop() }
        jobs += scope.launch { statsLoop() }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { tunInput.close() }
        runCatching { tunOutput.close() }
        runCatching { connection.close() }
        runCatching { descriptor.close() }
    }

    private fun pumpTunToServer() {
        runPump(name = "TUN → GuardWire") {
            while (!closed) {
                val length = tunInput.read(packetBuffer)
                if (length < 0) return@runPump
                connection.sendData(packetBuffer, length)
                meter.onPacketOut(length)
            }
        }
    }

    private fun pumpServerToTun() {
        runPump(name = "GuardWire → TUN") {
            while (!closed) {
                val frame = connection.receive()
                when (frame.type) {
                    GuardWireFrameType.DATA -> {
                        tunOutput.write(frame.payload)
                        tunOutput.flush()
                        meter.onPacketIn(frame.payload.size)
                    }

                    GuardWireFrameType.PING -> connection.sendPong()

                    GuardWireFrameType.KEEP_ALIVE,
                    GuardWireFrameType.PONG,
                    -> Unit

                    GuardWireFrameType.DISCONNECT ->
                        throw GuardWireException(context.getString(R.string.error_guardwire_server_closed))
                }
            }
        }
    }

    private suspend fun keepAliveLoop() {
        var tick = 0
        try {
            while (scope.isActive && !closed) {
                delay(keepAliveMillis)
                connection.sendKeepAlive()
                tick++
                if (tick % 2 == 0) {
                    connection.sendPing()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed) fail(e.message ?: context.getString(R.string.log_guardwire_keepalive_error))
        }
    }

    private suspend fun statsLoop() {
        try {
            while (scope.isActive && !closed) {
                delay(STATS_INTERVAL_MS)
                if (!closed) {
                    onStats(meter.snapshot())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed) fail(e.message ?: context.getString(R.string.log_guardwire_stats_error))
        }
    }

    private fun runPump(name: String, block: () -> Unit) {
        val job = scope.launch {
            try {
                block()
                if (!closed) fail(context.getString(R.string.log_guardwire_pump_stopped, name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed) fail(e.message ?: context.getString(R.string.log_guardwire_pump_failed, name))
            }
        }
        jobs += job
    }

    private fun fail(message: String) {
        if (failureReported.compareAndSet(false, true)) {
            onFailure(message)
        }
    }

    private companion object {
        const val MAX_PACKET_SIZE = 65_535
        const val STATS_INTERVAL_MS = 1_000L
    }
}
