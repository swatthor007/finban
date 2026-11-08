package devr.shellshock.finban

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hard rule: the app never says a message is "safe", "real" or "genuine". Checks every user-visible string resource. */
class WordingTest {
    @Test fun noSafeRealGenuineInStrings() {
        val banned = Regex("""\b(safe|real|genuine|authentic|legit)\b""", RegexOption.IGNORE_CASE)
        val files = File("src/main/res").walkTopDown().filter { it.name == "strings.xml" }.toList()
        assertTrue(files.isNotEmpty())
        val bad = files.flatMap { f -> f.readLines().filter { "<string " in it && banned.containsMatchIn(it) }.map { "${f.path}: $it" } }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
