package com.cashflow.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashflow.app.R
import com.cashflow.app.ui.theme.cashflow

/** Two big side-by-side buttons (stacked when the system font is very large so labels never clip). */
@Composable
fun KindButtons(onExpense: () -> Unit, onIncome: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.cashflow
    val stacked = LocalDensity.current.fontScale > 1.3f
    val expense = @Composable { m: Modifier ->
        BigActionButton(stringResource(R.string.button_expense), colors.expenseFill, colors.buttonLabel, onExpense, m)
    }
    val income = @Composable { m: Modifier ->
        BigActionButton(stringResource(R.string.button_income), colors.incomeFill, colors.buttonLabel, onIncome, m)
    }
    if (stacked) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            expense(Modifier.fillMaxWidth())
            income(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            expense(Modifier.weight(1f))
            income(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BigActionButton(label: String, fill: Color, labelColor: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier.pressScale(source),
        shape = MaterialTheme.shapes.large,
        color = fill,
        contentColor = labelColor,
        interactionSource = source,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                label.uppercase(),
                color = labelColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}
