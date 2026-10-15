package com.cashflow.app.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashflow.app.data.LedgerRepository

/** The real hooks of the Lab build (src/lab/java). The other variants compile no-op stand-ins from src/labStub/java. */
object LabHooks {
    fun onAppCreate(app: android.app.Application) = LabState.init(app)

    /** Off by default in the Lab: its status-bar notifications start only when the Lab card's switch is on. The bell works either way. */
    fun systemNotificationsAllowed(): Boolean = LabState.notificationsOn

    /** The small amber "LAB" tag in the top bar of every main screen. */
    @Composable
    fun Tag() {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFFFB300))
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .semantics { contentDescription = "Lab build" },
        ) {
            Text("LAB", color = Color(0xFF1A1200), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun Card(repository: LedgerRepository, onDataChanged: () -> Unit) = LabCard(repository, onDataChanged)
}
