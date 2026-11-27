package com.soniccontrol.core.automation

import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.TapType

/**
 * Text form of the rules (one rule per line, `key=value` fields separated by `;`, values percent-escaped). No reflection, no
 * dependency. A line that does not decode into a valid rule is dropped on its own: one damaged rule never loses the others.
 */
object RuleCodec {
    fun encode(rules: List<Rule>): String = rules.joinToString("\n") { encodeRule(it) }

    fun decode(text: String?): List<Rule> = text.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { runCatching { decodeRule(it) }.getOrNull()?.takeIf { r -> r.valid } }

    private fun esc(s: String): String = buildString {
        for (c in s) if (c.isLetterOrDigit() && c.code < 128 || c in " _-.") append(c) else c.toString().toByteArray(Charsets.UTF_8).forEach { append('%').append("%02X".format(it.toInt() and 0xFF)) }
    }

    private fun unesc(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') { out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3 } else { out.write(s[i].toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun fields(vararg p: Pair<String, Any?>) = p.filter { it.second != null }.joinToString(":") { "${it.first}=${esc(it.second.toString())}" }

    private fun encodeRule(r: Rule): String {
        val t = r.trigger
        val trigger = fields(
            "k" to t.kind.name, "nm" to t.noiseMode?.name, "pt" to t.part.name, "th" to t.thresholdPercent, "mn" to t.minuteOfDay,
            "dy" to t.days.sorted().joinToString(",").ifEmpty { null }, "sd" to t.side?.name, "tp" to t.tap?.name,
        )
        val conds = r.conditions.joinToString("|") { c -> fields("k" to c.kind.name, "f" to c.fromMinute, "t" to c.toMinute, "nm" to c.noiseMode?.name) }
        val acts = r.actions.joinToString("|") { a -> fields("k" to a.kind.name, "nm" to a.noiseMode?.name, "v" to a.volumePercent, "eq" to a.eqProfileId, "mi" to a.minutes) }
        return listOf(
            "id" to esc(r.id), "name" to esc(r.name), "en" to (if (r.enabled) "1" else "0"), "pr" to r.priority.toString(), "dl" to r.delaySeconds.toString(),
            "dev" to (r.deviceAddress?.let(::esc) ?: ""), "ca" to r.createdAt.toString(), "tr" to trigger, "co" to conds, "ac" to acts,
        ).joinToString(";") { "${it.first}=${it.second}" }
    }

    private fun parse(s: String, sep: Char): Map<String, String> =
        s.split(sep).filter { it.isNotEmpty() }.associate { kv -> kv.substringBefore('=') to kv.substringAfter('=', "") }

    private fun decodeRule(line: String): Rule {
        val m = parse(line, ';')
        val tm = parse(m.getValue("tr"), ':').mapValues { unesc(it.value) }
        val trigger = Trigger(
            kind = TriggerKind.valueOf(tm.getValue("k")),
            noiseMode = tm["nm"]?.let { NoiseMode.valueOf(it) },
            part = tm["pt"]?.let { BatteryPart.valueOf(it) } ?: BatteryPart.ANY_EARBUD,
            thresholdPercent = tm["th"]?.toInt() ?: 20,
            minuteOfDay = tm["mn"]?.toInt() ?: 0,
            days = tm["dy"]?.split(",")?.filter { it.isNotEmpty() }?.map { it.toInt() }?.toSet() ?: emptySet(),
            side = tm["sd"]?.let { EarSide.valueOf(it) },
            tap = tm["tp"]?.let { TapType.valueOf(it) },
        )
        val conditions = m["co"].orEmpty().split('|').filter { it.isNotEmpty() }.map { c ->
            val f = parse(c, ':').mapValues { unesc(it.value) }
            Condition(ConditionKind.valueOf(f.getValue("k")), f["f"]?.toInt() ?: 0, f["t"]?.toInt() ?: 0, f["nm"]?.let { NoiseMode.valueOf(it) })
        }
        val actions = m["ac"].orEmpty().split('|').filter { it.isNotEmpty() }.map { a ->
            val f = parse(a, ':').mapValues { unesc(it.value) }
            Action(ActionKind.valueOf(f.getValue("k")), f["nm"]?.let { NoiseMode.valueOf(it) }, f["v"]?.toInt(), f["eq"], f["mi"]?.toInt())
        }
        return Rule(
            id = unesc(m.getValue("id")), name = unesc(m.getValue("name")), trigger = trigger, actions = actions, conditions = conditions,
            enabled = m["en"] != "0", priority = m["pr"]?.toInt() ?: 0, delaySeconds = m["dl"]?.toInt() ?: 0,
            deviceAddress = m["dev"]?.takeIf { it.isNotEmpty() }?.let(::unesc), createdAt = m["ca"]?.toLong() ?: 0,
        )
    }
}
