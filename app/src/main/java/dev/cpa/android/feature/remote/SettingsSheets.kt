package dev.cpa.android.feature.remote

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.core.localization.AppLanguage
import dev.cpa.android.core.network.HttpFailure
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryPhase
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryState
import dev.cpa.android.core.network.tunnel.TunnelProtocol
import dev.cpa.android.core.storage.ConnectionIcon
import dev.cpa.android.core.storage.ConnectionMode
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt

/** Memory-only input: never serialize passwords or API keys into saved state. */
data class TunnelConnectionDraft(val id: String?, val name: String, val address: String, val apiKey: String?,
    val serverId: String, val serverName: String, val password: String?, val allowCleartext: Boolean) {
    override fun toString() = "TunnelConnectionDraft(REDACTED)"
}

internal data class ConnectionEditorTarget(val connection: ConnectionSummary?, val tunnel: Boolean = connection?.mode == ConnectionMode.Tunnel)

@Composable
private fun connectionStatusText(connection: ConnectionSummary): String = when {
    !connection.enabled -> stringResource(R.string.ui_connection_status_disconnected)
    connection.phase == ConnectionPhase.Connected -> stringResource(R.string.ui_connection_status_connected)
    connection.phase == ConnectionPhase.Connecting -> stringResource(R.string.ui_connection_status_connecting)
    connection.phase == ConnectionPhase.AuthenticationRequired -> stringResource(R.string.password_expired)
    connection.phase == ConnectionPhase.Failed -> connection.error?.let { repositoryProblem(it) } ?: stringResource(R.string.connection_failed)
    else -> stringResource(R.string.ui_connection_status_disconnected)
}

/** Settings sheet: desktop connections plus composer / behavior / language preferences. */
@Composable
internal fun SettingsSheet(vm: RemoteHomeViewModel, connections: List<ConnectionSummary>, icons: Map<String, String>,
    onDismiss: () -> Unit, onAdd: () -> Unit, onEdit: (ConnectionSummary) -> Unit) {
    val c = Cpa.colors
    val operation by vm.operation.collectAsStateWithLifecycle()
    val collapse by vm.collapseKeyboardAfterSend.collectAsStateWithLifecycle()
    val context by vm.showContextUsage.collectAsStateWithLifecycle()
    val wrap by vm.wrapCode.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConnectionSummary?>(null) }
    CpaSheet(onDismiss, grouped = true) {
        SheetTopBar(stringResource(R.string.ui_settings_title),
            leading = { GlassTextButton(stringResource(if (editing) R.string.ui_common_done else R.string.ui_common_edit), { editing = !editing },
                emphasized = editing, modifier = Modifier.testTag("settings-edit")) },
            trailing = { GlassIconButton(CpaIcons.Close, stringResource(R.string.ui_common_close), onDismiss, modifier = Modifier.testTag("settings-close")) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp)) {
            FormSectionHeader(stringResource(R.string.ui_settings_connections), prominent = true) {
                Text(stringResource(R.string.ui_settings_disconnect_all), Modifier.pressable(connections.any { it.enabled } && !operation.pending, onClick = vm::disconnectAll)
                    .testTag("settings-disconnect-all"), style = cpaText(15), color = if (connections.any { it.enabled }) c.blue else c.tertiaryLabel)
            }
            FormGroup {
                ReorderableConnections(connections, icons, editing, operation.pending, onEdit, onToggle = { connection, on ->
                    if (on) vm.connect(connection.id) else vm.disconnect(connection.id)
                }, onDelete = { deleting = it }, onReorder = vm::reorder)
                FormRow(onClick = onAdd, enabled = !operation.pending, modifier = Modifier.testTag("settings-add-connection")) {
                    Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) { CpaIcon(CpaIcons.Plus, null, size = 26.dp, tint = c.blue) }
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.ui_settings_add_connection), style = cpaText(17), color = c.blue)
                }
            }
            TaskPushSettings(connections)
            FormSectionHeader(stringResource(R.string.ui_settings_composer), prominent = true)
            FormGroup {
                FormToggleRow(stringResource(R.string.ui_settings_collapse_keyboard_after_send), collapse, vm::setCollapseKeyboard)
                FormDivider()
                FormToggleRow(stringResource(R.string.ui_settings_show_context_usage), context, vm::setShowContextUsage)
                FormDivider()
                FollowUpSettingRow()
            }
            FormSectionHeader(stringResource(R.string.ui_settings_behavior), prominent = true)
            FormGroup { FormToggleRow(stringResource(R.string.ui_settings_wrap_diff_lines), wrap, vm::setWrapCode) }
            FormSectionHeader(stringResource(R.string.ui_settings_general), prominent = true)
            FormGroup { LanguageRow() }
        }
    }
    deleting?.let { connection ->
        CpaAlert(stringResource(R.string.ui_x_delete_connection_named, connection.displayName()), stringResource(R.string.remove_connection_hint), listOf(
            DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { deleting = null },
            DialogAction(stringResource(R.string.ui_x_delete), destructive = true) { deleting = null; vm.remove(connection.id) }), onDismiss = { deleting = null })
    }
}

