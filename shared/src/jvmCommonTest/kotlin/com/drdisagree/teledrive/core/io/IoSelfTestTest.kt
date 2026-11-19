package com.drdisagree.teledrive.core.io

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class IoSelfTestTest {

    /** java.io is the reference the iOS implementations are held to. */
    @Test
    fun `the java io layer passes the self test`() {
        val scratch = Files.createTempDirectory("io-self-test").toFile()
        try {
            assertEquals(emptyList<String>(), IoSelfTest.run(scratch))
        } finally {
            scratch.deleteRecursively()
        }
    }
}
