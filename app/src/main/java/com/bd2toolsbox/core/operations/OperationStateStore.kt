package com.bd2toolsbox.core.operations

import com.bd2toolsbox.core.model.OperationId
import com.bd2toolsbox.core.model.OperationRequest
import com.bd2toolsbox.core.model.OperationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/** 进程内任务的唯一事实源；只保留参数和结果，不保留 UI、executor 或 CoroutineScope。 */
@Singleton
class OperationStateStore @Inject constructor() {
    private val mutableStates = MutableStateFlow<Map<OperationId, OperationState>>(emptyMap())
    val states: StateFlow<Map<OperationId, OperationState>> = mutableStates.asStateFlow()

    // 参数、状态和资源占用在同一把锁下变更，不能先发布 QUEUED 再补占用。
    internal val monitor = Any()
    internal val requests = LinkedHashMap<OperationId, OperationRequest>()

    internal fun publish(state: OperationState) {
        mutableStates.value = Collections.unmodifiableMap(LinkedHashMap(states.value).apply { put(state.id, state) })
    }

    internal fun remove(id: OperationId) {
        mutableStates.value = Collections.unmodifiableMap(LinkedHashMap(states.value).apply { remove(id) })
    }
}
