package com.lian.plus

import com.lian.plus.core.model.Quant
import com.lian.plus.core.model.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class QuantTest {

    @Test
    fun `reads the quantisation out of a file name`() {
        assertEquals(Quant.Q4_K_M, Quant.fromFileName("Llama-3.2-3B-Instruct-Q4_K_M.gguf"))
        assertEquals(Quant.Q4_K_S, Quant.fromFileName("model-q4_k_s.gguf"))
        assertEquals(Quant.Q8_0, Quant.fromFileName("sd_turbo-f16-q8_0.gguf"))
        assertEquals(Quant.IQ4_XS, Quant.fromFileName("qwen-IQ4_XS.gguf"))
    }

    @Test
    fun `unknown names do not pretend to be a quantisation`() {
        assertEquals(Quant.UNKNOWN, Quant.fromFileName("weights.gguf"))
    }

    @Test
    fun `longer tags win over their own prefixes`() {
        // Q4_K_M must not be reported as Q4_K_S or Q4_0.
        assertEquals(Quant.Q4_K_M, Quant.fromFileName("a-Q4_K_M-b.gguf"))
    }

    @Test
    fun `byte formatting is readable`() {
        assertEquals("1.00 GB", formatBytes(1_073_741_824))
        assertEquals("512 MB", formatBytes(512L * 1024 * 1024))
    }
}
