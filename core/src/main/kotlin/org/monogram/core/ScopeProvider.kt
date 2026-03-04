package org.monogram.core

import kotlinx.coroutines.CoroutineScope

interface ScopeProvider {
    val appScope: CoroutineScope
}