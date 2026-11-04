package com.duoopen.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Why the Accessibility toggle is greyed out for a sideloaded app, and the
 * way around it. Android hides "Allow restricted settings" until the user
 * has tried the toggle once and been refused, hence the order of the steps.
 */
@Composable
fun SetupGuide(onOpenAccessibility: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Toggle greyed out?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Android blocks Accessibility for apps installed from a file until you allow it " +
                        "(it's the permission banking malware abuses, so every sideloaded app gets the warning). " +
                        "eyeFold asks for no other permission and has no network access.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Step(1, "In Accessibility, tap “eyeFold full-screen fold” once so Android shows the “restricted setting” message.")
                Step(2, "Open this app's info page (button below) → ⋮ menu at the top right → Allow restricted settings. Confirm with your PIN.")
                Step(3, "Back in Accessibility, turn the toggle on.")
                if (samsung) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Samsung: if the option is missing, turn off Settings → Security and privacy → Auto Blocker first, " +
                            "then repeat from step 1. Older One UI shows “Allow restricted settings” lower on the app info page instead of in the menu.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Still blocked? Installing over USB skips the check entirely:\n" +
                        "adb install -r DuoOpen.apk\n" +
                        "or enable the service directly:\n" +
                        "adb shell settings put secure enabled_accessibility_services " +
                        "com.duoopen/com.duoopen.overlay.FoldOverlayService",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { openAppInfo(context) }) { Text("App info") }
        },
        dismissButton = {
            TextButton(onClick = onOpenAccessibility) { Text("Accessibility") }
        },
    )
}

@Composable
private fun Step(n: Int, text: String) {
    Text("$n.  $text", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
}

private fun openAppInfo(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        Toast.makeText(context, "Couldn't open app info", Toast.LENGTH_SHORT).show()
    }
}
