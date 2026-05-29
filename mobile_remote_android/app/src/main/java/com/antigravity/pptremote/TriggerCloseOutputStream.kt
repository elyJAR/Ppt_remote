package com.antigravity.pptremote

import java.io.OutputStream

class TriggerCloseOutputStream(
    delegate: OutputStream,
    private val onClose: Runnable
) : java.io.FilterOutputStream(delegate) {
    override fun close() {
        try {
            super.close()
        } finally {
            onClose.run()
        }
    }
}
