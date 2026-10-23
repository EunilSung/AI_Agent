package com.eunilsung.aiagent.ui.uikit.textfield

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunilsung.aiagent.ui.theme.AppColors

@Composable
fun TextFieldV1(
    modifier: Modifier = Modifier,
    inputText: String,
    hintText: String,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textColor: Color = AppColors.Text,
    cursorColor: Color = AppColors.PrimaryMain,
    bgColor: Color = AppColors.TextFieldBg,
    borderColor: Color = AppColors.Line,
    hintColor: Color = AppColors.TextHint,
    fontSize: TextUnit = 14.sp,
    round: Dp = 4.dp,
    singleLine: Boolean = false,
    onValueChange: (String) -> Unit
){
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .background(
                color = bgColor,
                shape = RoundedCornerShape(round)
            )
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(round)
            ),
        contentAlignment = Alignment.Center
    ){
        BasicTextField(
            value = inputText,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = singleLine,
            textStyle = TextStyle(
                color = textColor,
                fontSize = fontSize
            ),
            cursorBrush = SolidColor(cursorColor),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { innerTextField ->
                if (inputText.isEmpty()) {
                    Text(
                        text = hintText,
                        color = hintColor,
                        fontSize = fontSize
                    )
                }
                innerTextField()
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun TextFieldV1Preview() {
    MaterialTheme {
        TextFieldV1(
            inputText = "",
            hintText = "hint",
            onValueChange = {}
        )
    }
}
