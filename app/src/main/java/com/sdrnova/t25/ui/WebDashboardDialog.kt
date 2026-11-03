package com.sdrnova.t25.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sdrnova.t25.radio.DashboardState

/** Turns the Wi-Fi web dashboard on or off and shows the address and PIN to type on the computer. */
@Composable
fun WebDashboardDialog(state: DashboardState, onToggle: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Web dashboard") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Edit channels and talkgroup aliases, and import or export sdrtrunk playlists, from a browser on " +
                        "the same Wi-Fi.",
                    color = Muted, fontSize = 14.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Share on Wi-Fi", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.running,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Color(0xFF00363D)),
                    )
                }
                if (state.running) {
                    Column(
                        Modifier.fillMaxWidth().background(PanelDeep, RoundedCornerShape(10.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Open in a browser", color = Muted, fontSize = 12.sp)
                        Text(state.url.orEmpty(), color = Cyan, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Text("PIN", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Text(state.pin.orEmpty().chunked(3).joinToString(" "), color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Anyone on this network with the PIN can change your channels. Turn sharing off when you're done; " +
                            "the PIN changes every time it's turned on.",
                        color = Muted, fontSize = 12.sp,
                    )
                }
                state.error?.let { Text(it, color = Alert, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
