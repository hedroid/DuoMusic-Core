import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

// Dedicated thread for the same reason as the android actual (see createDataStore.kt).
@OptIn(ExperimentalCoroutinesApi::class)
actual val dataStoreDispatcher: CoroutineDispatcher = newSingleThreadContext("SimpDataStore")
