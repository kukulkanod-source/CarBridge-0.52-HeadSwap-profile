package com.example.carbridge

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable

internal data class ProfileApp(val component: ComponentName, val label: String, val icon: Drawable.ConstantState?)

internal class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("carbridge_profile", Context.MODE_PRIVATE)
    var music: Boolean
        get() = prefs.getBoolean("music", false)
        set(value) { prefs.edit().putBoolean("music", value).apply() }
    var musicRight: Boolean
        get() = prefs.getBoolean("musicRight", false)
        set(value) { prefs.edit().putBoolean("musicRight", value).apply() }
    var favoritesRight: Boolean
        get() = prefs.getBoolean("favoritesRight", true)
        set(value) { prefs.edit().putBoolean("favoritesRight", value).apply() }
    var autoHide: Boolean
        get() = prefs.getBoolean("autoHide", false)
        set(value) { prefs.edit().putBoolean("autoHide", value).apply() }
    fun favorites() = read("favorites")
    fun recent() = read("recent").take(8)
    fun favorite(component: ComponentName, selected: Boolean) {
        val id = component.flattenToString()
        val items = favorites().toMutableList()
        if (selected && id !in items) items.add(id) else if (!selected) items.remove(id)
        write("favorites", items)
    }
    fun record(component: ComponentName) = write("recent", (listOf(component.flattenToString()) + recent()).distinct().take(8))
    fun removeRecent(component: ComponentName) = write("recent", recent().filter { it != component.flattenToString() })
    private fun read(key: String) = prefs.getString(key, "").orEmpty().split('|').filter { it.isNotBlank() }.distinct()
    private fun write(key: String, items: List<String>) { prefs.edit().putString(key, items.joinToString("|")).apply() }
}

/** Pure geometry used by both the rendered dock and the published host reservation. */
internal object ProfileGeometry {
    fun dockHeight(height: Int, density: Float): Int =
        (HuDockDimensions.HEIGHT_DP * density).toInt().coerceAtLeast(1).coerceAtMost((height / 5).coerceAtLeast(1))
    fun columns(width: Int, density: Float) = ((width / density - 40) / 120).toInt().coerceIn(2, 8)
}
