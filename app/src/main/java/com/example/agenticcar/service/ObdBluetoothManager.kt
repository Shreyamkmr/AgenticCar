package com.example.agenticcar.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class BondedDevice(val name: String, val address: String)

/**
 * Bluetooth Classic SPP link to an ELM327 adapter.
 *
 * Not thread-safe across connections: one instance per connection attempt, owned
 * by [ObdLoggerService]. All I/O runs on [Dispatchers.IO]; [execute] calls are
 * serialised by a [Mutex] because the ELM327 handles one command at a time.
 */
class ObdBluetoothManager(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) : Elm327Link {
    private val ioLock = Mutex()

    @Volatile private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    /** Opens the RFCOMM socket. Blocks up to ~12 s inside the Bluetooth stack. */
    @SuppressLint("MissingPermission") // checked by hasConnectPermission()
    override suspend fun connect(address: String): String = withContext(Dispatchers.IO) {
        check(hasConnectPermission(context)) { "BLUETOOTH_CONNECT permission not granted" }
        val device = adapter.getRemoteDevice(address)
        val name = device.name ?: address

        val secure = device.createRfcommSocketToServiceRecord(SPP_UUID)
        val connected = try {
            connectCancellable(secure)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            // Many ELM327 clones only accept an insecure (unauthenticated) RFCOMM link.
            connectCancellable(device.createInsecureRfcommSocketToServiceRecord(SPP_UUID))
        }

        socket = connected
        input = connected.inputStream
        output = connected.outputStream
        name
    }

    override suspend fun execute(command: String, timeoutMillis: Long): String = ioLock.withLock {
        withContext(Dispatchers.IO) {
            val out = output ?: throw IOException("Not connected")
            val inp = input ?: throw IOException("Not connected")

            // Discard any stale bytes from a previous timed-out command.
            while (inp.available() > 0) inp.read()

            out.write((command + "\r").toByteArray(Charsets.US_ASCII))
            out.flush()
            readUntilPrompt(inp, timeoutMillis)
        }
    }

    override fun close() {
        val s = socket ?: return
        socket = null
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { s.close() }
        input = null
        output = null
    }

    /**
     * Polls [InputStream.available] instead of blocking in read(), so the loop
     * honours both the timeout and coroutine cancellation.
     */
    private suspend fun readUntilPrompt(inp: InputStream, timeoutMillis: Long): String {
        val buffer = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            currentCoroutineContext().ensureActive()
            if (inp.available() > 0) {
                val b = inp.read()
                if (b < 0) throw IOException("Bluetooth stream closed")
                val c = b.toChar()
                if (c == '>') return buffer.toString()
                buffer.append(c)
            } else {
                if (System.currentTimeMillis() > deadline) {
                    throw IOException("ELM327 timeout waiting for prompt (got '${buffer.trim()}')")
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * socket.connect() blocks its thread and ignores interruption. A watchdog child
     * suspended on another thread closes the socket when the caller is cancelled,
     * which makes connect() throw immediately.
     */
    private suspend fun connectCancellable(s: BluetoothSocket): BluetoothSocket = coroutineScope {
        val connected = AtomicBoolean(false)
        val watchdog = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                if (!connected.get()) runCatching { s.close() }
            }
        }
        try {
            s.connect()
            connected.set(true)
            s
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_INTERVAL_MILLIS = 5L

        fun hasConnectPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        @SuppressLint("MissingPermission") // checked by hasConnectPermission()
        fun bondedDevices(context: Context, adapter: BluetoothAdapter?): List<BondedDevice> {
            if (adapter == null || !hasConnectPermission(context)) return emptyList()
            return runCatching {
                adapter.bondedDevices.orEmpty()
                    .map { BondedDevice(name = it.name ?: it.address, address = it.address) }
                    .sortedBy { it.name }
            }.getOrDefault(emptyList())
        }
    }
}
