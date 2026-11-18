package app.vlcinterface.player

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The Bluetooth codec in use (read only: apps can't change the codec or the SBC bitrate, only Android's
 * Developer options can). Read through Android's codec status where the system allows it.
 */
object BtCodec {
    private fun codecName(type: Int) = when (type) { 0 -> "SBC"; 1 -> "AAC"; 2 -> "aptX"; 3 -> "aptX HD"; 4 -> "LDAC"; 5 -> "LC3"; 6 -> "Opus"; else -> "codec $type" }
    private fun rateName(r: Int) = when (r) { 0x1 -> "44.1 kHz"; 0x2 -> "48 kHz"; 0x4 -> "88.2 kHz"; 0x8 -> "96 kHz"; 0x10 -> "176.4 kHz"; 0x20 -> "192 kHz"; else -> "" }
    private fun bitsName(b: Int) = when (b) { 0x1 -> "16-bit"; 0x2 -> "24-bit"; 0x4 -> "32-bit"; else -> "" }
    private fun ldacName(q: Long) = when (q) { 1000L -> "LDAC 990 kbps"; 1001L -> "LDAC 660 kbps"; 1002L -> "LDAC 330 kbps"; 1003L -> "LDAC best effort"; else -> "" }

    /** e.g. "SBC · 44.1 kHz · 16-bit", or null when Android doesn't tell. */
    suspend fun info(ctx: Context): String? = try { infoInner(ctx) } catch (_: Throwable) { null }

    private suspend fun infoInner(ctx: Context): String? = withTimeoutOrNull(3000) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return@withTimeoutOrNull null
        val a2dp: BluetoothA2dp = suspendCancellableCoroutine { cont ->
            val ok = try {
                adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { if (cont.isActive) cont.resume(proxy as BluetoothA2dp) }
                    override fun onServiceDisconnected(p: Int) {}
                }, BluetoothProfile.A2DP)
            } catch (_: Exception) { false }
            if (!ok && cont.isActive) cont.cancel()
        }
        try {
            val dev = try { a2dp.connectedDevices.firstOrNull() } catch (_: Exception) { null } ?: return@withTimeoutOrNull null
            val status = a2dp.javaClass.getMethod("getCodecStatus", android.bluetooth.BluetoothDevice::class.java).invoke(a2dp, dev) ?: return@withTimeoutOrNull null
            val cfg = status.javaClass.getMethod("getCodecConfig").invoke(status) ?: return@withTimeoutOrNull null
            fun int(m: String) = try { cfg.javaClass.getMethod(m).invoke(cfg) as Int } catch (_: Exception) { -1 }
            val type = int("getCodecType")
            val q = try { cfg.javaClass.getMethod("getCodecSpecific1").invoke(cfg) as Long } catch (_: Exception) { 0L }
            listOf(if (type == 4) ldacName(q).ifEmpty { "LDAC" } else codecName(type), rateName(int("getSampleRate")), bitsName(int("getBitsPerSample")))
                .filter { it.isNotEmpty() }.joinToString(" · ")
        } catch (_: Throwable) { null }
        finally { try { adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp) } catch (_: Exception) {} }
    }
}
