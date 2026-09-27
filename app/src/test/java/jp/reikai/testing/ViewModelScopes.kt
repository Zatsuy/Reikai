package jp.reikai.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/**
 * Ends a view model's work and waits for it, so nothing it started resumes on `Dispatchers.Main`
 * after the test's `resetMain`. A late resume there throws, and kotlinx-coroutines-test pins the
 * exception on the next test as `UncaughtExceptionsBeforeTest`. Cancelling without the join is not
 * enough: work on the IO dispatcher still finishes, and its parent then resumes on Main.
 */
suspend fun cancelAndJoinScope(model: ViewModel) {
    model.viewModelScope.coroutineContext.job.cancelAndJoin()
}
