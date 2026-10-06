package org.southtyrol.transit.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Block structure of the small Markdown subset used in GitHub release notes. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data object Rule : MdBlock
}

object MarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    /** Headings, bullet and numbered lists, rules and paragraphs (soft-wrapped lines are joined). */
    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        for (raw in markdown.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                rule.matches(line) -> { flush(); blocks += MdBlock.Rule }
                heading.matches(line) -> { flush(); heading.find(line)!!.let { blocks += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2]) } }
                bullet.matches(line) -> { flush(); bullet.find(line)!!.let { blocks += MdBlock.Item("•", it.groupValues[2], it.groupValues[1].length / 2) } }
                numbered.matches(line) -> { flush(); numbered.find(line)!!.let { blocks += MdBlock.Item(it.groupValues[2] + ".", it.groupValues[3], it.groupValues[1].length / 2) } }
                // A continuation line of a list item (indented, no marker) belongs to that item.
                raw.startsWith("  ") && paragraph.isEmpty() && blocks.lastOrNull() is MdBlock.Item -> {
                    val last = blocks.removeAt(blocks.lastIndex) as MdBlock.Item
                    blocks += last.copy(text = last.text + " " + line.trim())
                }
                else -> paragraph.append(if (paragraph.isEmpty()) line.trim() else " " + line.trim())
            }
        }
        flush()
        return blocks
    }

    private val inline = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\(([^)\\s]+)\\)|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)")

    /** Bold, italic, inline code and links; anything else stays literal. */
    fun inline(text: String, code: SpanStyle, link: TextLinkStyles): AnnotatedString = buildAnnotatedString {
        var at = 0
        for (m in inline.findAll(text)) {
            append(text.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(g[1].ifEmpty { g[2] }, code, link)) }
                g[3].isNotEmpty() -> withStyle(code) { append(g[3]) }
                g[4].isNotEmpty() -> withLink(LinkAnnotation.Url(g[5], link)) { append(g[4]) }
                g[6].isNotEmpty() || g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6].ifEmpty { g[7] }) }
            }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }
}

/** Renders GitHub-flavoured release notes (the common subset) with Material typography. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val colors = MaterialTheme.colorScheme
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHighest, color = colors.onSurface)
    val link = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline))
    val body = MaterialTheme.typography.bodyMedium
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { i, block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    MarkdownParser.inline(block.text, code, link),
                    style = if (block.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 6.dp),
                )
                is MdBlock.Paragraph -> Text(MarkdownParser.inline(block.text, code, link), style = body)
                is MdBlock.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Text(block.marker, style = body, color = colors.primary, modifier = Modifier.width(if (block.marker == "•") 14.dp else 22.dp))
                    Text(MarkdownParser.inline(block.text, code, link), style = body)
                }
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
            }
        }
    }
}
