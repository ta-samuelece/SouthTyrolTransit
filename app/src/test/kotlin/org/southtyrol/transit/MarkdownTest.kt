package org.southtyrol.transit

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.southtyrol.transit.feature.common.MarkdownParser
import org.southtyrol.transit.feature.common.MdBlock

class MarkdownTest {
    @Test fun parsesReleaseNoteBlocks() {
        val md = """
            Intro line one
            continues here.

            ## New in 0.1.1
            - **In-app updates**: checks
              on start
            * second
            1. first step
            ---
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertEquals(MdBlock.Paragraph("Intro line one continues here."), blocks[0])
        assertEquals(MdBlock.Heading(2, "New in 0.1.1"), blocks[1])
        assertEquals(MdBlock.Item("•", "**In-app updates**: checks on start", 0), blocks[2])
        assertEquals(MdBlock.Item("•", "second", 0), blocks[3])
        assertEquals(MdBlock.Item("1.", "first step", 0), blocks[4])
        assertEquals(MdBlock.Rule, blocks[5])
    }

    @Test fun inlineFormattingRemovesMarkers() {
        val text = MarkdownParser.inline("Get **this** and `that.apk` from [GitHub](https://github.com) *now*", SpanStyle(), TextLinkStyles())
        assertEquals("Get this and that.apk from GitHub now", text.text)
        assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && text.text.substring(it.start, it.end) == "this" })
        assertTrue(text.getLinkAnnotations(0, text.length).any { (it.item as? LinkAnnotation.Url)?.url == "https://github.com" })
    }
}
