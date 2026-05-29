package com.antigravity.pptremote

fun interface FtpItemChangedListener {
    fun onItemChanged()
}

object FtpFileSystemEvents {
    var onItemChangedListener: FtpItemChangedListener? = null
    
    fun notifyItemChanged() {
        onItemChangedListener?.onItemChanged()
    }
}
