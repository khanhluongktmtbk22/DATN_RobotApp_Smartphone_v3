package com.example.datn_v1

import java.text.Normalizer
import java.util.Locale

internal object ChatbotSpeechPolicy {
    const val SILENCE_TIMEOUT_MS = 8_000L

    fun wakeGreeting(profile: UserProfile): String {
        val elder = profile.elderlyPronoun.trim().ifBlank { "ông" }.lowercase(Locale.ROOT)
        val self = profile.robotPronoun.trim().ifBlank { "cháu" }.replaceFirstChar { it.uppercase() }
        val name = profile.robotName.trim().ifBlank { "Robot" }
        return "Dạ $elder ơi, $self $name đây."
    }

    fun reminderIntroduction(profile: UserProfile): String {
        val elder = profile.elderlyPronoun.trim().ifBlank { "ông" }.lowercase(Locale.ROOT)
        val name = profile.caregiverName.trim()
        val relation = profile.caregiverRelation.trim().lowercase(Locale.ROOT)
        val sender = when {
            name.isNotEmpty() && relation.isNotEmpty() -> "$relation $elder là $name"
            name.isNotEmpty() -> name
            relation.isNotEmpty() -> "$relation $elder"
            else -> "người chăm sóc"
        }
        return "${elder.replaceFirstChar { it.uppercase() }} ơi, $sender nhắc $elder là:"
    }

    fun isEndConversation(text: String, robotName: String): Boolean {
        var phrase = normalize(text)
        val name = normalize(robotName)
        if (name.isNotBlank()) {
            // Accept an optional direct address without treating a sentence containing
            // the stop phrase (e.g. a quotation or a follow-up request) as a command.
            phrase = phrase.removePrefix("$name oi ").removePrefix("$name ")
                .removeSuffix(" $name oi").removeSuffix(" $name")
        }
        return phrase in setOf(
            "thoi duoc roi", "thoi duoc roi nhe", "thoi duoc roi nha",
            "thoi duoc roi a", "dung tro chuyen", "ket thuc tro chuyen",
            "ket thuc cuoc tro chuyen", "ngung tro chuyen"
        )
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT).replace('đ', 'd')
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .trim().replace(Regex("\\s+"), " ")
}
