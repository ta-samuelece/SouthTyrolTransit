package org.southtyrol.transit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** English, German and Italian must stay complete (Ladin deliberately falls back to German). */
class TranslationsTest {
    private val pattern = Regex("<(?:string|plurals) name=\"([^\"]+)\"")
    private fun keys(file: File) = pattern.findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test fun appAndDesignSystemAreFullyTranslated() {
        for (res in listOf(File("src/main/res"), File("../core/designsystem/src/main/res"))) {
            val base = keys(File(res, "values/strings.xml"))
            for (lang in listOf("de", "it")) {
                val translated = keys(File(res, "values-$lang/strings.xml"))
                assertEquals("missing in $lang ($res)", emptySet<String>(), base - translated)
                assertEquals("stale in $lang ($res)", emptySet<String>(), translated - base)
            }
        }
    }
}
