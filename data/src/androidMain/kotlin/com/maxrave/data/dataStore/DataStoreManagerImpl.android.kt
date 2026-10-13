package com.maxrave.data.dataStore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.maxrave.common.SETTINGS_FILENAME
import dataStoreDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import createDataStore
import org.koin.mp.KoinPlatform.getKoin

actual fun createDataStoreInstance(): DataStore<Preferences> =
    createDataStore(
        producePath = { getKoin().get<Context>().filesDir.resolve("datastore/$SETTINGS_FILENAME.preferences_pb").absolutePath },
        scope = CoroutineScope(SupervisorJob() + dataStoreDispatcher),
    )
