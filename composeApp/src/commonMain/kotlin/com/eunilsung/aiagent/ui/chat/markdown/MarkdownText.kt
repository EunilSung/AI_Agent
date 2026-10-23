package com.eunilsung.aiagent.ui.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunilsung.aiagent.ui.theme.AppColors

/**
 * LLM 답변을 마크다운으로 그린다. 블록은 [MarkdownParser.parse], 줄 안 문법은 [MarkdownParser.parseInline] 이 나눈다.
 *
 * @param suffix 끝에 붙일 글자. 받는 중인 답변의 커서(▍)를 붙인다.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 15.sp,
    suffix: String = "",
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val subColor = AppColors.TextSub
    val codeBackground = AppColors.Gray100BgAuto
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            val tail = if (index == blocks.lastIndex) suffix else ""
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inline(block.text + tail, codeBackground),
                    color = color,
                    fontSize = if (block.level <= 2) fontSize * 1.15f else fontSize,
                    fontWeight = FontWeight.Bold,
                )
                is MarkdownBlock.Bullet -> ListRow(
                    marker = if (block.indent == 0) "•" else "◦",
                    indent = block.indent,
                    text = inline(block.text + tail, codeBackground),
                    color = color,
                    fontSize = fontSize,
                )
                is MarkdownBlock.Numbered -> ListRow(
                    marker = "${block.number}.",
                    indent = block.indent,
                    text = inline(block.text + tail, codeBackground),
                    color = color,
                    fontSize = fontSize,
                )
                is MarkdownBlock.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(AppColors.Line))
                    Text(
                        text = inline(block.text + tail, codeBackground),
                        color = subColor,
                        fontSize = fontSize,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                is MarkdownBlock.Code -> Box(
                    modifier = Modifier
                        .background(codeBackground, RoundedCornerShape(6.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(text = block.text + tail, color = color, fontSize = fontSize * 0.85f, fontFamily = FontFamily.Monospace)
                }
                is MarkdownBlock.Table -> MarkdownTable(block.rows, color, fontSize, codeBackground, tail)
                is MarkdownBlock.Paragraph -> Text(
                    text = inline(block.text + tail, codeBackground),
                    color = color,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.45f,
                )
            }
        }
        if (blocks.isEmpty() && suffix.isNotEmpty()) Text(text = suffix, color = color, fontSize = fontSize)
    }
}

@Composable
private fun ListRow(marker: String, indent: Int, text: AnnotatedString, color: Color, fontSize: TextUnit) {
    Row(modifier = Modifier.padding(start = (indent * 16).dp)) {
        Text(text = marker, color = color, fontSize = fontSize, modifier = Modifier.widthIn(min = 16.dp))
        Text(text = text, color = color, fontSize = fontSize, lineHeight = fontSize * 1.45f, modifier = Modifier.padding(start = 4.dp))
    }
}

/** 표 — 넓으면 가로로 밀어서 본다. 첫 줄은 머리글이다. */
@Composable
private fun MarkdownTable(rows: List<List<String>>, color: Color, fontSize: TextUnit, background: Color, tail: String) {
    val columns = rows.maxOf { it.size }
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    for (column in 0 until columns) {
                        val cell = row.getOrElse(column) { "" } + if (rowIndex == rows.lastIndex && column == columns - 1) tail else ""
                        Text(
                            text = inline(cell, background),
                            color = color,
                            fontSize = fontSize * 0.9f,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.widthIn(min = 48.dp, max = 220.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (span in MarkdownParser.parseInline(text)) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBackground else Color.Unspecified,
        )
        withStyle(style) { append(span.text) }
    }
}
