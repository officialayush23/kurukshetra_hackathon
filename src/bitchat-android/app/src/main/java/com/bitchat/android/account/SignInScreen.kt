package com.bitchat.android.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitchat.android.ui.design.AppleButton
import com.bitchat.android.ui.design.AppleTextField
import com.bitchat.android.ui.design.ButtonKind
import com.bitchat.android.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.launch

/**
 * Sign in with the Indradhanu account (citizen, field crew or staff).
 *
 * One screen, three fields at most. The command centre address is asked for only when the
 * phone does not know it yet; once known it collapses to a single line that can be changed.
 * A citizen may continue without an account: the mesh, SOS and the map work regardless.
 */
@Composable
fun SignInScreen(
    onSignedIn: (Session) -> Unit,
    onContinueWithout: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalBitchatPalette.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    val knownServer = remember { Account.commandCentreUrl }
    var server by rememberSaveable { mutableStateOf(knownServer) }
    var editServer by rememberSaveable { mutableStateOf(knownServer.isBlank()) }
    var email by rememberSaveable { mutableStateOf(Account.session.value?.email.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val config by Account.config.collectAsState()

    LaunchedEffect(Unit) { if (knownServer.isNotBlank()) Account.fetchConfig() }

    fun submit() {
        if (busy) return
        error = null
        if (server.isBlank()) { error = "Enter the command centre address."; return }
        if (!email.contains('@')) { error = "Enter the email for your account."; return }
        if (password.isEmpty()) { error = "Enter your password."; return }
        focus.clearFocus()
        busy = true
        scope.launch {
            Account.setApiBase(normaliseServer(server))
            when (val r = Account.signIn(email, password)) {
                is SignInResult.Success -> onSignedIn(r.session)
                is SignInResult.Failure -> error = r.message
            }
            busy = false
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(palette.groupedBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onCancel != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    AppleButton("Cancel", onCancel, kind = ButtonKind.Plain, height = 44.dp)
                }
            } else {
                Spacer(Modifier.height(56.dp))
            }

            Box(
                Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.CellTower, null, tint = Color.White, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Sign in", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use your Indradhanu account. Field crews and staff use the account the control room issued.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            AnimatedVisibility(editServer, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column {
                    AppleTextField(
                        value = server,
                        onValueChange = { server = it; error = null },
                        label = "Command centre",
                        placeholder = "https://api.example.org",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (!editServer) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 16.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Command centre · " + server.removePrefix("https://").removePrefix("http://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    AppleButton("Change", { editServer = true }, kind = ButtonKind.Plain, height = 44.dp)
                }
            }

            AppleTextField(
                value = email,
                onValueChange = { email = it; error = null },
                label = "Email",
                placeholder = "name@example.org",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                enabled = !busy,
            )
            Spacer(Modifier.height(16.dp))
            AppleTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "Password",
                secure = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                error = error,
                enabled = !busy,
            )
            if (config != null && config?.signInAvailable == false) {
                Text(
                    "This command centre has not enabled sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            AppleButton("Sign in", ::submit, Modifier.fillMaxWidth(), loading = busy, icon = Icons.Rounded.Lock)
            if (onContinueWithout != null) {
                Spacer(Modifier.height(8.dp))
                AppleButton(
                    "Continue without an account", onContinueWithout, Modifier.fillMaxWidth(),
                    kind = ButtonKind.Plain, enabled = !busy,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "After you sign in once, BiChat remembers you and works without internet. " +
                    "Messages then travel phone to phone over Bluetooth and Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
            )
        }
    }
}

/** Accept "api.example.org" as well as a full URL; drop a pasted `/api/v1` suffix. */
internal fun normaliseServer(raw: String): String {
    var s = raw.trim().trimEnd('/')
    if (s.isEmpty()) return s
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    val cut = s.indexOf("/api/v1")
    return if (cut > 0) s.substring(0, cut) else s
}
