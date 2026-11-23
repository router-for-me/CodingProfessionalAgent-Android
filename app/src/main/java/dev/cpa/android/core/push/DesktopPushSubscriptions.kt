package dev.cpa.android.core.push

import dev.cpa.android.core.network.RemoteRpcFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

sealed interface PushTarget {
    data object Off : PushTarget
    data object Pending : PushTarget
    class Registered(val fid: String) : PushTarget
}

enum class DesktopPushStatus { Registered, Unsupported, Retrying }

/** Journal before sending: a lost reply can still leave a subscription on the desktop. */
interface PushJournal {
    val clientId: String
    fun authorize(connection: String): String
    fun retire(connection: String)
    suspend fun persistRetirement(connection: String)
    fun retired(connection: String): Set<String>
    fun cleared(connection: String, route: String)
    fun forget(connection: String)
}

typealias PushRpc = suspend (String, List<JsonElement>) -> JsonElement

class DesktopPushSubscriptions(private val journal: PushJournal) {
    val target = MutableStateFlow<PushTarget>(PushTarget.Pending)
    private val states = MutableStateFlow<Map<String, DesktopPushStatus>>(emptyMap())
    val status = states.asStateFlow()

    suspend fun synchronize(connection: String, call: PushRpc) {
        try {
            target.collectLatest { desired ->
                states.update { it - connection }
                if (desired == PushTarget.Pending) return@collectLatest
                var backoff = 2_000L
                while (true) {
                    try {
                        if (desired == PushTarget.Off) journal.retire(connection)
                        unregister(connection, call)
                        if (desired is PushTarget.Registered) {
                            val route = journal.authorize(connection)
                            val args = buildJsonObject {
                                put("clientId", journal.clientId)
                                put("connectionId", route)
                                put("platform", "android")
                                put("provider", "fcm")
                                put("deviceToken", desired.fid)
                                put("enabled", true)
                            }
                            checkAck(call("notification:registerClient", listOf(args)))
                            states.update { it + (connection to DesktopPushStatus.Registered) }
                        }
                        return@collectLatest
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        val message = error.message.orEmpty()
                        if (error is RemoteRpcFailure && listOf("unknown rpc method", "only \"ios\" is supported",
                                "invalid environment", "invalid devicetoken").any { message.contains(it, ignoreCase = true) }) {
                            states.update { it + (connection to DesktopPushStatus.Unsupported) }
                            return@collectLatest
                        }
                    }
                    states.update { it + (connection to DesktopPushStatus.Retrying) }
                    delay(backoff)
                    backoff = minOf(backoff * 2, 60_000L)
                }
            }
        } finally {
            states.update { it - connection }
        }
    }

    suspend fun persistRetirement(connection: String) = journal.persistRetirement(connection)
    fun forget(connection: String) = journal.forget(connection)

    suspend fun unregister(connection: String, call: PushRpc) {
        for (route in journal.retired(connection)) {
            val args = buildJsonObject {
                put("clientId", journal.clientId)
                put("connectionId", route)
            }
            checkAck(call("notification:unregisterClient", listOf(args)))
            journal.cleared(connection, route)
        }
    }

    private fun checkAck(reply: JsonElement) {
        check((reply as? JsonObject)?.get("ok") == JsonPrimitive(true)) { "Invalid notification registration reply" }
    }
}
