package com.example.datn_v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatbotSpeechPolicyTest {
    private val profile = UserProfile(
        elderlyPronoun = "Ông", robotPronoun = "cháu", robotName = "Mít",
        caregiverName = "Khang", caregiverRelation = "Con"
    )

    @Test fun greetingUsesConfiguredNamesAndPronouns() {
        assertEquals("Dạ ông ơi, Cháu Mít đây.", ChatbotSpeechPolicy.wakeGreeting(profile))
        assertEquals("Dạ bà ơi, Em Na đây.", ChatbotSpeechPolicy.wakeGreeting(
            profile.copy(elderlyPronoun = "Bà", robotPronoun = "em", robotName = "Na")
        ))
    }

    @Test fun reminderAttributesTheMessageToTheCaregiver() {
        assertEquals("Ông ơi, con ông là Khang nhắc ông là:",
            ChatbotSpeechPolicy.reminderIntroduction(profile))
        assertEquals("Bà ơi, cháu bà là Lan nhắc bà là:",
            ChatbotSpeechPolicy.reminderIntroduction(profile.copy(
                elderlyPronoun = "Bà", caregiverRelation = "Cháu", caregiverName = "Lan"
            )))
    }

    @Test fun missingCaregiverFieldsStillProduceACompleteIntroduction() {
        assertEquals("Ông ơi, Khang nhắc ông là:",
            ChatbotSpeechPolicy.reminderIntroduction(profile.copy(caregiverRelation = "")))
        assertEquals("Ông ơi, con ông nhắc ông là:",
            ChatbotSpeechPolicy.reminderIntroduction(profile.copy(caregiverName = "")))
        assertEquals("Ông ơi, người chăm sóc nhắc ông là:",
            ChatbotSpeechPolicy.reminderIntroduction(profile.copy(caregiverName = "", caregiverRelation = "")))
    }

    @Test fun stopCommandAcceptsAccentsPunctuationAndDirectAddress() {
        listOf("Thôi được rồi!", "thoi duoc roi", "Mít ơi, thôi được rồi nhé.",
            "Thôi được rồi, Mít ơi", "Kết thúc cuộc trò chuyện").forEach {
            assertTrue(it, ChatbotSpeechPolicy.isEndConversation(it, "Mít"))
        }
    }

    @Test fun stopCommandDoesNotSwallowAQuestionOrNegation() {
        listOf("Thôi được rồi nhưng cho ông hỏi thêm", "Đừng dừng trò chuyện",
            "Câu thôi được rồi nghĩa là gì", "Ông thấy khỏe rồi", "Được rồi").forEach {
            assertFalse(it, ChatbotSpeechPolicy.isEndConversation(it, "Mít"))
        }
    }
}
