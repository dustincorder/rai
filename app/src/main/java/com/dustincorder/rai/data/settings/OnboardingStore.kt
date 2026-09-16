package com.dustincorder.rai.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore("raya_onboarding")

class OnboardingStore(context: Context) {
    private val dataStore = context.applicationContext.onboardingDataStore
    val completed: Flow<Boolean?> = dataStore.data.map { it[COMPLETED] }

    suspend fun markCompleted() {
        dataStore.edit { it[COMPLETED] = true }
    }

    private companion object {
        val COMPLETED = booleanPreferencesKey("completed")
    }
}

object OnboardingPolicy {
    fun shouldShow(completed: Boolean?, legacySettingsExist: Boolean): Boolean =
        when (completed) {
            true -> false
            false -> true
            null -> !legacySettingsExist
        }
}
