package devr.shellshock.finban.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractorsTest {
    private fun urls(t: String) = Extractors.find(t).filter { it.kind == Extractors.Kind.URL }.map { it.text }

    @Test fun `bare address as Chrome shows it`() {
        assertEquals(listOf("a-b.ngrok-free.dev/demo/bank-login"), urls("a-b.ngrok-free.dev/demo/bank-login Sign in"))
        assertEquals(listOf("sbikyc.store/x"), urls("open sbikyc.store/x"))
    }
}
