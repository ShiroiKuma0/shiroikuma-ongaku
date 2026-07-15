package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences.getSharedPreferences
import java.security.SecureRandom

/**
 * Preference store for the external automation surface (fork-only, hand-off.md B/C).
 *
 * A master enable flag (default OFF) plus a shared secret token that every automation
 * intent must carry. Modeled on the jami fork's AutomationPrefs and the sibling
 * [ShiroikumaPreferences] object. The token is generated lazily on first read so the
 * settings screen always shows a value, and can be regenerated at any time.
 */
object AutomationPreferences {

    const val ENABLED = "sk_automation_enabled"
    const val TOKEN = "sk_automation_token"

    private const val HEX = "0123456789abcdef"

    /** 16 cryptographically-random bytes → 32 hex chars (128-bit secret). */
    private const val TOKEN_BYTES = 16

    fun isEnabled(): Boolean = getSharedPreferences().getBoolean(ENABLED, false)

    fun setEnabled(value: Boolean) = getSharedPreferences().edit { putBoolean(ENABLED, value) }

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

    /** True only when automation is enabled AND [token] matches the stored secret (constant-time). */
    fun isAuthorized(token: String?): Boolean {
        if (!isEnabled()) return false
        if (token.isNullOrEmpty()) return false
        return constantTimeEquals(token, getToken())
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

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray()
        val bb = b.toByteArray()
        if (ab.size != bb.size) return false
        var r = 0
        for (i in ab.indices) r = r or (ab[i].toInt() xor bb[i].toInt())
        return r == 0
    }
}
