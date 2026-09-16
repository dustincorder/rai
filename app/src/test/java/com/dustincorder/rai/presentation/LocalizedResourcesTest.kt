package com.dustincorder.rai.presentation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedResourcesTest {
    @Test
    fun `touched main and settings resource keys exist in all supported locales`() {
        val keys = listOf(
            "app_name", "assistant_name", "assistant_subtitle", "empty_conversation", "user_label",
            "mic_off", "mic_on", "voice_chat", "stop_speech", "end_voice_chat",
            "status_ready", "status_listening", "status_thinking", "status_answering", "status_speaking", "status_error",
            "settings_title", "stt_engine", "tts_engine", "system_engine", "stt_model", "local_neural_unavailable",
            "show_key", "hide_key", "api_key_saved", "api_key_not_saved", "api_key_unreadable",
            "error_reply_unavailable", "error_recognition_start", "error_recognition", "error_voice_pipeline", "notice_inactivity",
        )
        val projectRoot = Path.of(System.getProperty("user.dir")).let { cwd ->
            if (Files.isDirectory(cwd.resolve("src/main/res"))) cwd else cwd.resolve("app")
        }
        listOf("values", "values-ru", "values-uk").forEach { localeDirectory ->
            val xml = Files.readAllBytes(
                projectRoot.resolve("src/main/res").resolve(localeDirectory).resolve("strings.xml"),
            ).toString(Charsets.UTF_8)
            keys.forEach { key ->
                assertTrue("$key missing from $localeDirectory", xml.contains("name=\"$key\""))
            }
        }
    }
}
