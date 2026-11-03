package link.mccoy88f.zephyr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OnePinTest {
    @Test fun theOlderPinsBecomeOne() {
        val both = migratedPins("adult", "lock", "remote")
        assertEquals("adult", both.pin) // remote access already used the 18+ PIN
        assertTrue(both.lockOn && both.adultOn && both.merged)
        val lockOnly = migratedPins(null, "lock", null)
        assertEquals("lock", lockOnly.pin)
        assertTrue(lockOnly.lockOn)
        assertFalse(lockOnly.adultOn || lockOnly.merged)
        assertEquals("remote", migratedPins(null, null, "remote").pin)
        assertNull(migratedPins(null, null, null).pin)
    }

    @Test fun migrationMovesThePinsOnce() {
        val p = MemoryPrefs()
        p.edit().putString(K.ADULT_PIN, "a").putString(K.LOCK_PIN, "l").commit()
        assertTrue(migratePins(p))
        assertEquals("a", p.getString(K.PIN, null))
        assertTrue(p.getBoolean(K.LOCK_ON, false) && p.getBoolean(K.ADULT_ON, false))
        assertFalse(p.contains(K.ADULT_PIN) || p.contains(K.LOCK_PIN))
        assertFalse(migratePins(p)) // nothing left to move
        assertTrue(adultLockOn(p))
    }

    @Test fun thePinNeverGoesInABackup() {
        val tv = MemoryPrefs()
        val pin = hashPin("1234")
        tv.edit().putString(K.PIN, pin).putBoolean(K.LOCK_ON, true).putBoolean(K.ADULT_ON, true).putString(K.THEME, "dark").commit()
        val backup = Backup.export(tv)
        assertFalse(backup.contains(pin))
        assertFalse(backup.contains(pin.substringAfter(':')))
        assertTrue(JSONObject(backup).getBoolean("pin"))
        assertTrue(Backup.needsPin(backup))

        val other = MemoryPrefs()
        try {
            Backup.restore(other, backup)
            fail("a backup that had a PIN asks for a new one first")
        } catch (e: IllegalArgumentException) {
        }
        assertFalse(other.contains(K.THEME)) // nothing restored without the new PIN

        val fresh = hashPin("9876")
        Backup.restore(other, backup, newPin = fresh)
        assertEquals(fresh, other.getString(K.PIN, null))
        assertTrue(pinMatches(other.getString(K.PIN, null)!!, "9876"))
        assertTrue(other.getBoolean(K.LOCK_ON, false) && other.getBoolean(K.ADULT_ON, false)) // the locks come back on
        assertEquals("dark", other.getString(K.THEME, null))
    }

    @Test fun aBackupWithoutAPinAsksForNone() {
        val tv = MemoryPrefs()
        tv.edit().putString(K.THEME, "glass").commit()
        val backup = Backup.export(tv)
        assertFalse(Backup.needsPin(backup))
        val other = MemoryPrefs()
        Backup.restore(other, backup)
        assertEquals("glass", other.getString(K.THEME, null))
        assertFalse(other.contains(K.PIN))
    }

    @Test fun anOlderBackupsPinStaysOut() {
        val old = JSONObject().put("app", "zephyr").put("format", 1).put("prefs", JSONObject()
            .put(K.ADULT_PIN, JSONObject().put("s", "salt:hash"))
            .put(K.THEME, JSONObject().put("s", "dark"))).toString()
        assertTrue(Backup.needsPin(old))
        val other = MemoryPrefs()
        Backup.restore(other, old, newPin = "new")
        assertFalse(other.contains(K.ADULT_PIN))
        assertEquals("new", other.getString(K.PIN, null))
        assertTrue(other.getBoolean(K.ADULT_ON, false)) // its 18+ lock was on
    }
}
