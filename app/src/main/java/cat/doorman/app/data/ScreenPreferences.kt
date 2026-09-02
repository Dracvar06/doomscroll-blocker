package cat.doorman.app.data

import cat.doorman.app.limits.Limits

/**
 * Works out how strictly each thing is actually held, given what the user has
 * decided and what the shipped rules default to.
 *
 * The naive version -- "has the user configured anything? then use their set" --
 * breaks on every update that adds a screen. Someone who once flicked a single
 * switch would never receive Instagram support at all: their stored set has no
 * Instagram ids in it, and the new defaults are ignored because they count as
 * configured.
 *
 * So a choice is recorded per screen. A screen the user has ruled on obeys them
 * forever; a screen they have never seen takes the default it ships with. New
 * rules then arrive switched on as intended, and no existing decision is undone.
 *
 * Pure, so the upgrade behaviour is testable without a device.
 */
object ScreenPreferences {

    /**
     * The limits in force on every known screen.
     *
     * Three sources, in order of authority: a mode the user has chosen, an
     * older on/off decision from before allowances existed, and the default the
     * screen ships with.
     *
     * The middle one is the upgrade path. An install that predates this change
     * has no modes at all, only the set of screens it had switched on, and
     * those have to keep meaning "blocked" -- an upgrade that silently unheld
     * every screen someone had chosen would be a betrayal of the whole app.
     */
    fun effectiveLimits(
        screenDefaults: Map<String, Boolean>,
        decided: Set<String>,
        enabled: Set<String>,
        stored: Map<String, Limits>,
    ): Map<String, Limits> = screenDefaults.mapValues { (id, shippedDefault) ->
        stored[id]
            ?: when {
                id in decided -> if (id in enabled) Limits.BLOCKED else Limits.OFF
                shippedDefault -> Limits.BLOCKED
                else -> Limits.OFF
            }
    }

    /**
     * Which screens the rules should be matched against at all.
     *
     * A screen with an allowance or a schedule still has to be recognised: the
     * allowance is spent by looking at it and the schedule has to know when it
     * is in front. Only a screen with no limits at all leaves the running.
     */
    fun activeScreenIds(limits: Map<String, Limits>): Set<String> =
        limits.filterValues { !it.isOff }.keys
}