@Composable
internal fun FollowUpSettingRow() {
    FormValueRow(stringResource(R.string.ui_settings_follow_up_mode), stringResource(R.string.ui_settings_follow_up_desktop))
}

@Composable
private fun ReorderableConnections(connections: List<ConnectionSummary>, icons: Map<String, String>, editing: Boolean, busy: Boolean,
    onEdit: (ConnectionSummary) -> Unit, onToggle: (ConnectionSummary, Boolean) -> Unit, onDelete: (ConnectionSummary) -> Unit,
    onReorder: (List<String>) -> Unit) {
    val c = Cpa.colors
    val rowHeight = with(LocalDensity.current) { 68.dp.toPx() }
    var order by remember(connections.map { it.id }) { mutableStateOf(connections.map { it.id }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    order.forEachIndexed { index, id ->
        val connection = connections.find { it.id == id } ?: return@forEachIndexed
        key(id) {
            val lift by animateFloatAsState(if (dragging == id) 1.03f else 1f, label = "lift")
            Box(Modifier.zIndexIf(dragging == id).graphicsLayer { translationY = if (dragging == id) dragOffset else 0f; scaleX = lift; scaleY = lift }
                .background(c.groupedCard)) {
                Column {
                    FormRow(onClick = if (editing) null else ({ onEdit(connection) }), minHeight = 68.dp,
                        modifier = Modifier.testTag(homeKey("connection-row", connection.id))) {
                        if (editing) {
                            Box(Modifier.size(28.dp).pressable(!busy, label = stringResource(R.string.ui_x_delete)) { onDelete(connection) }
                                .testTag(homeKey("delete-connection", connection.id)), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(22.dp).background(c.red, CircleShape), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(10.dp, 2.dp).background(androidx.compose.ui.graphics.Color.White))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Box(Modifier.width(34.dp), contentAlignment = Alignment.Center) {
                            CpaIcon(ConnectionIcon.of(icons[connection.id]).vector, null, size = 27.dp, tint = c.secondaryLabel)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(connection.displayName(), style = cpaText(17), color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(statusColor(connection), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(connectionStatusText(connection), style = cpaText(15), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (editing) Box(Modifier.size(40.dp).semantics { contentDescription = "reorder" }.pointerInput(order) {
                            detectVerticalDragGestures(onDragStart = { dragging = id; dragOffset = 0f },
                                onDragEnd = { dragging = null; dragOffset = 0f; if (order != connections.map { it.id }) onReorder(order) },
                                onDragCancel = { dragging = null; dragOffset = 0f }) { change, amount ->
                                change.consume()
                                dragOffset += amount
                                val current = order.indexOf(id)
                                val target = (current + (dragOffset / rowHeight).roundToInt()).coerceIn(0, order.lastIndex)
                                if (target != current) {
                                    order = order.toMutableList().apply { removeAt(current); add(target, id) }
                                    dragOffset -= (target - current) * rowHeight
                                }
                            }
                        }, contentAlignment = Alignment.Center) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { Box(Modifier.size(20.dp, 1.5.dp).background(c.gray)) } }
                        }
                        else CpaSwitch(connection.enabled, { onToggle(connection, it) }, enabled = !busy, label = connection.displayName(),
                            modifier = Modifier.testTag(homeKey("connection-toggle", connection.id)))
                    }
                    FormDivider(start = 68.dp)
                }
            }
        }
    }
}

private fun Modifier.zIndexIf(on: Boolean) = if (on) this.then(Modifier.graphicsLayer { shadowElevation = 8f }) else this

@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val manager = context.getSystemService(LocaleManager::class.java)
    val current = remember(configuration) {
        val locales = manager.applicationLocales
        if (locales.isEmpty) AppLanguage.System else AppLanguage.resolveSystem((0 until locales.size()).map { locales[it].toLanguageTag() })
    }
    var failed by remember { mutableStateOf(false) }
    val options = listOf(MenuOption(AppLanguage.System, stringResource(R.string.ui_settings_language_system)),
        MenuOption(AppLanguage.English, stringResource(R.string.ui_x_language_english)),
        MenuOption(AppLanguage.SimplifiedChinese, stringResource(R.string.ui_x_language_chinese)))
    FormMenuRow(stringResource(R.string.ui_settings_language), options.first { it.value == current }.title, options, current, { language ->
        failed = false
        runCatching { manager.applicationLocales = LocaleList.forLanguageTags(language.tag) }.onFailure { failed = true }
    }, modifier = Modifier.testTag("settings-language"))
    if (failed) FormFooter(stringResource(R.string.language_failed), color = Cpa.colors.red)
}

@Composable
internal fun ConnectionIconPicker(selected: ConnectionIcon, onSelect: (ConnectionIcon) -> Unit) {
    val c = Cpa.colors
    FormRow {
        Text(stringResource(R.string.ui_connection_icon), style = cpaText(17), color = c.label)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ConnectionIcon.entries.forEach { icon ->
                val label = stringResource(when (icon) {
                    ConnectionIcon.Code -> R.string.ui_connection_icon_code
                    ConnectionIcon.Terminal -> R.string.ui_connection_icon_terminal
                    ConnectionIcon.Laptop -> R.string.ui_connection_icon_laptop
                    ConnectionIcon.Display -> R.string.ui_connection_icon_display
                })
                Box(Modifier.size(36.dp).pressable(pressedAlpha = .6f, label = label) { onSelect(icon) }
                    .semantics { contentDescription = label; this.selected = icon == selected }, contentAlignment = Alignment.Center) {
                    CpaIcon(icon.vector, null, size = 26.dp, tint = if (icon == selected) c.label else c.gray3)
                }
            }
        }
    }
}

/** Host + port (or a pasted http(s) address) → validated origin; null when incomplete. */
internal data class DirectAddress(val origin: String?, val insecure: Boolean, val error: Int?)
internal fun parseDirectAddress(hostInput: String, portInput: String): DirectAddress {
    val trimmed = hostInput.trim()
    if (trimmed.isEmpty()) return DirectAddress(null, true, null)
    var scheme = "http"
    var rest = trimmed
    val separator = trimmed.indexOf("://")
    if (separator >= 0) {
        scheme = trimmed.substring(0, separator).lowercase()
        if (scheme == "ws") scheme = "http"
        if (scheme == "wss") scheme = "https"
        if (scheme != "http" && scheme != "https") return DirectAddress(null, true, R.string.desktop_scheme_hint)
        rest = trimmed.substring(separator + 3)
    }
    rest = rest.trimEnd('/')
    if (rest.isEmpty() || rest.contains('/') || rest.contains('?') || rest.contains('#') || rest.contains('@')) return DirectAddress(null, scheme == "http", R.string.ui_connection_invalid_host)
    var host: String
    var port: Int? = null
    if (rest.startsWith("[")) {
        val end = rest.indexOf(']')
        if (end < 0) return DirectAddress(null, scheme == "http", R.string.ui_connection_invalid_host)
        host = rest.substring(0, end + 1)
        rest.substring(end + 1).removePrefix(":").takeIf(String::isNotEmpty)?.let { port = it.toIntOrNull() ?: return DirectAddress(null, scheme == "http", R.string.ui_connection_invalid_host) }
    } else if (rest.count { it == ':' } > 1) {
        host = "[$rest]" // Bare IPv6 literal.
    } else {
        host = rest.substringBefore(':')
        if (':' in rest) port = rest.substringAfter(':').toIntOrNull() ?: return DirectAddress(null, scheme == "http", R.string.ui_connection_invalid_host)
    }
    val resolvedPort = port ?: portInput.trim().toIntOrNull()
    if (host.isBlank() || resolvedPort == null || resolvedPort !in 1..65535) return DirectAddress(null, scheme == "http", if (host.isBlank()) R.string.ui_connection_invalid_host else null)
    return DirectAddress("$scheme://$host:$resolvedPort", scheme == "http", null)
}

/** Editor for direct desktops (add / edit) and saved tunnel connections; adding a tunnel runs the discovery flow. */
@Composable
internal fun ConnectionEditorSheet(vm: RemoteHomeViewModel, target: ConnectionEditorTarget, connections: List<ConnectionSummary>,
    icons: Map<String, String>, operation: HomeOperation, onDismiss: () -> Unit, onSaved: () -> Unit = onDismiss) {
    if (target.tunnel && target.connection == null) { TunnelConnectionSheet(vm, connections, operation, onDismiss, onSaved); return }
    val c = Cpa.colors
    val original = target.connection
    val tunnel = target.tunnel
    var icon by remember { mutableStateOf(ConnectionIcon.of(original?.id?.let { icons[it] })) }
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    val parsedOriginal = remember(original) { original?.takeIf { !tunnel }?.address?.let { runCatching { java.net.URI(it) }.getOrNull() } }
    var host by remember { mutableStateOf(parsedOriginal?.let { uri -> (if (uri.scheme == "https") "https://" else "") + uri.host.orEmpty() }.orEmpty()) }
    var port by remember { mutableStateOf(parsedOriginal?.port?.takeIf { it > 0 }?.toString() ?: "18080") }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    var gateway by remember { mutableStateOf(if (tunnel) original?.address.orEmpty() else "") }
    var apiKey by remember { mutableStateOf("") }
    var cleartext by remember { mutableStateOf(original?.allowCleartext == true) }
    var deleting by remember { mutableStateOf(false) }
    val direct = parseDirectAddress(host, port)
    val tunnelInsecure = gateway.trim().startsWith("http://", true) || gateway.trim().startsWith("ws://", true)
    val tunnelValid = runCatching { TunnelProtocol.endpoint(gateway.trim()) }.isSuccess
    val insecure = if (tunnel) tunnelInsecure else direct.insecure && direct.origin != null
    val canSave = !operation.pending && (if (tunnel) tunnelValid else direct.origin != null) && (!insecure || cleartext)
    fun save() {
        val secret = if (clearPassword) "" else password.takeIf(String::isNotEmpty)
        if (tunnel) {
            val connection = original ?: return
            vm.saveTunnel(TunnelConnectionDraft(connection.id, name.trim(), gateway.trim(), apiKey.trim().takeIf(String::isNotEmpty),
                connection.tunnelServerId, connection.tunnelServerName, secret, insecure && cleartext)) { onSaved() }
            vm.setIcon(connection.id, icon)
        } else vm.saveWithIcon(original?.id, name.trim(), direct.origin ?: return, secret, insecure && cleartext, icon) { onSaved() }
    }
    CpaSheet(onDismiss, grouped = true, dismissible = !operation.pending) {
        SheetTopBar(stringResource(if (original == null) R.string.ui_connection_add_title else R.string.ui_connection_edit_title),
            leading = { GlassTextButton(stringResource(R.string.ui_common_cancel), onDismiss, enabled = !operation.pending) },
            trailing = { if (operation.pending) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner() }
                else GlassTextButton(stringResource(R.string.ui_common_save), ::save, enabled = canSave, emphasized = true, modifier = Modifier.testTag("connection-save")) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(top = 12.dp, bottom = 32.dp)) {
            FormGroup {
                ConnectionIconPicker(icon) { icon = it }
                FormDivider()
                if (tunnel) {
                    val serverLabel = original?.tunnelServerName?.ifBlank { original.tunnelServerId }.orEmpty()
                    FormFieldRow(stringResource(R.string.ui_connection_display_name), name, { name = it }, serverLabel.ifBlank { stringResource(R.string.ui_common_optional) })
                    FormDivider()
                    FormFieldRow("CLIProxyAPI", gateway, { gateway = it; cleartext = false }, "https://example.com:8317", keyboardType = KeyboardType.Uri)
                    FormDivider()
                    FormFieldRow("API Key", apiKey, { apiKey = it }, stringResource(R.string.ui_x_api_key_keep), keyboardType = KeyboardType.Password, secret = true)
                    FormDivider()
                    FormValueRow(stringResource(R.string.ui_connection_remote_server), serverLabel)
                } else {
                    FormFieldRow(stringResource(R.string.ui_connection_display_name), name, { name = it }, stringResource(R.string.ui_common_optional),
                        fieldModifier = Modifier.testTag("connection-name"))
                    FormDivider()
                    FormFieldRow(stringResource(R.string.ui_connection_host), host, { host = it }, "192.168.1.10", keyboardType = KeyboardType.Uri,
                        fieldModifier = Modifier.testTag("connection-host"))
                    FormDivider()
                    FormFieldRow(stringResource(R.string.ui_connection_port), port, { port = it.filter(Char::isDigit).take(5) }, "18080", keyboardType = KeyboardType.Number,
                        fieldModifier = Modifier.testTag("connection-port"))
                }
                FormDivider()
                FormFieldRow(stringResource(R.string.ui_connection_password), password, { password = it; clearPassword = false },
                    stringResource(if (original == null) R.string.ui_connection_password_placeholder else R.string.ui_x_password_keep),
                    keyboardType = KeyboardType.Password, secret = true, fieldModifier = Modifier.testTag("connection-password"))
                if (original != null) { FormDivider(); FormToggleRow(stringResource(R.string.ui_x_clear_password), clearPassword, { clearPassword = it; if (it) password = "" }) }
                if (insecure) { FormDivider(); FormToggleRow(stringResource(R.string.ui_x_allow_http), cleartext, { cleartext = it }, modifier = Modifier.testTag("connection-cleartext")) }
            }
            if (!tunnel) direct.error?.let { FormFooter(stringResource(it), color = c.red) }
            if (tunnel && gateway.isNotBlank() && !tunnelValid) FormFooter(stringResource(R.string.gateway_scheme_hint), color = c.red)
            operation.error?.let { FormFooter(stringResource(it), color = c.red) }
            if (insecure) FormFooter(stringResource(R.string.cleartext_warning), color = c.orange)
            FormFooter(stringResource(if (tunnel) R.string.ui_connection_tunnel_hint else R.string.ui_connection_direct_hint))
            if (original != null) {
                Spacer(Modifier.height(24.dp))
                FormGroup { FormButtonRow(stringResource(R.string.ui_connection_delete), { deleting = true }, destructive = true, enabled = !operation.pending) }
            }
        }
    }
    if (deleting && original != null) CpaAlert(stringResource(R.string.ui_x_delete_connection_named, original.displayName()), stringResource(R.string.remove_connection_hint), listOf(
        DialogAction(stringResource(R.string.ui_common_cancel), cancel = true) { deleting = false },
        DialogAction(stringResource(R.string.ui_x_delete), destructive = true) { deleting = false; vm.remove(original.id); onSaved() }), onDismiss = { deleting = false })
}

/**
 * Two-step tunnel sheet: register as a CLIProxyAPI tunnel client, list online CPA desktops, then name the
 * chosen server. Discovery restarts when the sheet returns to the foreground and stops when it closes.
 */
@Composable
private fun TunnelConnectionSheet(vm: RemoteHomeViewModel, connections: List<ConnectionSummary>, operation: HomeOperation, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val c = Cpa.colors
    var gateway by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var cleartext by remember { mutableStateOf(false) }
    var recentKey by remember { mutableStateOf(false) }
    var discovery by remember { mutableStateOf<TunnelDiscoveryState?>(null) }
    var discoveryError by remember { mutableStateOf<Int?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var wanted by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TunnelProtocol.Server?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { vm.recentTunnel() }.getOrNull()?.let { recent ->
            if (gateway.isEmpty()) { gateway = recent.baseUrl; recentKey = recent.hasApiKey }
        }
    }
    val insecure = gateway.trim().startsWith("http://", true) || gateway.trim().startsWith("ws://", true)
    val validGateway = runCatching { TunnelProtocol.endpoint(gateway.trim()) }.isSuccess
    val keyAvailable = apiKey.isNotBlank() || recentKey
    val current = wanted
    val searching = current && discovery?.phase == TunnelDiscoveryPhase.Connecting && job?.isActive == true
    fun stop() { job?.cancel(); job = null }
    fun invalidateSearch() { stop(); wanted = false; discoveryError = null; selected = null }
    fun search() {
        if (!validGateway || !keyAvailable || (insecure && !cleartext)) return
        stop(); wanted = true; discoveryError = null; discovery = TunnelDiscoveryState(TunnelDiscoveryPhase.Connecting)
        val key = apiKey.trim().takeIf(String::isNotEmpty)
        val address = gateway.trim()
        val allowCleartext = insecure && cleartext
        job = scope.launch {
            try { vm.watchTunnel(null, address, key, allowCleartext).collect { discovery = it } }
            catch (_: TimeoutCancellationException) { discoveryError = R.string.discovery_timeout }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { discoveryError = if (e is HttpFailure && e.statusCode in listOf(401, 403)) R.string.gateway_auth_failed else R.string.discovery_failed }
        }
    }
    val resumeSearch by rememberUpdatedState { search() }
    LifecycleStartEffect(Unit) {
        if (wanted && job == null) resumeSearch()
        onStopOrDispose { stop() }
    }
    val added = connections.filter { it.mode == ConnectionMode.Tunnel && runCatching {
        TunnelProtocol.endpoint(it.address).webSocketUrl == TunnelProtocol.endpoint(gateway.trim()).webSocketUrl }.getOrDefault(false) }.map { it.tunnelServerId }.toSet()
    CpaSheet({ stop(); onDismiss() }, grouped = true, dismissible = !operation.pending) {
        val server = selected
        if (server != null) {
            TunnelServerDetail(vm, server, gateway.trim(), apiKey.trim(), insecure && cleartext, operation, onBack = { selected = null }) { stop(); onSaved() }
            return@CpaSheet
        }
        SheetTopBar("CLIProxyAPI Tunnel", leading = { GlassTextButton(stringResource(R.string.ui_common_cancel), { stop(); onDismiss() }) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(bottom = 32.dp)) {
            FormSectionHeader(stringResource(R.string.ui_tunnel_service_section))
            FormGroup {
                FormFieldRow(stringResource(R.string.ui_tunnel_url_label), gateway, { invalidateSearch(); gateway = it; cleartext = false; recentKey = false },
                    "https://example.com:8317", keyboardType = KeyboardType.Uri, fieldModifier = Modifier.testTag("tunnel-url"))
                FormDivider()
                FormFieldRow("API Key", apiKey, { invalidateSearch(); apiKey = it }, if (recentKey) stringResource(R.string.ui_x_api_key_keep) else stringResource(R.string.ui_common_required),
                    keyboardType = KeyboardType.Password, secret = true, fieldModifier = Modifier.testTag("tunnel-key"))
                if (insecure) {
                    FormDivider()
                    FormToggleRow(stringResource(R.string.ui_x_allow_http), cleartext, {
                        invalidateSearch(); discovery = null; cleartext = it
                    }, modifier = Modifier.testTag("tunnel-cleartext"))
                }
                FormDivider()
                FormRow(onClick = ::search, enabled = validGateway && keyAvailable && (!insecure || cleartext) && !searching,
                    modifier = Modifier.testTag("tunnel-search")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (searching) { Spinner(size = 16.dp); Spacer(Modifier.width(10.dp)) }
                        val enabled = validGateway && keyAvailable && (!insecure || cleartext) && !searching
                        Text(stringResource(when {
                            searching -> R.string.ui_connection_status_connecting
                            current -> R.string.ui_tunnel_search_again
                            discovery == null -> R.string.ui_tunnel_connect_and_search
                            else -> R.string.ui_tunnel_switch_gateway_and_search
                        }), style = cpaText(17), color = if (enabled) c.blue else c.tertiaryLabel)
                    }
                }
            }
            if (gateway.isNotBlank() && !validGateway) FormFooter(stringResource(R.string.gateway_scheme_hint), color = c.red)
            discoveryError?.let { FormFooter(stringResource(it), color = c.red) }
            if (recentKey && apiKey.isEmpty()) FormFooter(stringResource(R.string.reuse_gateway_key))
            if (insecure) FormFooter(stringResource(R.string.cleartext_warning), color = c.orange)
            FormFooter(stringResource(R.string.ui_tunnel_gateway_hint))
            val state = discovery
            if (state != null) {
                FormSectionHeader(stringResource(R.string.ui_tunnel_online_servers_section)) {
                    if (current && state.phase == TunnelDiscoveryPhase.Connected) Row(Modifier.pressable(onClick = ::search), verticalAlignment = Alignment.CenterVertically) {
                        CpaIcon(CpaIcons.Refresh, null, size = 14.dp, tint = c.blue); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ui_common_refresh), style = cpaText(14), color = c.blue)
                    }
                }
                FormGroup {
                    if (!current) FormRow { Text(stringResource(R.string.ui_tunnel_config_changed_prompt), style = cpaText(15), color = c.secondaryLabel) }
                    else when (state.phase) {
                        TunnelDiscoveryPhase.Connecting -> FormRow { Spinner(size = 16.dp); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.ui_tunnel_registering), style = cpaText(17), color = c.secondaryLabel) }
                        TunnelDiscoveryPhase.Reconnecting -> FormRow { Spinner(size = 16.dp); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.ui_tunnel_reconnecting_attempt, state.reconnectAttempt), style = cpaText(17), color = c.secondaryLabel) }
                        TunnelDiscoveryPhase.Failed -> Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CpaIcon(CpaIcons.Warning, null, size = 18.dp, tint = c.red); Spacer(Modifier.width(8.dp))
                                Text(tunnelDiscoveryNotice(state) ?: state.error ?: stringResource(R.string.discovery_failed), style = cpaText(15), color = c.red)
                            }
                            Text(stringResource(R.string.ui_tunnel_retry_connect), Modifier.pressable(onClick = ::search), style = cpaText(15, FontWeight.Medium), color = c.blue)
                        }
                        TunnelDiscoveryPhase.Connected -> if (state.servers.isEmpty()) FormRow {
                            Text(stringResource(R.string.ui_tunnel_no_online_servers), style = cpaText(15), color = c.secondaryLabel)
                        } else state.servers.forEachIndexed { index, server ->
                            if (index > 0) FormDivider(start = 64.dp)
                            val already = server.uuid in added
                            FormRow(onClick = if (already) null else ({ selected = server }), modifier = Modifier.testTag("tunnel-server:${server.uuid}")) {
                                Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) { CpaIcon(CpaIcons.Display, null, size = 24.dp, tint = if (already) c.secondaryLabel else c.label) }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(server.name, style = cpaText(17), color = if (already) c.secondaryLabel else c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(server.uuid, style = cpaText(12), color = if (already) c.tertiaryLabel else c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (already) Text(stringResource(R.string.ui_tunnel_added), style = cpaText(14), color = c.secondaryLabel)
                                else CpaIcon(CpaIcons.ChevronRight, null, size = 14.dp, tint = c.tertiaryLabel)
                            }
                        }
                    }
                }
                if (current && state.phase != TunnelDiscoveryPhase.Failed) tunnelDiscoveryNotice(state)?.let { FormFooter(it, color = c.orange) }
            }
        }
    }
}

