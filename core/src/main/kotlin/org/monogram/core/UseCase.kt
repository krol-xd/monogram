package org.monogram.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

abstract class UseCase<in Param, out Result>(
    private val dispatcher: CoroutineDispatcher
) {
    suspend operator fun invoke(param: Param): Result =
        withContext(dispatcher) {
            execute(param)
        }
    protected abstract suspend fun execute(param: Param): Result
}