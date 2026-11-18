package com.drdisagree.teledrive.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoSelfTestTest {

    @Test
    fun `fixtures from the original JVM implementation still decrypt`() {
        assertEquals(emptyList<String>(), CryptoSelfTest.run())
    }
}
