package org.monogram.core

sealed interface AppError {
    data class Network(val cause: Throwable?) : AppError
    data class Unauthorized(val cause: Throwable?) : AppError
    data class NotFound(val cause: Throwable?) : AppError
    data class Validation(val message: String) : AppError
    data class Unknown(val cause: Throwable?) : AppError
}