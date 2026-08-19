package cat.doorman.app.data

/**
 * Works out which screens are actually blocked, given what the user has decided
 * and what the shipped rules default to.
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

    fun effectiveEnabled(
        screenDefaults: Map<String, Boolean>,
        decided: Set<String>,
        enabled: Set<String>,
    ): Set<String> = screenDefaults
        .filter { (id, shippedDefault) -> if (id in decided) id in enabled else shippedDefault }
        .keys
}
