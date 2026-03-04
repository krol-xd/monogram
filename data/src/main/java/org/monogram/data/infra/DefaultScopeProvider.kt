package org.monogram.data.infra

import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class DefaultScopeProvider(
    dispatcherProvider: DispatcherProvider
) : ScopeProvider {
    override val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + dispatcherProvider.default
    )
}