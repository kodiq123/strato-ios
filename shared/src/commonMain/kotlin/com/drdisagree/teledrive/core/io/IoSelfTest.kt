package com.drdisagree.teledrive.core.io

import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.decryptStream
import com.drdisagree.teledrive.core.crypto.encryptStream
import com.drdisagree.teledrive.core.crypto.secureRandomBytes
import kotlin.io.encoding.Base64

/**
 * Exercises the java.io-shaped layer the engine runs on. On the JVM it checks java.io itself (the
 * reference); on iOS it checks the Foundation/POSIX/zlib/CommonCrypto implementations, from the
 * stage-3 check screen and the simulator tests. Returns failure descriptions; empty means all passed.
 */
object IoSelfTest {

    fun run(scratch: File): List<String> {
        val failures = mutableListOf<String>()
        fun check(name: String, block: () -> Boolean) {
            val ok = runCatching(block).getOrElse { e ->
                failures += "$name: ${e::class.simpleName} ${e.message}"
                return
            }
            if (!ok) failures += name
        }

        val root = File(scratch, "io-self-test")
        root.deleteRecursively()
        check("mkdirs") { root.mkdirs() && root.isDirectory() && !root.mkdirs() }

        val a = File(root, "a.bin")
        val payload = secureRandomBytes(300_000)
        check("write/read bytes") {
            a.writeBytes(payload)
            a.isFile() && a.length() == payload.size.toLong() && a.readBytes().contentEquals(payload)
        }
        check("append") {
            a.appendBytes(byteArrayOf(1, 2, 3))
            a.length() == payload.size + 3L && a.readBytes().copyOfRange(payload.size, payload.size + 3)
                .contentEquals(byteArrayOf(1, 2, 3))
        }
        check("setLength truncate/extend") {
            a.setLength(10)
            val cut = a.length() == 10L && a.readBytes().contentEquals(payload.copyOf(10))
            a.setLength(20)
            cut && a.length() == 20L && a.readBytes().copyOfRange(10, 20).all { it == 0.toByte() }
        }
        check("createNewFile") {
            val b = File(root, "b.txt")
            b.createNewFile() && !b.createNewFile() && b.length() == 0L
        }
        check("text utf-8") {
            val text = "olá — 日本 🚀\n"
            File(root, "b.txt").writeText(text)
            File(root, "b.txt").readText() == text
        }
        check("renameTo") {
            // The engine only renames onto free names (replacing differs on Windows).
            val src = File(root, "src.txt").apply { writeText("new") }
            val dst = File(root, "dst.txt")
            src.renameTo(dst) && !src.exists() && dst.readText() == "new"
        }
        check("lastModified") {
            val stamp = 1_700_000_000_000L
            a.setLastModified(stamp) && kotlin.math.abs(a.lastModified() - stamp) < 1000
        }
        check("paths") {
            val nested = File(File(root, "x"), "y.txt")
            nested.getName() == "y.txt" && nested.getParentFile()?.getName() == "x" &&
                nested.getAbsolutePath().endsWith("/io-self-test/x/y.txt".replace('/', separator()))
        }
        check("listFiles / walk") {
            File(root, "d1/d2").mkdirs()
            File(root, "d1/d2/f.txt").writeText("f")
            val names = root.listFiles()?.map { it.getName() }?.toSet().orEmpty()
            val walked = root.walkTopDown().count { it.isFile() }
            names.containsAll(setOf("a.bin", "b.txt", "dst.txt", "d1")) && walked == 4
        }
        check("delete keeps non-empty directory") { !File(root, "d1").delete() && File(root, "d1").exists() }
        check("copyTo") {
            val copy = a.copyTo(File(root, "copy/a.bin"))
            copy.readBytes().contentEquals(a.readBytes())
        }
        check("buffered streams") {
            val f = File(root, "buf.bin")
            f.outputStream().buffered(4096).use { out ->
                out.write(payload, 0, 10)
                payload.copyOfRange(10, payload.size).forEach { out.write(it.toInt()) }
            }
            val back = ByteArrayOutputStream()
            f.inputStream().buffered(1000).use { input ->
                input.skip(5)
                input.copyTo(back, 777)
            }
            back.toByteArray().contentEquals(payload.copyOfRange(5, payload.size))
        }
        check("gzip round trip") {
            val f = File(root, "x.gz")
            gzipOutputStream(f.outputStream()).use { it.write(payload) }
            gzipInputStream(f.inputStream()).use { it.readBytes() }.contentEquals(payload)
        }
        check("gzip reads JVM output") {
            val jvm = Base64.decode(JVM_GZIP_FIXTURE)
            gzipInputStream(jvm.inputStream()).use { it.readBytes() }.decodeToString() ==
                "TeleDrive index snapshot\n".repeat(40)
        }
        check("sha256") {
            Sha256().apply { update("abc".encodeToByteArray()) }.digest().toHex() ==
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        }
        check("sha256 streamed") {
            val digest = Sha256()
            DigestingInputStream(payload.inputStream(), digest).use { it.copyTo(ByteArrayOutputStream()) }
            val whole = Sha256().apply { update(payload) }.digest()
            digest.digest().contentEquals(whole)
        }
        check("StreamCrypto over streams") {
            val key = secureRandomBytes(32)
            val crypto = StreamCrypto()
            val sealed = ByteArrayOutputStream()
            crypto.encryptStream(key, payload.inputStream(), sealed)
            val opened = ByteArrayOutputStream()
            crypto.decryptStream(key, sealed.toByteArray().inputStream(), opened)
            opened.toByteArray().contentEquals(payload)
        }
        check("deleteRecursively") { root.deleteRecursively() && !root.exists() }
        return failures
    }

    /** java.util.zip.GZIPOutputStream output for "TeleDrive index snapshot\n" × 40. */
    private const val JVM_GZIP_FIXTURE =
        "H4sIAAAAAAAA/wtJzUl1KcosS1XIzEtJrVAozkssKM7IL+EKGZUYlRiVGC4SACRFFZroAwAA"

    private fun separator(): Char = if (File("a/b").getPath().contains('\\')) '\\' else '/'
}
