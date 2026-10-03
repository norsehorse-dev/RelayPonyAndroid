package com.relaypony.session.pairing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** The RPX1 line matches RelayPonyPake's vectors/pake_vectors.json (data_a_plain). */
class PakeDetailsTests {
    private val ref = "RPX1|age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72|Test+Phone+8|QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8|"

    @Test
    fun matchesTheRustVector() {
        val d = PakeDetails("age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72", "Test Phone 8",
            "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8", "")
        assertEquals(ref, d.encode())
        assertEquals(d, PakeDetails.decode(ref))
        val custom = d.copy(relay = "https://relay.example.com")
        assertEquals(custom, PakeDetails.decode(custom.encode()))
    }

    @Test
    fun rejectsJunk() {
        assertThrows(IllegalArgumentException::class.java) { PakeDetails.decode("RPX1|nope|x|y|") }
        assertThrows(IllegalArgumentException::class.java) { PakeDetails.decode(ref.replace("RPX1", "RPX2")) }
    }
}
