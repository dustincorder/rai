package com.dustincorder.rai.domain.tools

import kotlinx.serialization.Serializable

/**
 * Classifies what an action does to the local or remote state.
 *
 * Security and confirmation policies evaluate this classification alongside user settings
 * and caller trust, rather than relying on execution mechanisms (e.g. Intents).
 */
@Serializable
enum class ToolEffect {
    /** Read-only queries with no local or external state mutation (e.g. Battery, Time, Weather, Web Search). */
    ReadOnly,

    /** Reversible or low-risk local device state changes (e.g. Start Timer, Toggle Flashlight, Copy to Clipboard). */
    LocalReversible,

    /** External side-effects or outbound data transmission (e.g. Pre-fill SMS, Open Telegram Chat, Write to MCP resource). */
    ExternalWrite,

    /** Irreversible local or remote modifications (e.g. Delete Calendar Event, Overwrite Data, Delete Remote Record). */
    Destructive,

    /** Accesses private user data requiring explicit user consent (e.g. Read Device Contacts, Read Clipboard). */
    SensitiveDataAccess,
}

/**
 * Execution mechanism describing how the action is physically invoked.
 */
@Serializable
sealed interface ExecutionKind {
    /** Direct Android SDK / system service invocation (e.g. BatteryManager, AudioManager). */
    @Serializable
    data object LocalApi : ExecutionKind

    /** System Activity launch via Intent (e.g. AlarmClock, CalendarContract, ACTION_SENDTO). */
    @Serializable
    data object AndroidIntent : ExecutionKind

    /** Network request to a remote/local stateless MCP server over Streamable HTTP. */
    @Serializable
    data class RemoteMcp(val serverId: String) : ExecutionKind

    /** Built-in direct HTTPS API call (e.g. Open-Meteo weather, search provider). */
    @Serializable
    data object Network : ExecutionKind
}
