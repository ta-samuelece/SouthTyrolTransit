package org.southtyrol.transit.data

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hits the real STA FTP server; only runs with -PliveTests=true. */
class FtpLiveTest {
    @Test fun headOfRealFile() {
        assumeTrue(System.getProperty("live.tests") == "true")
        val ftp = FtpDownload(GtfsSources.StaFtp.url)
        try {
            val bytes = ftp.open().use { it.readNBytes(4) }
            println("FTP size=${ftp.size} modified=${ftp.lastModified} head=${bytes.joinToString { "%02x".format(it) }}")
            assertTrue(bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
            assertTrue(ftp.size > 10_000_000)
        } finally {
            ftp.close()
        }
        // Resume support: the last 22 bytes are the ZIP end-of-central-directory record.
        val tail = FtpDownload(GtfsSources.StaFtp.url)
        try {
            val probe = FtpDownload(GtfsSources.StaFtp.url)
            val size = try { probe.open().close(); probe.size } finally { probe.close() }
            val bytes = tail.open(size - 22).use { it.readAllBytes() }
            println("FTP tail=${bytes.size} ${bytes.take(4).joinToString { "%02x".format(it) }}")
            assertTrue(bytes.size == 22 && bytes[2] == 5.toByte() && bytes[3] == 6.toByte())
        } finally {
            tail.close()
        }
    }
}
