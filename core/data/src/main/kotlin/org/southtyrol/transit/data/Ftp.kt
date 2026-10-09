package org.southtyrol.transit.data

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Minimal anonymous passive-mode FTP download (RFC 959), used only for STA's official GTFS
 * publication point as a fallback. Android's URLConnection has no FTP support.
 */
class FtpDownload(private val url: String, private val timeoutMillis: Int = 20_000) {
    private lateinit var control: Socket
    private lateinit var reader: BufferedReader
    private lateinit var writer: OutputStreamWriter

    var size: Long = -1
        private set
    var lastModified: String = ""
        private set

    private fun reply(): Pair<Int, String> {
        var line = reader.readLine() ?: throw IOException("FTP connection closed")
        val code = line.take(3).toIntOrNull() ?: throw TransferFailedException("Bad FTP reply: $line")
        // Multi-line replies: "123-..." until "123 ..."
        if (line.length > 3 && line[3] == '-') {
            while (true) {
                line = reader.readLine() ?: throw IOException("FTP connection closed")
                if (line.startsWith("$code ")) break
            }
        }
        return code to line
    }

    private fun command(cmd: String, vararg expected: Int): String {
        writer.write("$cmd\r\n"); writer.flush()
        val (code, line) = reply()
        if (expected.isNotEmpty() && code !in expected) throw TransferFailedException("FTP ${cmd.substringBefore(' ')} failed: $line")
        return line
    }

    /** Opens the file for reading from [offset]; the caller must close the stream and then call [close]. */
    fun open(offset: Long = 0): InputStream {
        val uri = URI(url)
        require(uri.scheme == "ftp") { "Not an FTP URL" }
        control = Socket().apply { connect(InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 21), timeoutMillis); soTimeout = timeoutMillis }
        reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.ISO_8859_1))
        writer = OutputStreamWriter(control.getOutputStream(), Charsets.ISO_8859_1)
        val (welcome, line) = reply()
        if (welcome != 220) throw TransferFailedException("FTP greeting: $line")
        val user = command("USER anonymous", 230, 331)
        if (user.startsWith("331")) command("PASS guest", 230)
        command("TYPE I", 200)
        size = runCatching { command("SIZE ${uri.path}", 213).substring(4).trim().toLong() }.getOrDefault(-1)
        lastModified = runCatching { command("MDTM ${uri.path}", 213).substring(4).trim() }.getOrDefault("")
        val pasv = command("PASV", 227)
        val numbers = Regex("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").find(pasv)?.groupValues?.drop(1)?.map { it.toInt() }
            ?: throw TransferFailedException("Bad PASV reply")
        // Connect to the control host rather than the advertised address (NAT-safe, avoids redirection).
        val data = Socket().apply { connect(InetSocketAddress(control.inetAddress, numbers[4] * 256 + numbers[5]), timeoutMillis); soTimeout = 60_000 }
        if (offset > 0) command("REST $offset", 350)
        command("RETR ${uri.path}", 125, 150)
        return object : InputStream() {
            private val input = data.getInputStream()
            override fun read(): Int = input.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
            override fun close() {
                data.close()
                runCatching { reply() }
            }
        }
    }

    fun close() {
        runCatching { command("QUIT") }
        runCatching { control.close() }
    }
}
