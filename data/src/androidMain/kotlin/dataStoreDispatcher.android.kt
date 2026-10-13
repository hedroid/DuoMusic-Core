import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

// A real dedicated thread, not limitedParallelism(1): a quota still borrows physical threads from
// the shared 64-thread pool, so a saturated pool would keep starving DataStore — the exact failure
// seen on 2026-10-12. The thread lives as long as the process-wide Koin single; nothing to close.
@OptIn(ExperimentalCoroutinesApi::class)
actual val dataStoreDispatcher: CoroutineDispatcher = newSingleThreadContext("SimpDataStore")
