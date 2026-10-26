package cat.doorman.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cat.doorman.app.R
import cat.doorman.app.data.Redirect

/**
 * Where a block that holds you sends you: nowhere, another app, or a WhatsApp
 * chat. One row in settings, the choice in a dialog -- it is set once and
 * rarely changed, so it does not earn a screen of its own.
 */
@Composable
fun RedirectSection(
    current: Redirect?,
    apps: List<OtherApp>,
    icons: AppIcons,
    onPick: (Redirect?) -> Unit,
) {
    var step by remember { mutableStateOf<Step?>(null) }

    NavRow(
        icon = Icons.AutoMirrored.Outlined.Send,
        title = stringResource(R.string.redirect_title),
        supporting = when (current) {
            null -> stringResource(R.string.redirect_nowhere)
            is Redirect.App -> stringResource(
                R.string.redirect_summary_app,
                apps.firstOrNull { it.packageName == current.packageName }?.label ?: current.packageName,
            )
            is Redirect.WhatsAppChat -> stringResource(
                R.string.redirect_summary_chat,
                current.name.ifBlank { "+" + current.number },
            )
        },
        onClick = { step = Step.KIND },
    )

    when (step) {
        Step.KIND -> KindDialog(
            current = current,
            onDismiss = { step = null },
            onNowhere = { step = null; onPick(null) },
            onApp = { step = Step.APP },
            onChat = { step = Step.CHAT },
        )
        Step.APP -> AppDialog(
            apps = apps,
            icons = icons,
            selected = (current as? Redirect.App)?.packageName,
            onDismiss = { step = null },
            onPick = { pkg -> step = null; onPick(Redirect.App(pkg)) },
        )
        Step.CHAT -> ChatDialog(
            current = current as? Redirect.WhatsAppChat,
            onDismiss = { step = null },
            onSave = { chat -> step = null; onPick(chat) },
        )
        null -> Unit
    }
}

private enum class Step { KIND, APP, CHAT }

@Composable
private fun KindDialog(
    current: Redirect?,
    onDismiss: () -> Unit,
    onNowhere: () -> Unit,
    onApp: () -> Unit,
    onChat: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.redirect_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.redirect_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    RadioRow(stringResource(R.string.redirect_nowhere), current == null, onNowhere)
                    RadioRow(stringResource(R.string.redirect_kind_app), current is Redirect.App, onApp)
                    RadioRow(stringResource(R.string.redirect_kind_chat), current is Redirect.WhatsAppChat, onChat)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel_change)) }
        },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AppDialog(
    apps: List<OtherApp>,
    icons: AppIcons,
    selected: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) {
        // The package name too: on a Catalan phone Play Books is "Play
        // Llibres", and someone typing "books" should still find it.
        val q = query.trim()
        apps.filter {
            q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.redirect_kind_app)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.redirect_search_apps)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp).selectableGroup()) {
                    items(shown, key = { it.packageName }) { app ->
                        val icon by produceState<ImageBitmap?>(null, app.packageName) {
                            value = icons.load(app.packageName)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(
                                    selected = app.packageName == selected,
                                    role = Role.RadioButton,
                                    onClick = { onPick(app.packageName) },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppAvatar(icon = icon, label = app.label, size = 40.dp)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (app.packageName == selected) RadioButton(selected = true, onClick = null)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel_change)) }
        },
    )
}

@Composable
private fun ChatDialog(
    current: Redirect.WhatsAppChat?,
    onDismiss: () -> Unit,
    onSave: (Redirect.WhatsAppChat) -> Unit,
) {
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var number by remember { mutableStateOf(current?.number?.let { "+$it" }.orEmpty()) }
    var tried by remember { mutableStateOf(false) }
    val normalised = Redirect.normaliseNumber(number)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.redirect_kind_chat)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.redirect_chat_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("|", "") },
                    singleLine = true,
                    label = { Text(stringResource(R.string.redirect_chat_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.redirect_chat_number)) },
                    placeholder = { Text("+34 612 34 56 78") },
                    isError = tried && normalised == null,
                    supportingText = if (tried && normalised == null) {
                        { Text(stringResource(R.string.redirect_chat_number_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (normalised != null) onSave(Redirect.WhatsAppChat(normalised, name.trim()))
            }) { Text(stringResource(R.string.redirect_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel_change)) }
        },
    )
}