@Composable
private fun TunnelServerDetail(vm: RemoteHomeViewModel, server: TunnelProtocol.Server, gateway: String, apiKey: String, cleartext: Boolean,
    operation: HomeOperation, onBack: () -> Unit, onSaved: () -> Unit) {
    val c = Cpa.colors
    var icon by remember { mutableStateOf(ConnectionIcon.Display) }
    var name by remember { mutableStateOf(server.name) }
    var password by remember { mutableStateOf("") }
    androidx.activity.compose.BackHandler(onBack = onBack)
    SheetTopBar(stringResource(R.string.ui_tunnel_add_remote_server_title),
        leading = { GlassIconButton(CpaIcons.ChevronLeft, stringResource(R.string.ui_x_back), onBack) },
        trailing = { if (operation.pending) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner() }
            else GlassTextButton(stringResource(R.string.ui_common_save), {
                vm.saveTunnelWithIcon(TunnelConnectionDraft(null, name.trim(), gateway, apiKey.takeIf(String::isNotEmpty), server.uuid, server.name,
                    password.takeIf(String::isNotEmpty), cleartext), icon, onSaved)
            }, emphasized = true, modifier = Modifier.testTag("tunnel-save")) })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(top = 12.dp, bottom = 32.dp)) {
        FormGroup {
            ConnectionIconPicker(icon) { icon = it }
            FormDivider()
            FormFieldRow(stringResource(R.string.ui_connection_display_name), name, { name = it }, server.name)
            FormDivider()
            FormFieldRow(stringResource(R.string.ui_connection_password), password, { password = it }, stringResource(R.string.ui_connection_password_placeholder),
                keyboardType = KeyboardType.Password, secret = true)
        }
        operation.error?.let { FormFooter(stringResource(it), color = c.red) }
        FormFooter(stringResource(R.string.ui_tunnel_detail_password_hint))
        FormSectionHeader(stringResource(R.string.ui_tunnel_info_section))
        FormGroup {
            FormValueRow(stringResource(R.string.ui_connection_remote_server), server.name)
            FormDivider()
            FormValueRow("CLIProxyAPI", gateway, TextOverflow.MiddleEllipsis)
        }
    }
}
