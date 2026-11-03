package link.mccoy88f.zephyr

import android.content.SharedPreferences

/** SharedPreferences in memory, for plain JVM tests (Android's own are stubs there). */
internal class MemoryPrefs : SharedPreferences {
    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val put = HashMap<String, Any?>()
        private val gone = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            put[key] = value
            return this
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            put[key] = values?.toMutableSet()
            return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            put[key] = value
            return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            put[key] = value
            return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            put[key] = value
            return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            put[key] = value
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            gone += key
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }
        override fun commit(): Boolean {
            if (clear) values.clear()
            gone.forEach { values.remove(it) }
            put.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
