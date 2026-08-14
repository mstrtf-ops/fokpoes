package com.egrwatch.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

val egrSet = setOf(
    "P0401", "P0402", "P0403", "P0404", "P0405",
    "P0406", "P0409", "P040D", "P040E", "P1406"
)

data class WatchState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val codes: List<String> = emptyList(),
    val statusLine: String = "Tap Connect to start",
    val lastAction: String = "",
    val autoClear: Boolean = false,
    val autoReconnect: Boolean = true,
    val pendingClear: List<String>? = null
)

class MainActivity : ComponentActivity() {

    private var state by mutableStateOf(WatchState())
    private var mgr: ObdManager? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) init()
            else state = state.copy(statusLine = "Bluetooth permission needed")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WatchScreen(
                    state,
                    onToggleConn = { mgr?.toggleConnection() },
                    onRead = { mgr?.readCodes() },
                    onClear = { mgr?.requestClearAll() },
                    onConfirmClear = { mgr?.confirmClearAll() },
                    onCancelClear = { mgr?.cancelClear() },
                    onAutoClear = { mgr?.setAutoClear(it) },
                    onAutoReconnect = { mgr?.setAutoReconnect(it) }
                )
            }
        }
        if (hasPerm()) init() else permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun init() {
        mgr = ObdManager(applicationContext) { s -> runOnUiThread { state = s } }
    }

    private fun hasPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        else true

    override fun onDestroy() {
        super.onDestroy()
        mgr?.shutdown()
    }
}

