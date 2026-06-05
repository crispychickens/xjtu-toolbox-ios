package com.xjtu.toolbox.shared.auth

interface AccessModeStore {
    fun load(): AccessMode
    fun save(mode: AccessMode)
}

class InMemoryAccessModeStore(
    private var mode: AccessMode = AccessMode.AUTO,
) : AccessModeStore {
    override fun load(): AccessMode = mode

    override fun save(mode: AccessMode) {
        this.mode = mode
    }
}
