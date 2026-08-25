package de.davidgrieser.container

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Stores and verifies the admin PIN.
 *
 * The PIN is never stored in clear text. We keep a random per-install salt and
 * a salted, iterated SHA-256 digest. This is a UI gate for the admin menu, not
 * a cryptographic secret store, but it avoids ever persisting the raw PIN.
 */
class PinManager(private val prefs: Prefs) {

    val isPinSet: Boolean get() = prefs.isPinSet

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.pinHash = hash(pin, salt)
    }

    fun verify(pin: String): Boolean {
        val saltB64 = prefs.pinSalt ?: return false
        val expected = prefs.pinHash ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        return constantTimeEquals(hash(pin, salt), expected)
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        var data = salt + pin.toByteArray(Charsets.UTF_8)
        repeat(ITERATIONS) {
            md.reset()
            data = md.digest(data)
        }
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    companion object {
        private const val ITERATIONS = 10_000
        const val MIN_LENGTH = 4
    }
}
