package com.bitchat.android.shell

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.account.Account
import com.bitchat.android.account.Session
import com.bitchat.android.account.SignInScreen
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.theme.BitchatMotion
import kotlinx.coroutines.delay

/** Which face of the app is in front. */
enum class ShellMode { Online, Mesh }

/**
 * What the switcher needs to know to show the mesh-side controls ("Back online", "Use mesh").
 */
data class ShellControls(
    val mode: ShellMode,
    /** The online app could be opened right now. */
    val onlineAvailable: Boolean,
    /** The person chose the mesh by hand; automatic switching is off until they leave it. */
    val pinnedMesh: Boolean,
    val goOnline: () -> Unit,
    val useMesh: () -> Unit,
)

private const val PREFS = "bitchat_shell"

/**
 * The one app: Indradhanu when the command centre is reachable, BiChat when it is not.
 *
 *  - Signs the person in first (or lets a citizen continue without an account).
 *  - Online: the PWA for their role (`/citizen` or `/field`) in a WebView, already signed in.
 *  - Offline, or when the page fails, or when the person taps "Use mesh": the native mesh
 *    app, picking up the route, open incident and draft the PWA last reported.
 *  - When the connection comes back the online app returns on its own, unless the person
 *    pinned the mesh by hand.
 */
@Composable
fun AppShell(
    chatViewModel: ChatViewModel,
    meshContent: @Composable (ShellControls) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val session by Account.session.collectAsStateWithLifecycle()
    val skipped by Account.skipped.collectAsStateWithLifecycle()
    val config by Account.config.collectAsStateWithLifecycle()
    val online by NetworkMonitor.online.collectAsStateWithLifecycle()

    var pinnedMesh by remember { mutableStateOf(prefs.getBoolean("pinned_mesh", false)) }
    var failedAt by remember { mutableLongStateOf(0L) }
    var showSignIn by remember { mutableStateOf(false) }

    // Refresh the public config and the role whenever the network comes back.
    LaunchedEffect(online) {
        if (online) {
            Account.fetchConfig()
            if (Account.session.value != null) Account.reverify()
        }
    }
    // Apply the signed-in role to the mesh identity (also after a promotion online).
    LaunchedEffect(session?.meshRole) {
        session?.let { s ->
            if (chatViewModel.localRole.value != s.meshRole) chatViewModel.setLocalRole(s.meshRole)
        }
    }
    // A failed page load holds the mesh for a minute, then online is tried again.
    LaunchedEffect(failedAt) {
        if (failedAt > 0) {
            delay(60_000)
            failedAt = 0
        }
    }

    val needsSignIn = session == null && !skipped
    if (needsSignIn || showSignIn) {
        SignInScreen(
            onSignedIn = { s -> onSignedIn(chatViewModel, s); showSignIn = false },
            onContinueWithout = if (needsSignIn) ({ Account.setSkipped(true) }) else null,
            onCancel = if (!needsSignIn) ({ showSignIn = false }) else null,
        )
        return
    }

    val appUrl = config?.appUrl.orEmpty()
    val onlineAvailable = online && appUrl.startsWith("http") && failedAt == 0L
    val mode = if (onlineAvailable && !pinnedMesh) ShellMode.Online else ShellMode.Mesh

    val controls = ShellControls(
        mode = mode,
        onlineAvailable = onlineAvailable,
        pinnedMesh = pinnedMesh,
        goOnline = {
            pinnedMesh = false
            failedAt = 0
            prefs.edit().putBoolean("pinned_mesh", false).apply()
        },
        useMesh = {
            pinnedMesh = true
            prefs.edit().putBoolean("pinned_mesh", true).apply()
        },
    )

    AnimatedContent(
        targetState = mode,
        transitionSpec = {
            fadeIn(tween(BitchatMotion.EMPHASIZED_MS)) togetherWith fadeOut(tween(BitchatMotion.STANDARD_MS))
        },
        label = "shell",
    ) { m ->
        when (m) {
            ShellMode.Online -> {
                val path = session?.pwaPath ?: "/citizen"
                OnlineAppScreen(
                    url = appUrl + path,
                    bridgeFactory = { currentUrl ->
                        BiChatBridge(
                            allowedOrigin = BiChatBridge.originOf(appUrl),
                            currentUrl = currentUrl,
                            onSwitchToMesh = controls.useMesh,
                            onSendText = { text -> chatViewModel.sendMessage(text) },
                            peerCount = { chatViewModel.connectedPeers.value.size },
                            onSignedOut = { Account.signOut() },
                        )
                    },
                    onUnreachable = { failedAt = System.currentTimeMillis() },
                    onExit = { (context as? android.app.Activity)?.moveTaskToBack(true) },
                )
            }
            ShellMode.Mesh -> meshContent(controls)
        }
    }

    // Exposed for the settings screen ("Account" → sign in / switch account).
    LaunchedEffect(Unit) { ShellActions.openSignIn = { showSignIn = true } }
}

/** Hooks other screens use to reach the shell without threading callbacks everywhere. */
object ShellActions {
    var openSignIn: () -> Unit = {}
}

private fun onSignedIn(vm: ChatViewModel, s: Session) {
    vm.setLocalRole(s.meshRole)
    // Use the person's name on the mesh unless they already chose one.
    val current = vm.nickname.value
    if (current.isBlank() || current.startsWith("anon")) vm.setNickname(s.displayName.take(24))
}
