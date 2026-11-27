package com.soniccontrol.core.automation

import kotlinx.coroutines.flow.StateFlow

/** What the rules editor needs (implemented by the app's automation runtime). */
interface AutomationController {
    val rules: StateFlow<List<Rule>>
    val journal: StateFlow<List<JournalEntry>>
    fun upsert(rule: Rule)
    fun delete(id: String)
    fun setEnabled(id: String, enabled: Boolean)
}
