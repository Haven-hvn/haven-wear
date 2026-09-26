package haven.wear.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import haven.wear.ui.components.Glyphs
import haven.wear.ui.theme.HavenColors
import kotlinx.coroutines.launch

/**
 * Show recovery phrase.
 *
 * Three gates before a word appears: an explanation of what these words are, the watch's screen
 * lock, and a secure window (no screenshots, no screen recording, blank in the app switcher). The
 * words vanish the moment the screen is left. They are never copied, logged, synced or sent.
 */
@Composable
fun RecoveryPhraseScreen(loadWords: suspend () -> List<String>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<String>?>(null) }
    var lockMissing by remember { mutableStateOf(false) }

    SecureWindow()
    HideWhenBackgrounded { words = null }

    val confirm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) scope.launch { words = loadWords() }
    }

    val shown = words
    if (shown == null) {
        Intro(
            lockMissing = lockMissing,
            onShow = {
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (!keyguard.isDeviceSecure) {
                    lockMissing = true
                    return@Intro
                }
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent("Recovery phrase", "Confirm it's you")
                if (intent != null) confirm.launch(intent) else lockMissing = true
            },
            onSetLock = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )
    } else {
        Words(shown, onDone = {
            words = null
            onDone()
        })
    }
}

@Composable
private fun Intro(lockMissing: Boolean, onShow: () -> Unit, onSetLock: () -> Unit) {
    val scroll = rememberScrollState()
    ScreenScaffold(
        scrollState = scroll,
        timeText = {},
        edgeButton = {
            if (lockMissing) EdgeButton(onClick = onSetLock) { Text("Set screen lock") }
            else EdgeButton(onClick = onShow) { Text("Show") }
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(
            Modifier.verticalScroll(scroll).padding(padding).padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Glyphs.Lock, contentDescription = null, tint = HavenColors.Ember, modifier = Modifier.size(22.dp))
            Text("Recovery phrase", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            Text(
                if (lockMissing) {
                    "Set a screen lock on this watch first. It protects these words."
                } else {
                    "12 words that restore this watch's wallet in any Ethereum wallet. Anyone who sees them can take what it holds."
                },
                style = MaterialTheme.typography.bodySmall,
                color = HavenColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        } }
    }
}

/** One word per line, numbered, large — easy to copy onto paper, turn by turn of the crown. */
@Composable
private fun Words(words: List<String>, onDone: () -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = state, timeText = {}, edgeButton = { EdgeButton(onClick = onDone) { Text("Done") } }) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) {
            item { ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text("Write these down") } }
            itemsIndexed(words) { index, word ->
                Row(
                    Modifier.fillMaxWidth().transformedHeight(this, spec).padding(vertical = 4.dp, horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = HavenColors.Faint,
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        word,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = HavenColors.Ink,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

/** FLAG_SECURE for as long as this screen is on the back stack. */
@Composable
private fun SecureWindow() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun HideWhenBackgrounded(onHide: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onHide() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