class ObdManager(
    private val context: Context,
    private val onState: (WatchState) -> Unit
) {
    private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val stLock = Any()

    @Volatile private var socket: BluetoothSocket? = null
    private var autoJob: Job? = null
    private var st = WatchState()

    private fun update(f: (WatchState) -> WatchState) {
        val ns = synchronized(stLock) { st = f(st); st }
        onState(ns)
    }

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    // ---- public actions ----

    fun toggleConnection() = scope.launch {
        if (st.connected || st.connecting) disconnectNow()
        else mutex.withLock { openLocked() }
    }

    fun readCodes() = scope.launch {
        mutex.withLock {
            if (!ensureOpen()) return@withLock
            val codes = readDtcsLocked()
            if (codes == null) update { it.copy(statusLine = "No response on read") }
            else update { it.copy(codes = codes, statusLine = "Read ${now()}") }
        }
    }

    fun requestClearAll() = scope.launch {
        mutex.withLock {
            if (!ensureOpen()) return@withLock
            val codes = readDtcsLocked() ?: emptyList()
            if (codes.isEmpty()) update { it.copy(codes = codes, statusLine = "No codes to clear") }
            else update { it.copy(codes = codes, pendingClear = codes, statusLine = "Confirm clear") }
        }
    }

    fun cancelClear() = update { it.copy(pendingClear = null, statusLine = "Clear cancelled") }

    fun confirmClearAll() = scope.launch {
        mutex.withLock {
            val ok = clearAllLocked()
            val codes = readDtcsLocked() ?: emptyList()
            update {
                it.copy(
                    pendingClear = null,
                    codes = codes,
                    lastAction = if (ok) "Cleared all ${now()}" else "Clear rejected ${now()}",
                    statusLine = if (codes.isEmpty()) "Cleared" else "Codes still present"
                )
            }
        }
    }

    fun setAutoClear(on: Boolean) {
        update { it.copy(autoClear = on) }
        if (on) startAutoLoop() else stopAutoLoop()
    }

    fun setAutoReconnect(on: Boolean) = update { it.copy(autoReconnect = on) }

    fun shutdown() {
        stopAutoLoop()
        try { socket?.close() } catch (_: Exception) {}
        scope.cancel()
    }

    // ---- connection ----

    @SuppressLint("MissingPermission")
    private suspend fun openLocked(): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null || !adapter.isEnabled) {
            update { it.copy(connecting = false, statusLine = "Turn on Bluetooth") }; return false
        }
        val dev = adapter.bondedDevices.firstOrNull { d ->
            d.name?.let { n ->
                listOf("OBD", "ELM", "V-LINK", "VEEPEAK", "VIECAR", "VGATE").any { n.contains(it, true) }
            } == true
        }
        if (dev == null) {
            update { it.copy(connecting = false, statusLine = "Pair the OBD adapter first") }; return false
        }
        update { it.copy(connecting = true, statusLine = "Connecting to ${dev.name}") }
        return try {
            var s = dev.createRfcommSocketToServiceRecord(SPP)
            try { s.connect() } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                s = dev.createInsecureRfcommSocketToServiceRecord(SPP)
                s.connect()
            }
            socket = s
            initElm()
            update { it.copy(connected = true, connecting = false, statusLine = "Connected") }
            true
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            update { it.copy(connected = false, connecting = false, statusLine = "Connect failed") }
            false
        }
    }

    private suspend fun ensureOpen(): Boolean {
        if (socket?.isConnected == true) return true
        return openLocked()
    }

    private suspend fun reopenLocked() {
        update { it.copy(statusLine = "Reconnecting for clear") }
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        delay(700)
        openLocked()
    }

    private suspend fun disconnectNow() {
        stopAutoLoop()
        mutex.withLock {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }
        update { it.copy(connected = false, connecting = false, autoClear = false, statusLine = "Disconnected") }
    }

    // ---- obd ops (call with mutex held) ----

    private suspend fun clearAllLocked(): Boolean {
        if (st.autoReconnect) reopenLocked()
        if (socket?.isConnected != true && !openLocked()) return false
        return cmd("04").replace(" ", "").uppercase().contains("44")
    }

    private fun initElm() {
        cmd("ATZ"); Thread.sleep(1000)
        cmd("ATE0"); cmd("ATL0"); cmd("ATH0"); cmd("ATSP0"); cmd("0100")
    }

    private fun readDtcsLocked(): List<String>? {
        val resp = cmd("03")
        val flat = resp.uppercase().replace(" ", "").replace("\r", "").replace("\n", "")
        if (flat.contains("NODATA")) return emptyList()
        val toks = tokenize(resp)
        val i = toks.indexOf("43")
        if (i < 0) return null
        var data = toks.drop(i + 1)
        if (data.isEmpty()) return emptyList()
        if (data.size % 2 == 1) data = data.drop(1)
        val out = mutableListOf<String>()
        var k = 0
        while (k + 1 < data.size) {
            val hi = data[k]; val lo = data[k + 1]
            if (!(hi == "00" && lo == "00")) decode(hi, lo)?.let { out.add(it) }
            k += 2
        }
        return out.distinct()
    }

    private fun tokenize(s: String): List<String> =
        s.uppercase().replace(">", " ").replace(":", " ").replace("\r", " ").replace("\n", " ")
            .split(Regex("\\s+"))
            .filter { it.length == 2 && it.all { c -> c in "0123456789ABCDEF" } }

    private fun decode(hiHex: String, loHex: String): String? {
        val hi = hiHex.toIntOrNull(16) ?: return null
        loHex.toIntOrNull(16) ?: return null
        val letter = when (hi ushr 6) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
        val d1 = (hi ushr 4) and 0x03
        val d2 = (hi and 0x0F).toString(16).uppercase()
        return "$letter$d1$d2${loHex.uppercase()}"
    }

    private fun cmd(c: String): String {
        val s = socket ?: return ""
        return try {
            val out = s.outputStream; val inp = s.inputStream
            out.write((c + "\r").toByteArray()); out.flush()
            val sb = StringBuilder(); val buf = ByteArray(512)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 4000) {
                if (inp.available() > 0) {
                    val n = inp.read(buf)
                    if (n > 0) { sb.append(String(buf, 0, n)); if (sb.contains('>')) break }
                } else Thread.sleep(20)
            }
            sb.toString()
        } catch (e: Exception) { "" }
    }

    // ---- auto-clear loop ----

    private fun startAutoLoop() {
        if (autoJob?.isActive == true) return
        autoJob = scope.launch {
            while (st.autoClear) {
                mutex.withLock {
                    if (ensureOpen()) {
                        val codes = readDtcsLocked()
                        if (codes != null) {
                            val nonEgr = codes.any { it !in egrSet }
                            update { it.copy(codes = codes) }
                            if (codes.isNotEmpty() && !nonEgr) {
                                val ok = clearAllLocked()
                                update {
                                    it.copy(lastAction = if (ok) "Auto-cleared EGR ${now()}" else "Auto-clear rejected ${now()}")
                                }
                            }
                        }
                    }
                }
                delay(5000)
            }
        }
    }

    private fun stopAutoLoop() { autoJob?.cancel(); autoJob = null }
}

