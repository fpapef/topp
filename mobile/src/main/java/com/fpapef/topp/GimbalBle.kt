package com.fpapef.topp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Liaison Bluetooth LE avec la nacelle (remplace Hohem Joy : ne pas utiliser les deux en même temps). */
@SuppressLint("MissingPermission")
class GimbalBle(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    enum class Status { OFF, SCANNING, CONNECTING, READY }

    data class State(
        val status: Status = Status.OFF,
        val name: String? = null,
        val mode: Int? = null,
        val attitude: Hohem.Attitude? = null,
        val lastSeen: Long = 0L,
    )

    val state = MutableStateFlow(State())
    val frames = MutableSharedFlow<Hohem.Frame>(extraBufferCapacity = 128)

    /** Appareils Bluetooth vus pendant la recherche (pour le choix manuel). */
    data class Seen(val address: String, val name: String?, val rssi: Int, val likely: Boolean)
    val seen = MutableStateFlow<List<Seen>>(emptyList())
    private val seenDevices = HashMap<String, BluetoothDevice>()

    private val manager get() = ctx.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val prefs = ctx.getSharedPreferences("topp", Context.MODE_PRIVATE)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    private var canWriteNoResponse = false
    private var canWriteWithResponse = false
    @Volatile private var wanted = false
    @Volatile private var servicesRequested = false
    private val parser = Hohem.Parser()

    private sealed class Op {
        class Write(val bytes: ByteArray, val fast: Boolean = false) : Op()
        class Notify(val c: BluetoothGattCharacteristic) : Op()
        class Pause(val ms: Long) : Op()
        object Ready : Op()
    }

    private var ops = Channel<Op>(Channel.UNLIMITED)
    private var trackCh = Channel<ByteArray>(Channel.CONFLATED)
    private var opJob: Job? = null
    private var scanTimeout: Job? = null
    private var scanCb: ScanCallback? = null
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var attitudeWaiter: CompletableDeferred<Hohem.Attitude>? = null

    val isReady get() = state.value.status == Status.READY

    fun connect() {
        wanted = true
        if (state.value.status != Status.OFF) return
        val ad = adapter
        if (ad == null) { log("Bluetooth indisponible sur ce téléphone"); return }
        if (!ad.isEnabled) { log("Active le Bluetooth, puis touche Connecter"); return }
        nextIsDirect = true
        startAttempt()
    }

    /** Connexion manuelle à un appareil choisi dans la liste. */
    fun connectAddress(address: String) {
        wanted = true
        stopScan()
        gatt?.let { it.disconnect(); cleanup(it) }
        prefs.edit().putString("lastAddress", address).apply()
        val dev = seenDevices[address] ?: remoteDevice(address) ?: return
        connectTo(dev, seen.value.firstOrNull { it.address == address }?.name)
    }

    fun disconnect() {
        wanted = false
        stopScan()
        gatt?.let { it.disconnect(); cleanup(it) }
        state.update { it.copy(status = Status.OFF) }
        log("Nacelle déconnectée")
    }

    /** Commande ordonnée (mode, requêtes…). */
    fun send(bytes: ByteArray) {
        if (gatt != null) ops.trySend(Op.Write(bytes))
    }

    /** Trame de suivi : seule la plus récente compte, les anciennes sont abandonnées. */
    fun sendTrack(bytes: ByteArray) {
        if (isReady) trackCh.trySend(bytes)
    }

    suspend fun readAttitude(timeoutMs: Long = 600): Hohem.Attitude? {
        if (!isReady) return null
        val w = CompletableDeferred<Hohem.Attitude>()
        attitudeWaiter = w
        send(Hohem.query(Hohem.CMD_ATTITUDE))
        return withTimeoutOrNull(timeoutMs) { w.await() }
    }

    // ---------- Recherche ----------

    private var nextIsDirect = true

    private fun knownAddress(): String? = prefs.getString("lastAddress", null)

    private fun looksLikeGimbal(name: String?, address: String): Boolean {
        if (knownAddress()?.equals(address, true) == true) return true
        if (name == null) return false
        return name.startsWith("SV3", true) || name.contains("iSteady", true) || name.contains("hohem", true)
    }

    private fun remoteDevice(address: String): BluetoothDevice? = runCatching {
        val ad = adapter ?: return null
        // Les adresses dont le 1er octet commence par 11 en binaire (C0 à FF) sont « aléatoires statiques ».
        val random = (address.substring(0, 2).toInt(16) and 0xC0) == 0xC0
        if (Build.VERSION.SDK_INT >= 33) {
            ad.getRemoteLeDevice(
                address.uppercase(),
                if (random) BluetoothDevice.ADDRESS_TYPE_RANDOM else BluetoothDevice.ADDRESS_TYPE_PUBLIC
            )
        } else ad.getRemoteDevice(address.uppercase())
    }.getOrNull()

    /** Alterne : appareil déjà connu du système → connexion directe à l'adresse connue → recherche. */
    private fun startAttempt() {
        if (!wanted || state.value.status != Status.OFF) return
        val system = runCatching {
            (manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() + adapter?.bondedDevices.orEmpty())
        }.getOrDefault(emptyList())
        val known = system.firstOrNull { looksLikeGimbal(runCatching { it.name }.getOrNull(), it.address) }
        if (known != null) {
            log("Nacelle trouvée dans les appareils du téléphone : ${known.name ?: known.address}")
            connectTo(known, known.name, timeoutMs = 10_000)
            return
        }
        if (nextIsDirect) {
            nextIsDirect = false
            val addr = knownAddress()
            val dev = addr?.let { remoteDevice(it) }
            if (dev != null) {
                log("Connexion directe à la nacelle ($addr)…")
                connectTo(dev, null, timeoutMs = 10_000)
                return
            }
        }
        nextIsDirect = true
        startScan()
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { log("Scanner Bluetooth indisponible"); return }
        state.update { it.copy(status = Status.SCANNING) }
        log("Recherche Bluetooth… (Hohem Joy doit être fermée)")
        var logged = 0
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val addr = result.device.address
                val hohemService = result.scanRecord?.serviceUuids?.any { u -> u.uuid.short()?.let { it in 0xFFE0..0xFFEF } == true } == true
                val likely = looksLikeGimbal(name, addr)
                val isNew = !seenDevices.containsKey(addr)
                seenDevices[addr] = result.device
                seen.update { list ->
                    (list.filterNot { it.address == addr } + Seen(addr, name, result.rssi, likely || hohemService))
                        .sortedWith(compareByDescending<Seen> { it.likely }.thenByDescending { it.rssi })
                }
                if (isNew && logged < 25) {
                    logged++
                    log("  vu : ${name ?: "(sans nom)"} $addr ${result.rssi} dBm" + if (hohemService) " [FFE0]" else "")
                }
                if (state.value.status != Status.SCANNING || !likely) return
                stopScan()
                connectTo(result.device, name)
            }

            override fun onScanFailed(errorCode: Int) {
                log("Échec de la recherche Bluetooth (code $errorCode)")
                state.update { it.copy(status = Status.OFF) }
                retryLater()
            }
        }
        scanCb = cb
        runCatching {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }.onFailure { log("Recherche impossible : ${it.message}") }
        scanTimeout = scope.launch {
            delay(20_000)
            if (state.value.status == Status.SCANNING) {
                stopScan()
                state.update { it.copy(status = Status.OFF) }
                if (seenDevices.isEmpty()) {
                    log("Aucun appareil Bluetooth vu : active la Localisation du téléphone et réessaie")
                } else {
                    log("Nacelle pas reconnue. Touche « Choisir » pour la sélectionner dans la liste")
                }
                retryLater()
            }
        }
    }

    private fun stopScan() {
        scanTimeout?.cancel()
        scanCb?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
        scanCb = null
    }

    private fun retryLater() {
        scope.launch {
            delay(3_000)
            startAttempt()
        }
    }

    private var connectSeq = 0

    private fun connectTo(device: BluetoothDevice, name: String?, timeoutMs: Long = 15_000) {
        state.update { it.copy(status = Status.CONNECTING, name = name ?: device.address) }
        log("Connexion à ${name ?: device.address}…")
        servicesRequested = false
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        val seq = ++connectSeq
        scope.launch {
            delay(timeoutMs)
            if (seq == connectSeq && state.value.status == Status.CONNECTING) {
                log("Pas de réponse de ${name ?: device.address}")
                gatt?.let { it.disconnect(); cleanup(it) }
                retryLater()
            }
        }
    }

    // ---------- GATT ----------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connectée, lecture des services…")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(247)) discover(g)
                scope.launch { delay(2_500); discover(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Liaison perdue (code $status)")
                cleanup(g)
                if (wanted) retryLater()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU $mtu")
            discover(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            setup(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) c.value?.let { onData(it) }
        }
    }

    @Synchronized
    private fun discover(g: BluetoothGatt) {
        if (servicesRequested || g != gatt) return
        servicesRequested = true
        g.discoverServices()
    }

    private fun UUID.short(): Int? =
        if (leastSignificantBits == BASE_LSB && (mostSignificantBits and 0xFFFFFFFFL) == 0x1000L)
            ((mostSignificantBits ushr 32) and 0xFFFF).toInt() else null

    private fun setup(g: BluetoothGatt) {
        val chars = g.services.flatMap { it.characteristics }
        chars.forEach { c ->
            log("  car. %s props=%02X".format(c.uuid.short()?.let { "%04X".format(it) } ?: c.uuid.toString(), c.properties))
        }
        val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        fun isHohem(c: BluetoothGattCharacteristic) = c.uuid.short()?.let { it in 0xFFE0..0xFFEF } == true ||
            c.service.uuid.short()?.let { it in 0xFFE0..0xFFEF } == true
        val notifies = chars.filter { it.properties and notifyMask != 0 && isHohem(it) }
            .ifEmpty { chars.filter { it.properties and notifyMask != 0 && it.service.uuid.short() == null } }
        val writes = chars.filter { it.properties and writeMask != 0 }
        val w = writes.firstOrNull { it.uuid.short() == 0xFFE1 }
            ?: writes.firstOrNull { it.uuid.short() == 0xFFE3 }
            ?: writes.firstOrNull { isHohem(it) }
            ?: writes.firstOrNull { it.service.uuid.short() == null }
        if (w == null || notifies.isEmpty()) {
            log("Service de la nacelle introuvable (écriture=${w != null}, notifications=${notifies.size})")
            g.disconnect()
            return
        }
        writeChar = w
        canWriteNoResponse = w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        canWriteWithResponse = w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        log("Écriture sur ${w.uuid.short()?.let { "%04X".format(it) } ?: w.uuid}, notifications : " +
            notifies.joinToString { c -> c.uuid.short()?.let { "%04X".format(it) } ?: c.uuid.toString() })

        startOpLoop(g)
        notifies.forEach { ops.trySend(Op.Notify(it)) }
        Hohem.initSequence.forEach {
            ops.trySend(Op.Write(it))
            ops.trySend(Op.Pause(35))
        }
        ops.trySend(Op.Ready)
    }

    private fun startOpLoop(g: BluetoothGatt) {
        opJob?.cancel()
        ops = Channel(Channel.UNLIMITED)
        trackCh = Channel(Channel.CONFLATED)
        val opsLocal = ops
        val trackLocal = trackCh
        opJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val op = opsLocal.tryReceive().getOrNull() ?: select {
                    opsLocal.onReceive { it }
                    trackLocal.onReceive { Op.Write(it, fast = true) }
                }
                execute(g, op)
            }
        }
    }

    private suspend fun execute(g: BluetoothGatt, op: Op) {
        when (op) {
            is Op.Pause -> delay(op.ms)
            is Op.Ready -> {
                state.update { it.copy(status = Status.READY, lastSeen = SystemClock.elapsedRealtime()) }
                prefs.edit().putString("lastAddress", g.device.address).apply()
                log("Nacelle prête ✓")
                startPolling()
            }
            is Op.Notify -> {
                g.setCharacteristicNotification(op.c, true)
                val d = op.c.getDescriptor(CCCD) ?: return
                val v = if (op.c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                awaitGatt {
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
                    else @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
                }
            }
            is Op.Write -> {
                val c = writeChar ?: return
                val type = when {
                    op.fast && canWriteNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    canWriteWithResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    else -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                awaitGatt {
                    if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, op.bytes, type) == BluetoothStatusCodes.SUCCESS
                    else @Suppress("DEPRECATION") run { c.writeType = type; c.value = op.bytes; g.writeCharacteristic(c) }
                }
            }
        }
    }

    private suspend fun awaitGatt(start: () -> Boolean): Boolean {
        val d = CompletableDeferred<Int>()
        pending = d
        val started = runCatching(start).getOrDefault(false)
        if (!started) {
            pending = null
            delay(20)
            return false
        }
        val r = withTimeoutOrNull(400) { d.await() }
        pending = null
        return r == BluetoothGatt.GATT_SUCCESS
    }

    private var pollJob: Job? = null

    /** Lit les angles 4 fois par seconde pour animer le cadran. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && isReady) {
                send(Hohem.query(Hohem.CMD_ATTITUDE))
                delay(250)
            }
        }
    }

    private var lastRecenterEvent = 0L

    private fun onData(value: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        for (f in parser.feed(value)) {
            when (f.cmd) {
                Hohem.CMD_INFO -> Hohem.decodeMode(f.data)?.let { m -> state.update { it.copy(mode = m, lastSeen = now) } }
                Hohem.CMD_ATTITUDE -> Hohem.decodeAttitude(f.data)?.let { a ->
                    state.update { it.copy(attitude = a, lastSeen = now) }
                    attitudeWaiter?.complete(a)
                }
                Hohem.CMD_RECENTER_EVENT -> lastRecenterEvent = now
                Hohem.CMD_RECENTERED -> if (now - lastRecenterEvent < 4_000) scope.launch {
                    delay(500)
                    readAttitude()?.let { a ->
                        prefs.edit().putFloat("homePan", a.encPan).putFloat("homeTilt", a.encTilt).apply()
                        log("Position de repos mémorisée (pan %.1f°, tilt %.1f°)".format(a.encPan, a.encTilt))
                    }
                }
                else -> state.update { it.copy(lastSeen = now) }
            }
            frames.tryEmit(f)
        }
    }

    private fun cleanup(g: BluetoothGatt) {
        pollJob?.cancel()
        opJob?.cancel()
        opJob = null
        pending?.cancel()
        runCatching { g.close() }
        if (gatt == g || gatt == null) {
            gatt = null
            writeChar = null
            state.update { it.copy(status = Status.OFF) }
        }
    }

    companion object {
        private const val BASE_LSB = -0x7fffff7fa064cb05L // 8000-00805F9B34FB
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
