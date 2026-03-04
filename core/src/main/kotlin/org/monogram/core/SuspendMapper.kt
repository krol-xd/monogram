package org.monogram.core

interface SuspendMapper<in I, out O> {
    suspend fun map(input: I): O
}