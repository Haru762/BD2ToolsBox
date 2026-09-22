package com.bd2toolsbox.integration

internal class StartupStageTrace(private val emit: (String) -> Unit) {
    fun <T> stage(name: String, action: () -> T): T {
        emit("before $name")
        return try {
            val value = action()
            emit("after $name")
            value
        } catch (t: Throwable) {
            emit("error $name")
            throw t
        }
    }
}
