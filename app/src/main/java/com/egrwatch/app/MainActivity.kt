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
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class WatchState(
    val connected: Boolean = false,
    val codes: List<String> = emptyList(),
    val nonEgr: Boolean = false,
    val lastAction: String = "",
    val statusLine: String = "Starting"
)

class MainActivity : ComponentActivity() {

    private var state by mutableStateOf(WatchState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: ObdWatcher? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startWatcher()
            else state = state.copy(statusLine = "Bluetooth permission needed")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { WatchScreen(state) }
        }
        if (hasBtPerm()) startWatcher() else permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun startWatcher() {
        watcher = ObdWatcher(applicationContext) { s -> runOnUiThread { state = s } }
        watcher?.start(scope)
    }

    private fun hasBtPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        else true

    override fun onDestroy() {
        super.onDestroy()
        watcher?.stop()
        scope.cancel()
    }
}

class ObdWatcher(
    private val context: Context,
    private val onState: (WatchState) -> Unit
) {
    private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val POLL_MS = 4000L
    private val MIN_CLEAR_MS = 15000L
    private var lastClear = 0L
    private var cur = WatchState()
    private var job: Job? = null
    @Volatile private var running = false
    private var socket: BluetoothSocket? = null

    // Common EGR-family codes. Confirm the real one on first connect and add if missing.
    private val EGR = setOf(
        "P0401", "P0402", "P0403", "P0404", "P0405", "P0406",
        "P0409", "P040D", "P040E", "P1406"
    )

    fun start(scope: CoroutineScope) {
        running = true
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() { running = false; job?.cancel(); close() }

    private fun emit(s: WatchState) { cur = s; onState(s) }
    private fun close() { try { socket?.close() } catch (_: Exception) {} ; socket = null }
    private fun now() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    @SuppressLint("MissingPermission")
    private suspend fun loop() {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        while (running) {
            if (adapter == null || !adapter.isEnabled) {
                emit(cur.copy(connected = false, statusLine = "Turn on Bluetooth")); delay(3000); continue
            }
            val dev = adapter.bondedDevices.firstOrNull { d ->
                d.name?.let { n ->
                    listOf("OBD", "ELM", "V-LINK", "VEEPEAK", "VIECAR", "VGATE").any { n.contains(it, true) }
                } == true
            }
            if (dev == null) {
                emit(cur.copy(connected = false, statusLine = "Pair the OBD adapter in Bluetooth settings"))
                delay(4000); continue
            }
            try {
                emit(cur.copy(connected = false, statusLine = "Connecting to ${dev.name}"))
                var s = dev.createRfcommSocketToServiceRecord(SPP)
                try { s.connect() } catch (e: Exception) {
                    try { s.close() } catch (_: Exception) {}
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP)
                    s.connect()
                }
                socket = s
                val out = s.outputStream; val inp = s.inputStream
                init(out, inp)
                emit(cur.copy(connected = true, statusLine = "Monitoring"))
                monitor(out, inp)
            } catch (e: Exception) {
                emit(cur.copy(connected = false, statusLine = "Reconnecting"))
            } finally { close() }
            delay(2500)
        }
    }

    private suspend fun monitor(out: OutputStream, inp: InputStream) {
        while (running) {
            val codes = readDtcs(out, inp)
            if (codes == null) {
                emit(cur.copy(connected = true, statusLine = "No response, retrying")); delay(POLL_MS); continue
            }
            val nonEgr = codes.any { it !in EGR }
            emit(cur.copy(connected = true, codes = codes, nonEgr = nonEgr, statusLine = "raw ok"))
            val t = System.currentTimeMillis()
            if (codes.isNotEmpty() && !nonEgr && t - lastClear > MIN_CLEAR_MS) {
                val ok = clear(out, inp)
                lastClear = t
                emit(cur.copy(lastAction =
                    if (ok) "Cleared ${codes.joinToString(",")} at ${now()}"
                    else "Clear rejected at ${now()} (engine running?)"))
            }
            delay(POLL_MS)
        }
    }

    private fun init(out: OutputStream, inp: InputStream) {
        cmd(out, inp, "ATZ"); Thread.sleep(1000)
        cmd(out, inp, "ATE0")
        cmd(out, inp, "ATL0")
        cmd(out, inp, "ATH0")
        cmd(out, inp, "ATSP0")
        cmd(out, inp, "0100")
    }

    private fun clear(out: OutputStream, inp: InputStream): Boolean =
        cmd(out, inp, "04").replace(" ", "").uppercase().contains("44")

    private fun readDtcs(out: OutputStream, inp: InputStream): List<String>? {
        val resp = cmd(out, inp, "03")
        val flat = resp.uppercase().replace(" ", "").replace("\r", "").replace("\n", "")
        if (flat.contains("NODATA")) return emptyList()
        val toks = tokenize(resp)
        val i = toks.indexOf("43")
        if (i < 0) return null
        var data = toks.drop(i + 1)
        if (data.isEmpty()) return emptyList()
        if (data.size % 2 == 1) data = data.drop(1)   // CAN count byte
        val out2 = mutableListOf<String>()
        var k = 0
        while (k + 1 < data.size) {
            val hi = data[k]; val lo = data[k + 1]
            if (!(hi == "00" && lo == "00")) decode(hi, lo)?.let { out2.add(it) }
            k += 2
        }
        return out2.distinct()
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

    private fun cmd(out: OutputStream, inp: InputStream, c: String): String {
        return try {
            out.write((c + "\r").toByteArray()); out.flush()
            val sb = StringBuilder()
            val buf = ByteArray(512)
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
}

@Composable
fun WatchScreen(st: WatchState) {
    val green = Color(0xFF22C55E); val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444); val grey = Color(0xFF475569)
    val bg = Color(0xFF0A0E14)

    val (word, sub, color) = when {
        !st.connected -> Triple(
            if (st.statusLine.isBlank()) "CONNECTING" else st.statusLine.uppercase(),
            "waiting for adapter", grey)
        st.codes.isEmpty() -> Triple("ALL CLEAR", "no fault codes", green)
        st.nonEgr -> Triple("CHECK ENGINE", st.codes.joinToString("   "), red)
        else -> Triple("EGR AUTO CLEAR", st.codes.joinToString("   "), amber)
    }

    val pulsing = st.connected && st.codes.isNotEmpty()
    val infinite = rememberInfiniteTransition(label = "p")
    val alpha by infinite.animateFloat(
        initialValue = if (pulsing) 0.5f else 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")

    Surface(color = bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape)
                    .background(if (st.connected) green else grey))
                Spacer(Modifier.width(8.dp))
                Text(if (st.connected) "ADAPTER LINKED" else "NO LINK",
                    color = Color(0xFF94A3B8), fontSize = 13.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.weight(1f))
                Text("EGR WATCH", color = Color(0xFF64748B), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }

            Spacer(Modifier.weight(1f))

            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(color.copy(alpha = 0.16f))
                    .padding(vertical = 48.dp, horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(word, color = color.copy(alpha = alpha),
                        fontSize = 40.sp, fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center, lineHeight = 44.sp)
                    Spacer(Modifier.height(14.dp))
                    Text(sub, color = Color(0xFFCBD5E1), fontSize = 20.sp,
                        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth()) {
                if (st.lastAction.isNotBlank())
                    Text(st.lastAction, color = Color(0xFF94A3B8), fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text("status: ${st.statusLine}", color = Color(0xFF475569), fontSize = 12.sp)
            }
        }
    }
}
