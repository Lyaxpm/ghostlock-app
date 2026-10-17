package com.ghostlock.app.brevent

import android.os.Parcel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Decoded form of one server reply. The vendor protocol has no exit code or
 * stdin channel; a command is only known to have been accepted once a real
 * [Command] frame arrives. [Ok]/[BaseOk] are the vendor's "no permission"
 * sentinels and [Empty]/[Unknown] mean the peer did not answer a command.
 */
sealed interface BreventResponse {
    data class Command(
        val request: String,
        val output: String,
        val done: Boolean,
        val remain: Int,
        val token: String,
    ) : BreventResponse

    /** Frame length 1; `BreventOK` — vendor "refused"/"no permission" reply. */
    data object Ok : BreventResponse

    /** `BaseBreventProtocol.BASE_STATUS_OK`; also a refusal. */
    data object BaseOk : BreventResponse

    /** Frame length 0; the peer closed without answering. */
    data object Empty : BreventResponse

    data class Unknown(val action: Int, val token: String) : BreventResponse
}

/** Outcome of the zero-length "is the Brevent server listening?" probe. */
data class ServerProbe(
    val reachable: Boolean,
    val ok: Boolean,
    val error: String?,
)

/**
 * Wire codec for the vendor `me.piebridge.brevent.protocol` messages.
 *
 * Framing is a 2-byte big-endian length prefix followed by a gzip-compressed
 * `Parcel`. The base layout is `int version, int action, String token`; command
 * messages append their own fields. This mirrors
 * `BaseBreventProtocol.writeTo`/`readFromBase` and `BreventProtocol.readFrom`.
 */
object BreventWire {
    /** `BaseBreventProtocol.PORT` (md5 of the package name, truncated). */
    const val PORT = 59526
    const val ACTION_CMD_REQUEST = 12
    const val ACTION_CMD_RESPONSE = 13
    const val BASE_STATUS_OK = 99

    private const val MAX_FRAME = 0xffff

    fun writeCommandRequest(
        out: DataOutputStream,
        version: Int,
        token: String,
        command: String,
        force: Boolean,
    ) {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(version)
            parcel.writeInt(ACTION_CMD_REQUEST)
            parcel.writeString(token)
            parcel.writeString(command)
            parcel.writeInt(if (force) 1 else 0)
            writeFrame(out, parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    /** Writes the 2-byte `0` probe frame used by `checkPortSync()`. */
    fun writeProbe(out: DataOutputStream) {
        out.writeShort(0)
    }

    fun writeFrame(out: DataOutputStream, payload: ByteArray) {
        val compressed = gzip(payload)
        if (compressed.size > MAX_FRAME) {
            throw IOException("frame too large: ${compressed.size}")
        }
        out.writeShort(compressed.size)
        out.write(compressed)
    }

    fun readResponse(input: DataInputStream): BreventResponse {
        val size = input.readUnsignedShort()
        if (size == 0) return BreventResponse.Empty
        if (size == 1) return BreventResponse.Ok
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read < 0) throw EOFException("truncated frame: $offset/$size")
            offset += read
        }
        return decodePayload(gunzip(bytes))
    }

    /** Decodes a de-framed, gzip-decompressed parcel body. */
    fun decodePayload(raw: ByteArray): BreventResponse {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(raw, 0, raw.size)
            parcel.setDataPosition(0)
            parcel.readInt() // version (unused by callers)
            val action = parcel.readInt()
            val token = parcel.readString().orEmpty()
            return when (action) {
                ACTION_CMD_RESPONSE -> BreventResponse.Command(
                    request = parcel.readString().orEmpty(),
                    output = parcel.readString().orEmpty(),
                    done = parcel.readInt() != 0,
                    remain = parcel.readInt(),
                    token = token,
                )

                BASE_STATUS_OK -> BreventResponse.BaseOk
                else -> BreventResponse.Unknown(action, token)
            }
        } finally {
            parcel.recycle()
        }
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(bytes) }
        return buffer.toByteArray()
    }

    fun gunzip(compressed: ByteArray): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        GZIPInputStream(java.io.ByteArrayInputStream(compressed)).use { input ->
            val chunk = ByteArray(0x1000)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read > 0) buffer.write(chunk, 0, read)
            }
        }
        return buffer.toByteArray()
    }
}

/**
 * Minimal client for the Brevent loopback server. It never sends a token it was
 * not given, so a third-party app is honestly limited to what the vendor
 * protocol allows. [versionCode] must be the installed Brevent's version code,
 * because the server rejects a mismatched protocol version.
 */
class BreventProtocolClient(
    private val versionCode: Int,
    private val host: InetAddress = InetAddress.getLoopbackAddress(),
    private val port: Int = BreventWire.PORT,
    private val connector: (InetAddress, Int, Int) -> Socket = { address, p, timeout ->
        Socket(address, p).apply { soTimeout = timeout }
    },
) {
    fun probe(timeoutMs: Int = 1500): ServerProbe = try {
        connector(host, port, timeoutMs).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            BreventWire.writeProbe(out)
            out.flush()
            when (val response = BreventWire.readResponse(input)) {
                BreventResponse.Ok -> ServerProbe(reachable = true, ok = true, error = null)
                else -> ServerProbe(
                    reachable = true,
                    ok = false,
                    error = "unexpected handshake: $response",
                )
            }
        }
    } catch (error: ConnectException) {
        ServerProbe(reachable = false, ok = false, error = null)
    } catch (error: SocketTimeoutException) {
        ServerProbe(reachable = true, ok = false, error = "handshake timeout")
    } catch (error: IOException) {
        ServerProbe(reachable = false, ok = false, error = error.message)
    }

    fun command(
        command: String,
        token: String,
        force: Boolean,
        timeoutMs: Int = 5000,
    ): BreventResponse = connector(host, port, timeoutMs).use { socket ->
        val out = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())
        BreventWire.writeCommandRequest(out, versionCode, token, command, force)
        out.flush()
        BreventWire.readResponse(input)
    }
}
