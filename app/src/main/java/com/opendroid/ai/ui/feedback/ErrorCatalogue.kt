package com.opendroid.ai.ui.feedback

/**
 * Stage 3 of v3.10.0: one message catalogue for error states.
 *
 * Every error is a (category -> message -> one action) triple so the UI can render it with an
 * icon and text rather than colour alone, and so a translation can be supplied per locale.
 * Technical detail is kept separate and is shown only in a collapsed section.
 */
enum class ErrorCategory {
    PLUGIN,
    REMOTE_CONTROL,
    PROVIDER,
    RUNTIME,
    GITHUB,
    IMPORT,
    UNKNOWN,
}

/** One user-facing action offered with an error. [actionId] maps to a handler in the UI. */
data class ErrorAction(val actionId: String, val labelKey: String)

/**
 * A catalogued error. [messageKey] and [titleKey] are translation keys; [technicalDetail] is the
 * raw stderr or exception text and is never shown by default. [technicalDetail] must never carry
 * a secret - callers mask before constructing.
 */
data class CataloguedError(
    val category: ErrorCategory,
    val titleKey: String,
    val messageKey: String,
    val action: ErrorAction?,
    val technicalDetail: String? = null,
)

object ErrorCatalogue {

    fun of(
        category: ErrorCategory,
        titleKey: String,
        messageKey: String,
        actionId: String? = null,
        actionLabelKey: String? = null,
        technicalDetail: String? = null,
    ): CataloguedError = CataloguedError(
        category = category,
        titleKey = titleKey,
        messageKey = messageKey,
        action = if (actionId != null && actionLabelKey != null) ErrorAction(actionId, actionLabelKey) else null,
        technicalDetail = technicalDetail,
    )

    /** The common cases, each with exactly one action. */
    val pluginNotApproved = of(
        ErrorCategory.PLUGIN,
        "error_plugin_title",
        "error_plugin_not_approved",
        "open_plugin_settings",
        "action_open_settings",
    )

    val remoteNotPaired = of(
        ErrorCategory.REMOTE_CONTROL,
        "error_remote_title",
        "error_remote_not_paired",
        "open_pairing",
        "action_pair_device",
    )

    val remoteKillSwitch = of(
        ErrorCategory.REMOTE_CONTROL,
        "error_remote_title",
        "error_remote_kill_switch",
        "open_kill_switch",
        "action_open_kill_switch",
    )

    val providerProtocolMismatch = of(
        ErrorCategory.PROVIDER,
        "error_provider_title",
        "error_provider_protocol_mismatch",
        "open_provider_settings",
        "action_open_settings",
    )
}
