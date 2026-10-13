import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

// Native has no dedicated-thread dispatcher semantics we rely on here, so a parallelism-1 quota is
// the safe equivalent (see createDataStore.kt for the full rationale).
@OptIn(ExperimentalCoroutinesApi::class)
actual val dataStoreDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
