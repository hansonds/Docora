package com.hanson.scansign

import android.content.Context
import org.json.JSONObject

/** "Fill & sign" details the user saves once (kept only on this phone) and taps to place on any PDF. */
object MyDetails {

    /** Field key to label, in the order shown. */
    val FIELDS = listOf(
        "name" to "Full name",
        "initials" to "Initials",
        "email" to "Email",
        "phone" to "Phone",
        "address" to "Address",
        "city" to "City / PIN code",
        "company" to "Company / organisation",
        "id" to "Customer / member no.",
        "other" to "Anything else"
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("my_details", Context.MODE_PRIVATE)

    fun load(ctx: Context): Map<String, String> = try {
        val o = JSONObject(prefs(ctx).getString("data", "{}")!!)
        FIELDS.associate { (k, _) -> k to o.optString(k, "") }
    } catch (_: Exception) { emptyMap() }

    fun save(ctx: Context, values: Map<String, String>) {
        val o = JSONObject()
        values.forEach { (k, v) -> if (v.isNotBlank()) o.put(k, v.trim()) }
        prefs(ctx).edit().putString("data", o.toString()).apply()
    }
}
