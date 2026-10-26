package cat.doorman.app.data

/**
 * Somewhere better to be, opened when a block holds you.
 *
 * A block on its own only says no. Most of the time what follows is staring at
 * it, or going round it; being handed something you chose for that moment --
 * a book, a chat with your grandmother -- is the part that actually changes
 * the next ten minutes.
 *
 * Kept as a plain string in preferences, so it survives an update without a
 * migration and a corrupt value reads as "nowhere" rather than as a crash.
 */
sealed interface Redirect {

    /** Another app on the phone, opened the way its launcher icon opens it. */
    data class App(val packageName: String) : Redirect

    /**
     * A WhatsApp conversation, by phone number. [number] is digits only, with
     * the country code and without a plus: the form wa.me links take. [name]
     * is what the person called them, for the button on the block.
     */
    data class WhatsAppChat(val number: String, val name: String) : Redirect

    companion object {
        private const val APP = "app:"
        private const val WHATSAPP = "wa:"

        fun encode(redirect: Redirect?): String? = when (redirect) {
            null -> null
            is App -> APP + redirect.packageName
            is WhatsAppChat -> WHATSAPP + redirect.number + "|" + redirect.name
        }

        fun decode(raw: String?): Redirect? {
            if (raw.isNullOrBlank()) return null
            return when {
                raw.startsWith(APP) -> raw.removePrefix(APP).takeIf { it.isNotBlank() }?.let(::App)
                raw.startsWith(WHATSAPP) -> {
                    val body = raw.removePrefix(WHATSAPP)
                    val number = normaliseNumber(body.substringBefore("|")) ?: return null
                    WhatsAppChat(number, body.substringAfter("|", "").trim())
                }
                else -> null
            }
        }

        /**
         * A phone number as somebody types it -- "+34 612 34 56 78",
         * "0034-612345678" -- reduced to the digits a wa.me link wants, or null
         * if it cannot be a full international number.
         *
         * A leading 00 is the international prefix written out and is dropped.
         * Fewer than 8 digits cannot include a country code, more than 15 is
         * longer than any number the standard allows; either way the link
         * would open a chat with nobody, so it is refused here instead.
         */
        fun normaliseNumber(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.any { !it.isDigit() && it !in " +-(). " }) return null
            var digits = trimmed.filter { it.isDigit() }
            if (!trimmed.startsWith("+") && digits.startsWith("00")) digits = digits.drop(2)
            return digits.takeIf { it.length in 8..15 && !it.startsWith("0") }
        }
    }
}
