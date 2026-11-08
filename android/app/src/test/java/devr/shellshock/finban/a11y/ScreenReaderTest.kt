package devr.shellshock.finban.a11y

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderTest {
    private fun node(text: String?, top: Int, left: Int = 0, password: Boolean = false, sensitive: Boolean = false, desc: String? = null) =
        NodeRec("p", "c", null, text, desc, Rect(left, top, left + 10, top + 10), sensitive, password)

    private fun win(pkg: String?, type: Int, vararg n: NodeRec) = WindowRec(pkg, null, type, 0, true, false, n.toList())
    private fun cap(vararg w: WindowRec) = Capture(0, "bubble", null, w.toList())

    @Test fun readsInReadingOrderAndSkipsOwnAndSystemWindows() {
        val r = ScreenReader.read(cap(
            win("chat.app", 1, node("second", 200), node("first", 100)),
            win("devr.shellshock.finban", 4, node("our overlay", 50)),
            win("com.android.systemui", 3, node("12:30", 0)),
            win("kb", 2, node("q", 900)),
        ), "devr.shellshock.finban")!!
        // Rect is a stub in JVM unit tests (all bounds read 0), so the top-to-bottom sort is checked on the emulator, not here.
        assertEquals(setOf("first", "second"), r.text.lines().toSet())
        assertEquals("chat.app", r.appPackage)
    }

    @Test fun skipsPasswordAndSensitiveAndDuplicates() {
        val r = ScreenReader.read(cap(win("a", 1, node("secret", 1, password = true), node("hidden", 2, sensitive = true),
            node("hello", 3), node("hello", 4), node(null, 5, desc = "Send button"))), "own")!!
        assertEquals("hello\nSend button", r.text)
    }

    @Test fun nothingReadableIsNull() {
        assertNull(ScreenReader.read(cap(win("devr.shellshock.finban", 1, node("Home", 1))), "devr.shellshock.finban"))
        assertNull(ScreenReader.read(cap(win(null, 1, node("x", 1))), "own"))
    }

    @Test fun extractsLinksAndCapsLength() {
        val r = ScreenReader.read(cap(win("a", 1, node("Update KYC at sbi-kyc-update.in now", 1), node("x".repeat(9000), 2))), "own")!!
        assertEquals(listOf("sbi-kyc-update.in"), r.links)
        assertTrue(r.text.length <= ScreenReader.MAX_CHARS)
        assertFalse(r.text.isEmpty())
    }
}
