import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import okio.Path.Companion.toPath

/**
 * The single dispatcher every DataStore read/write hops onto — the store's own scope AND every
 * `withContext(...)` wrapping a `settingsDataStore.edit` in DataStoreManagerImpl. It must be a
 * dedicated thread/quota, never the shared Dispatchers.IO: datastore's readState does
 * withContext(scope) per read, and the app's dozen-plus main-thread
 * `runBlocking { dataStore…first() }` call sites wait for a spare thread of whatever pool that
 * is. On the shared pool, a momentary saturation (reproduce case 2026-10-12: source switch on a
 * real device, proxy off, dozens of 15–30s-hanging requests piling up) stalls those runBlockings
 * and freezes the main thread — menus stop responding to taps until the process is killed.
 * Making BOTH the store scope and the write wrappers use this dispatcher keeps the whole
 * pipeline on its own thread, so a saturated shared pool can't starve reads or writes.
 */
expect val dataStoreDispatcher: CoroutineDispatcher

fun createDataStore(
    producePath: () -> String,
    scope: CoroutineScope,
): DataStore<Preferences> {
    return PreferenceDataStoreFactory.createWithPath(
        produceFile = { producePath().toPath() },
        scope = scope,
    )
}
