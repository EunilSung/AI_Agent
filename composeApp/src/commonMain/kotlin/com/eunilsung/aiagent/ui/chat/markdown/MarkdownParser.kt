package com.eunilsung.aiagent.ui.chat.markdown

/** LLM 답변을 그릴 마크다운 블록. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    /** [indent] 는 하위 목록 단계(0부터)다. */
    data class Bullet(val indent: Int, val text: String) : MarkdownBlock
    data class Numbered(val indent: Int, val number: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

/** 굵게·기울임·코드 같은 줄 안 문법의 한 조각. */
data class InlineSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

/**
 * LLM 답변용 간단한 마크다운 해석기.
 *
 * 제목·목록·인용·코드 블록·표만 줄 단위로 나눈다. 받는 중에 닫히지 않은 코드 블록도 그대로 그린다.
 * 링크·이미지·HTML 은 다루지 않는다 — 사내 문서 답변에 나오지 않는다.
 */
object MarkdownParser {
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^[-*+]\\s+(.*)$")
    private val numberedRegex = Regex("^(\\d+)[.)]\\s+(.*)$")
    private val quoteRegex = Regex("^>\\s?(.*)$")
    private val whitespaceRegex = Regex("\\s+")

    fun parse(text: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = mutableListOf<String>()
        val table = mutableListOf<List<String>>()
        var code: MutableList<String>? = null

        fun flush() {
            if (paragraph.isNotEmpty()) blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n")).also { paragraph.clear() }
            if (table.isNotEmpty()) blocks += MarkdownBlock.Table(table.toList()).also { table.clear() }
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            val indent = (raw.takeWhile { it == ' ' || it == '\t' }.length / 2).coerceAtMost(3)

            if (line.startsWith("```")) {
                val open = code
                if (open != null) {
                    blocks += MarkdownBlock.Code(open.joinToString("\n"))
                    code = null
                } else {
                    flush()
                    code = mutableListOf()
                }
                continue
            }
            code?.let { it += raw; continue }

            if (line.startsWith("|")) {
                if (paragraph.isNotEmpty()) flush()
                val cells = line.trim('|').split('|').map { it.trim() }
                val isSeparator = cells.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' || it == ' ' } }
                if (!isSeparator) table += cells
                continue
            }
            if (line.isEmpty()) {
                flush()
                continue
            }

            val heading = headingRegex.find(line)
            val bullet = bulletRegex.find(line)
            val numbered = numberedRegex.find(line)
            val quote = quoteRegex.find(line)
            when {
                heading != null -> { flush(); blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]) }
                line.length >= 3 && line.all { it == '-' || it == '*' || it == '_' } -> flush()
                bullet != null -> { flush(); blocks += MarkdownBlock.Bullet(indent, bullet.groupValues[1]) }
                numbered != null -> { flush(); blocks += MarkdownBlock.Numbered(indent, numbered.groupValues[1], numbered.groupValues[2]) }
                quote != null -> { flush(); blocks += MarkdownBlock.Quote(quote.groupValues[1]) }
                else -> {
                    if (table.isNotEmpty()) flush()
                    paragraph += line
                }
            }
        }
        code?.let { blocks += MarkdownBlock.Code(it.joinToString("\n")) }
        flush()
        return blocks
    }

    /** 줄 안 문법(`**굵게**`, `*기울임*`·`_기울임_`, `` `코드` ``)을 조각으로 나눈다. 짝이 없는 기호는 글자 그대로 둔다. */
    fun parseInline(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) spans += InlineSpan(plain.toString()).also { plain.clear() }
        }

        while (i < text.length) {
            val marker = when {
                text.startsWith("**", i) -> "**"
                text.startsWith("__", i) -> "__"
                text[i] == '`' -> "`"
                text[i] == '*' || text[i] == '_' -> text[i].toString()
                else -> null
            }
            val end = marker?.let { text.indexOf(it, i + it.length) } ?: -1
            if (marker != null && end > i + marker.length && isEmphasisBoundary(text, marker, i, end)) {
                flushPlain()
                val inner = text.substring(i + marker.length, end)
                spans += when (marker) {
                    "**", "__" -> InlineSpan(inner, bold = true)
                    "`" -> InlineSpan(inner, code = true)
                    else -> InlineSpan(inner, italic = true)
                }
                i = end + marker.length
            } else {
                plain.append(text[i])
                i++
            }
        }
        flushPlain()
        return spans
    }

    /**
     * 기호를 지운 한 줄 글. 대화 목록 미리보기처럼 꾸밈 없이 짧게 보여 줄 때 쓴다.
     * 제목·목록·인용·강조 기호는 빼고, 번호 목록의 번호와 표의 칸 글자는 남긴다.
     */
    fun toPlainText(text: String): String =
        parse(text).joinToString(" ") { block ->
            when (block) {
                is MarkdownBlock.Heading -> plainInline(block.text)
                is MarkdownBlock.Bullet -> plainInline(block.text)
                is MarkdownBlock.Numbered -> "${block.number}. ${plainInline(block.text)}"
                is MarkdownBlock.Quote -> plainInline(block.text)
                is MarkdownBlock.Code -> block.text
                is MarkdownBlock.Table -> block.rows.flatten().joinToString(" ") { plainInline(it) }
                is MarkdownBlock.Paragraph -> plainInline(block.text)
            }
        }.replace(whitespaceRegex, " ").trim()

    private fun plainInline(text: String): String = parseInline(text).joinToString("") { it.text }

    /**
     * 기호가 강조로 쓰였는지. 단어 안의 `_`(파일 이름 `01_휴가규정.md`)와 띄어 쓴 `*`(곱하기)는 강조가 아니다.
     * CommonMark 의 규칙을 줄인 것이다.
     */
    private fun isEmphasisBoundary(text: String, marker: String, start: Int, end: Int): Boolean {
        if (marker == "`") return true
        val inner = text.substring(start + marker.length, end)
        if (inner.first().isWhitespace() || inner.last().isWhitespace()) return false
        if (marker.first() != '_') return true
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end + marker.length)
        return (before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())
    }
}
