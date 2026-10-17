// Author: Pandiyaraj Karuppasamy
// Date: Oct-17-2026

package com.pandiyarajk.sportshub.data

/**
 * The tab registry. Adding a sport is one entry here, one repository and one screen: the
 * navigation bar iterates [entries], so nothing else changes.
 */
enum class SportTab(val title: String, val glyph: String) {
    CRICKET("Cricket", "🏏"),
    FOOTBALL("Football", "⚽"),
}
