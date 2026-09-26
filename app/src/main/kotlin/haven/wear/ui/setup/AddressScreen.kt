package haven.wear.ui.setup

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import haven.wear.library.shortAddress
import haven.wear.ui.components.QrCode
import haven.wear.ui.theme.HavenColors

/**
 * The whole setup: this is your watch's address. No phrase to write down, no words to confirm,
 * no account. Scan it from a phone wallet and send it the tokens your music is gated on.
 *
 * The same screen is reachable from Settings and from every locked track, so there is nothing to
 * remember from here.
 */
@Composable
fun AddressScreen(
    address: String?,
    onDone: (() -> Unit)?,
    caption: String = "Send tokens here to unlock music.",
) {
    val scroll = rememberScrollState()
    ScreenScaffold(
        scrollState = scroll,
        timeText = {},
        edgeButton = {
            if (onDone != null) EdgeButton(onClick = onDone) { Text("Continue") }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(
            modifier = Modifier.verticalScroll(scroll).padding(padding).padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (address == null) {
                CircularProgressIndicator()
                return@Column
            }
            QrCode(text = address, size = 96.dp, description = "Watch address QR code")
            Spacer(Modifier.height(8.dp))
            Text(
                text = shortAddress(address),
                style = MaterialTheme.typography.titleSmall,
                color = HavenColors.Ink,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = HavenColors.Muted,
                textAlign = TextAlign.Center,
            )
        } }
    }
}
