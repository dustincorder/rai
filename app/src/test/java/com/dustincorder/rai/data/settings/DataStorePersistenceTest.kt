package com.dustincorder.rai.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStorePersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `empty settings datastore defaults appearance to Raya`() = runBlocking {
        val dataStore = newDataStore()
        assertEquals(AppearanceMode.Raya, DataStoreSettingsRepository(dataStore, true).settings.first().appearanceMode)
    }

    @Test
    fun `saved Dynamic appearance survives repository recreation`() = runBlocking {
        val dataStore = newDataStore()
        DataStoreSettingsRepository(dataStore, true).save(AppSettings(appearanceMode = AppearanceMode.Dynamic))

        assertEquals(AppearanceMode.Dynamic, DataStoreSettingsRepository(dataStore, true).settings.first().appearanceMode)
    }

    @Test
    fun `missing appearance field remains Raya-compatible default`() = runBlocking {
        val dataStore = newDataStore()
        DataStoreSettingsRepository(dataStore, true).save(AppSettings())

        assertEquals(AppearanceMode.Raya, DataStoreSettingsRepository(dataStore, true).settings.first().appearanceMode)
    }

    @Test
    fun `onboarding completion survives store recreation`() = runBlocking {
        val dataStore = newDataStore()
        val first = OnboardingStore(dataStore, true)
        assertEquals(null, first.completed.first())
        first.markCompleted()

        assertEquals(true, OnboardingStore(dataStore, true).completed.first())
    }

    @Test
    fun `policy explicitly false still shows onboarding for legacy data`() {
        assertTrue(OnboardingPolicy.shouldShow(false, true))
        assertFalse(OnboardingPolicy.shouldShow(true, true))
    }

    private fun newDataStore() = PreferenceDataStoreFactory.create {
        temporaryFolder.newFile("preferences-${System.nanoTime()}.preferences_pb")
    }
}
