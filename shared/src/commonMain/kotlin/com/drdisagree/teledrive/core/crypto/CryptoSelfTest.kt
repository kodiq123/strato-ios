package com.drdisagree.teledrive.core.crypto

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Decrypts fixtures produced by the original Android/JVM implementation (javax.crypto), so any
 * platform can prove it reads drives written elsewhere. Run by the JVM tests and, on iOS, by an
 * XCTest with the real CryptoKit provider registered.
 */
object CryptoSelfTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val plain = ByteArray(300) { (it * 31 + 5).toByte() }
    private const val PASSPHRASE = "pässwörd-ção 🔑"
    private val contentKey = ByteArray(32) { (255 - it).toByte() }

    private const val STREAM =
        "5444453101233a8f8ddb0456288000013c9a1c17d2443eab00e669e2d716c9fb609d504ce03df2fb74f7" +
                "edd820dae7e2bb7df2e1dc9b7c4bc0bee0aa790b74194eb11e1d3d84e7d9dc7be263f762ca7e" +
                "5803a0ffc61557d8d6c8208bbb40c86489da5e8fefb8f8e0aa97e812dcd401e3995084b41a5d" +
                "d072cc2307511eff5ff6a108197ff2c13e7820d44073872d4cef86396032dd5fd4a258fdb472" +
                "f6092abacd8b31dc3919878b66fdf4a4ae13e6d37f68864e6ad9e0c2861fc144825dad968bfa" +
                "1cd4ad18791e39b51bd59f7d43b59fda21c993c601bb49b7474e8f9d2a730c8c0ab6083f35d1" +
                "ff947fcd7e333c72ad9021a7a67644379892117acf5923389ea09887e2ce589a7621f74f2dd1" +
                "d5231967e179fa02a131d89d681adf3d3d2051ba05a4a012131e382c407cf1b29a0ee2a9c5be" +
                "0516f5307b905486a2ae2632ef0149feae8cce31b9094d25fe"
    private const val STREAM_EMPTY =
        "544445310137fe7a35a3b11af88000001092b143fe13188bb2b9da65efb6a8f255"
    private const val BYTES =
        "638512cbe300c222d8ad556a5d1ad5bfaced4fd1502dddc512c78ad2ec539dfc5d5d955bd887c12baedc" +
                "aba7b5ee71dbf9"
    private const val PBKDF2_UNICODE =
        "cf914a8f3978b1afb70c0bc3000188d6da7884ed68ce031864269ba72691ed04"

    /** RFC 7914 section 11 style vector: PBKDF2-HMAC-SHA256("password", "salt", 1, 32). */
    private const val PBKDF2_RFC =
        "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
    private const val KEYBACKUP =
        "54444b42010004baf0b30c1ca65a8ab2115fac688070914ea3000a646963613a206761746fea065e9c66" +
                "924515f3209e156f059e0bf0787275054468c208f199c23062b7d6046251cef134513883c8a0" +
                "662e9513966928b438a8e54191127a56f9"

    /** Returns one line per failed check; empty when every fixture round-trips. */
    fun run(): List<String> {
        val failures = mutableListOf<String>()
        val crypto = StreamCrypto()
        val kdf = PassphraseKdf()

        check(failures, "pbkdf2 rfc") {
            pbkdf2HmacSha256("password".toCharArray(), "salt".encodeToByteArray(), 1, 32)
                .hex() == PBKDF2_RFC
        }
        check(failures, "pbkdf2 unicode") {
            kdf.deriveKey(PASSPHRASE.toCharArray(), ByteArray(16) { it.toByte() }, 1000)
                .hex() == PBKDF2_UNICODE
        }
        check(failures, "stream decrypt") { decryptStream(crypto, STREAM).contentEquals(plain) }
        check(failures, "empty stream decrypt") { decryptStream(crypto, STREAM_EMPTY).isEmpty() }
        check(failures, "bytes decrypt") {
            crypto.decryptBytes(key, BYTES.unhex()).decodeToString() == "TeleDrive caption ✓"
        }
        check(failures, "frame decrypt") {
            val blob = STREAM.unhex()
            val salt = crypto.saltOf(blob.copyOfRange(0, crypto.headerSize()))
            val frame = blob.copyOfRange(crypto.frameStart(0).toInt(), blob.size)
            crypto.decryptFrame(key, salt, 0, frame).contentEquals(plain)
        }
        check(failures, "stream round trip") {
            val sealed = Buffer()
            crypto.encryptStream(key, Buffer().apply { write(plain) }, sealed)
            val opened = Buffer()
            crypto.decryptStream(key, sealed, opened)
            opened.readByteArray().contentEquals(plain)
        }
        check(failures, "tamper rejected") {
            val blob = STREAM.unhex()
            blob[blob.size - 5] = (blob[blob.size - 5] + 1).toByte()
            runCatching { decryptStream(crypto, blob.hex()) }.isFailure
        }
        val codec = KeyBackupCodec(kdf, crypto)
        check(failures, "key backup decode") {
            codec.decode(KEYBACKUP.unhex(), PASSPHRASE.toCharArray())?.contentEquals(contentKey) == true
        }
        check(failures, "key backup hint") {
            codec.readInfo(KEYBACKUP.unhex())?.hint == "dica: gato"
        }
        check(failures, "key backup wrong passphrase") {
            codec.decode(KEYBACKUP.unhex(), "wrong".toCharArray()) == null
        }
        return failures
    }

    private fun decryptStream(crypto: StreamCrypto, hex: String): ByteArray {
        val out = Buffer()
        crypto.decryptStream(key, Buffer().apply { write(hex.unhex()) }, out)
        return out.readByteArray()
    }

    private inline fun check(failures: MutableList<String>, name: String, block: () -> Boolean) {
        val passed = try {
            block()
        } catch (e: Throwable) {
            failures += "$name: ${e::class.simpleName}: ${e.message}"
            return
        }
        if (!passed) failures += "$name: mismatch"
    }

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.unhex(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