@Composable
fun WatchScreen(
    st: WatchState,
    onToggleConn: () -> Unit,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onConfirmClear: () -> Unit,
    onCancelClear: () -> Unit,
    onAutoClear: (Boolean) -> Unit,
    onAutoReconnect: (Boolean) -> Unit
) {
    val green = Color(0xFF22C55E); val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444); val grey = Color(0xFF475569); val blue = Color(0xFF3B82F6)
    val bg = Color(0xFF0A0E14)

    val nonEgr = st.codes.any { it !in egrSet }
    val (word, sub, color) = when {
        st.connecting -> Triple("CONNECTING", "waiting for adapter", grey)
        !st.connected -> Triple("DISCONNECTED", "tap connect", grey)
        st.codes.isEmpty() -> Triple("ALL CLEAR", "no fault codes", green)
        nonEgr -> Triple("CHECK ENGINE", st.codes.joinToString("   "), red)
        else -> Triple("EGR PRESENT", st.codes.joinToString("   "), amber)
    }

    Surface(color = bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(if (st.connected) green else grey))
                Spacer(Modifier.width(8.dp))
                Text(if (st.connected) "LINKED" else "NO LINK",
                    color = Color(0xFF94A3B8), fontSize = 12.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Text("EGR WATCH", color = Color(0xFF64748B), fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }

            Spacer(Modifier.height(18.dp))

            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(color.copy(alpha = 0.14f)).padding(vertical = 30.dp, horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(word, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center, lineHeight = 36.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(sub, color = Color(0xFFCBD5E1), fontSize = 16.sp, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("TOOLS", color = Color(0xFF64748B), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onToggleConn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (st.connected) grey else blue),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (st.connected || st.connecting) "DISCONNECT" else "CONNECT",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onRead, enabled = st.connected,
                    modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)
                ) { Text("READ CODES", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onClear, enabled = st.connected,
                    modifier = Modifier.weight(1f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = red),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("CLEAR ALL", fontWeight = FontWeight.Bold) }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Color(0xFF1E293B))
            Spacer(Modifier.height(6.dp))

            ToggleRow("Auto-clear EGR", "clears only when EGR is the sole code",
                st.autoClear, onAutoClear)
            ToggleRow("Auto reconnect on clear", "fresh connection before each clear",
                st.autoReconnect, onAutoReconnect)

            Spacer(Modifier.height(18.dp))

            if (st.lastAction.isNotBlank())
                Text(st.lastAction, color = Color(0xFF94A3B8), fontSize = 14.sp)
            Text("status: ${st.statusLine}", color = Color(0xFF475569), fontSize = 12.sp)
        }
    }

    if (st.pendingClear != null) {
        AlertDialog(
            onDismissRequest = onCancelClear,
            title = { Text("Clear all codes?") },
            text = {
                Text(
                    "This wipes every stored code plus readiness data, not just EGR.\n\nAbout to clear:\n" +
                        (if (st.pendingClear.isEmpty()) "(none)" else st.pendingClear.joinToString("\n"))
                )
            },
            confirmButton = { TextButton(onClick = onConfirmClear) { Text("CLEAR ALL") } },
            dismissButton = { TextButton(onClick = onCancelClear) { Text("CANCEL") } }
        )
    }
}

@Composable
fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color(0xFFE2E8F0), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = Color(0xFF64748B), fontSize = 12.sp)
        }
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}
