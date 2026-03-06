package org.monogram.presentation.settings.debug

import org.monogram.presentation.root.AppComponentContext

interface DebugComponent {
    fun onBackClicked()
    fun onCrashClicked()
}

class DefaultDebugComponent(
    context: AppComponentContext,
    private val onBack: () -> Unit
) : DebugComponent, AppComponentContext by context {

    override fun onBackClicked() {
        onBack()
    }

    override fun onCrashClicked() {
        throw RuntimeException("Debug crash")
    }
}
