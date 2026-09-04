package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences.getSharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Preference store for the external automation surface (fork-only, 保存復元 contract §2).
 *
 * ## What changed in v2 (白い熊, 2026-09-04)
 *
 * v1 shipped the app **closed**: [ENABLED] defaulted to false and every caller had to present
 * the shared secret 白い熊 had pasted out of this page. That is the wrong shape for where the
 * family is going — **a pasted secret cannot survive a wipe**, and the case the automation
 * family now exists to serve is 応用管理 restoring apps *and their data* onto a clean phone,
 * where nothing has been configured and nobody has pasted anything.
 *
 * So [ENABLED] now defaults to **true** and [REQUIRE_TOKEN] is a new, separate switch
 * defaulting to **false**. The token still exists, still regenerates, still never leaves the
 * phone — it is simply opt-in.
 *
 * ## Idempotent about the token — required, not a nicety
 *
 * **A token handed to an app that does not require one is IGNORED, never an error.** Tokens
 * live in task arguments and workspace variables that outlive the setting they were pasted
 * for; refusing them would turn "白い熊 turned a switch off" into "half the batch mysteriously
 * fails", which is exactly the friction the switch exists to remove.
 *
 * ## Device-local by design
 *
 * All three keys are in [app.simple.felicity.shiroikuma.SkBackup]'s never-export set, so no
 * automation setting — least of all the token — travels in an export zip or reaches another
 * phone. Unlike most sister apps our automation state lives in the app's ordinary preferences
 * rather than a device-local prefs file, so that exclusion is the only thing keeping it here.
 */
object AutomationPreferences {

    const val ENABLED = "sk_automation_enabled"

    /** Whether a caller must also present [TOKEN]. New in v2; **default off**. */
    const val REQUIRE_TOKEN = "sk_automation_require_token"

    const val TOKEN = "sk_automation_token"

    /**
     * The two refusals, reported distinctly on purpose: they debug completely differently,
     * and every app in the family answers them with these exact strings.
     */
    const val ERROR_DISABLED = "ERROR:automation disabled"
    const val ERROR_BAD_TOKEN = "ERROR:bad token"

    private const val HEX = "0123456789abcdef"

    /** 16 cryptographically-random bytes → 32 hex chars (128-bit secret). */
    private const val TOKEN_BYTES = 16

    /**
     * Whether this app answers automation at all. **Default true** since 2026-09-04.
     *
     * Kept as a switch rather than removed: it is the only way to close this app off, and a
     * feature that can be turned on but never off is one 白い熊 cannot retreat from.
     */
    fun isEnabled(): Boolean = getSharedPreferences().getBoolean(ENABLED, true)

    fun setEnabled(value: Boolean) = getSharedPreferences().edit { putBoolean(ENABLED, value) }

    /** Whether a caller must present the token. **Default false** — the token is opt-in now. */
    fun isTokenRequired(): Boolean = getSharedPreferences().getBoolean(REQUIRE_TOKEN, false)

    fun setTokenRequired(value: Boolean) = getSharedPreferences().edit { putBoolean(REQUIRE_TOKEN, value) }

    /** The shared secret; generated and persisted on first access so it is never empty. */
    fun getToken(): String {
        getSharedPreferences().getString(TOKEN, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        return regenerateToken()
    }

    fun regenerateToken(): String {
        val fresh = generateToken()
        getSharedPreferences().edit { putString(TOKEN, fresh) }
        return fresh
    }

    /**
     * True when [token] matches the stored secret (constant-time). Deliberately says nothing
     * about [isEnabled] or [isTokenRequired] — [refuse] is the only place those combine.
     */
    fun isTokenValid(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return MessageDigest.isEqual(token.toByteArray(), getToken().toByteArray())
    }

    /**
     * The whole gate, in the one place every entry point asks — the receiver, the provider,
     * the data service and the [shiroikuma.ongaku.AUTOMATION] activity.
     *
     * Returns null to proceed, or the exact `ERROR:` string to answer with. Written as one
     * function so no entry point can implement the two checks in a subtly different order,
     * which is how "disabled" and "bad token" drift apart across forty-two apps.
     *
     * **A token supplied while [REQUIRE_TOKEN] is off is IGNORED, never an error.**
     */
    fun refuse(candidate: String?): String? = when {
        !isEnabled() -> ERROR_DISABLED
        isTokenRequired() && !isTokenValid(candidate) -> ERROR_BAD_TOKEN
        else -> null
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }
}
