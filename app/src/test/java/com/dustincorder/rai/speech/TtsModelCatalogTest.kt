package com.dustincorder.rai.speech

import com.dustincorder.rai.data.settings.AppSettings
import com.dustincorder.rai.domain.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsModelCatalogTest {
    @Test
    fun `catalog is intentionally empty without verified model-weight license`() {
        assertTrue(TtsModelCatalog.trusted.isEmpty())
        assertFalse(TtsModelCatalog.localNeuralAvailable)
    }

    @Test
    fun `system remains production default when local catalog is unavailable`() {
        assertEquals(TtsEngine.System, AppSettings().ttsEngine)
        assertFalse(TtsModelCatalog.trusted.any { it.languageTags.isEmpty() })
    }
}
