package com.heat.app.core

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("heat", Context.MODE_PRIVATE)

    /** Stable random id (4 bytes as 8 hex chars), advertised over BLE. */
    val id: String = sp.getString("id", null) ?: randomId(8).also { sp.edit().putString("id", it).apply() }

    var name: String
        get() = sp.getString("name", null)
            ?: "Heat-${(100..999).random()}".also { sp.edit().putString("name", it).apply() }
        set(v) { sp.edit().putString("name", v).apply() }

    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    /** Bumped to v2 when groups/profile clauses were added, so everyone re-accepts. */
    var termsAccepted: Boolean
        get() = sp.getBoolean("terms_v2", false)
        set(v) { sp.edit().putBoolean("terms_v2", v).apply() }

    var egg: Boolean
        get() = sp.getBoolean("egg", false)
        set(v) { sp.edit().putBoolean("egg", v).apply() }

    /** 0 = initial letter, 1 = my photo, 2 = secret cow photo (needs egg) */
    var avatarMode: Int
        get() = sp.getInt("avmode", 0)
        set(v) { sp.edit().putInt("avmode", v).apply() }

    /** 0..255, advertised so nearby phones know when to re-download my photo. */
    var avatarVer: Int
        get() = sp.getInt("avver", 0)
        set(v) { sp.edit().putInt("avver", v and 0xFF).apply() }

    /** Smoothed measured Wi-Fi Direct throughput in bytes/second, used for ETA estimates. */
    var throughput: Double
        get() = sp.getFloat("tp", 8_000_000f).toDouble()
        set(v) { sp.edit().putFloat("tp", v.toFloat()).apply() }
}
