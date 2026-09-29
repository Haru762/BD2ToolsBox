package com.bd2toolsbox.core.engine

/** A bridge failure, distinct from a Python operation returning its normal failure tuple. */
class EngineBridgeException(
    val function: String,
    val phase: Phase,
    cause: Exception,
) : IllegalStateException("$function [$phase]: ${cause.message ?: cause.javaClass.simpleName}", cause) {
    enum class Phase { CALL, PROTOCOL }
}
