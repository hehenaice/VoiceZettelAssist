package com.voicezettel.assist

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicezettel.assist.ui.theme.VoiceZettelTheme
import kotlinx.coroutines.launch

/**
 * Settings activity — single-screen Compose UI for configuring the app.
 *
 * Sections:
 *   1. Permissions card (mic, overlay, accessibility) with deep-links into Settings.
 *   2. Gemini API key (masked, stored in EncryptedSharedPreferences).
 *   3. Zettel Notes vault folder picker (SAF + takePersistableUriPermission).
 *   4. Trigger combination dropdown.
 *   5. Accessibility service status card.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceZettelTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize()
                ) {
                    SettingsScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // SAF folder picker — note the persistable permission flag so we can later
    // call takePersistableUriPermission() on the returned URI.
    val pickVaultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                viewModel.setVaultUri(uri.toString())
                scope.launch { snackbarHostState.showSnackbar("Vault folder saved") }
            } catch (e: SecurityException) {
                Log.e("MainActivity", "Could not persist URI permission", e)
                scope.launch { snackbarHostState.showSnackbar("Failed to persist vault access") }
            }
        }
    }

    // Re-check accessibility status whenever the activity comes back to the
    // foreground (user typically toggles the service in system Settings then
    // returns here).
    val lifecycleOwner = LocalLifecycleOwner.current
    var accessibilityActive by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityActive = isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val geminiApiKey by viewModel.geminiApiKey.collectAsState()
    val vaultUriString by viewModel.vaultUriString.collectAsState()
    val triggerCombo by viewModel.triggerCombo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ───────────── Permissions card ─────────────
            Section(title = stringResource(R.string.settings_section_permissions)) {
                PermissionRow(
                    label = "Microphone (RECORD_AUDIO)",
                    granted = hasMicPermission(context),
                    actionLabel = "Open",
                    onAction = { openAppDetailsSettings(context) }
                )
                Divider()
                PermissionRow(
                    label = "Display over other apps (SYSTEM_ALERT_WINDOW)",
                    granted = Settings.canDrawOverlays(context),
                    actionLabel = "Open",
                    onAction = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )
                Divider()
                PermissionRow(
                    label = "Accessibility Service",
                    granted = accessibilityActive,
                    actionLabel = if (accessibilityActive) "Open" else "Enable",
                    onAction = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }

            // ───────────── Gemini API key ─────────────
            Section(title = "Gemini API Key") {
                ApiKeyEditor(
                    initialValue = geminiApiKey,
                    onSave = {
                        viewModel.setGeminiApiKey(it)
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.settings_gemini_key_saved)
                            )
                        }
                    }
                )
            }

            // ───────────── Vault folder ─────────────
            Section(title = stringResource(R.string.settings_section_vault)) {
                VaultPicker(
                    vaultUri = vaultUriString,
                    onPick = { pickVaultLauncher.launch(null) }
                )
            }

            // ───────────── Trigger combo ─────────────
            Section(title = stringResource(R.string.settings_section_trigger)) {
                TriggerComboPicker(
                    selected = triggerCombo,
                    onSelect = { viewModel.setTriggerCombo(it) }
                )
            }

            // ───────────── Status card ─────────────
            Section(title = "Service Status") {
                ServiceStatusCard(active = accessibilityActive) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }

            // ───────────── About ─────────────
            Section(title = stringResource(R.string.settings_section_about)) {
                Text(
                    text = "VoiceZettelAssist v1.0.0\n" +
                        "Listens for a hardware key combo, records a short voice note, " +
                        "transcribes it with Gemini 1.5 Flash, and appends it to today's " +
                        "Markdown daily note inside your Zettel Notes vault.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ───────────────────────── Section card primitives ─────────────────────────

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (granted) Color(0xFF2E7D32) else Color(0xFFC62828),
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun ApiKeyEditor(
    initialValue: String,
    onSave: (String) -> Unit
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    var showKey by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.settings_gemini_key_label)) },
            placeholder = { Text(stringResource(R.string.settings_gemini_key_placeholder)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            TextButton(onClick = { showKey = !showKey }) {
                Text(if (showKey) "Hide" else "Show")
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSave(text) },
                enabled = text.isNotBlank()
            ) { Text("Save") }
        }
    }
}

@Composable
private fun VaultPicker(
    vaultUri: String,
    onPick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (vaultUri.isEmpty())
                    stringResource(R.string.settings_vault_unset)
                else
                    stringResource(R.string.settings_vault_set, vaultUri),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_vault_pick_button))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerComboPicker(
    selected: SecurePrefs.TriggerCombo,
    onSelect: (SecurePrefs.TriggerCombo) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.settings_trigger_dropdown_label)) },
            trailingIcon = {
                androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        androidx.compose.material3.ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            SecurePrefs.TriggerCombo.values().forEach { combo ->
                DropdownMenuItem(
                    text = { Text(combo.displayName) },
                    onClick = {
                        onSelect(combo)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(active: Boolean, onOpenSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (active) Color(0xFF2E7D32) else Color(0xFFC62828),
                        shape = CircleShape
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (active) stringResource(R.string.settings_status_active)
                else stringResource(R.string.settings_status_inactive),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_open_accessibility))
        }
    }
}

// ───────────────────────── Permissions / status helpers ─────────────────────────

private fun hasMicPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}

/**
 * Detects whether our [VoiceAccessibilityService] is enabled by iterating the
 * system's list of enabled accessibility services.
 */
private fun isAccessibilityEnabled(context: Context): Boolean {
    val expectedComponent = ComponentName(context, VoiceAccessibilityService::class.java)
    val expectedFlat = expectedComponent.flattenToString()

    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabled.split(":").any { it.equals(expectedFlat, ignoreCase = true) }
}
