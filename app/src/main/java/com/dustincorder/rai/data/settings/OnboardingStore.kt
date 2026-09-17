package com.dustincorder.rai.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore("raya_onboarding")

class OnboardingStore private constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.onboardingDataStore)
    internal constructor(dataStore: DataStore<Preferences>, forTests: Boolean = true) : this(dataStore)
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
