package com.byf3332.at2ht.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView

class SyncHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var onScrollChangedListener: ((Int) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (l != oldl) {
            onScrollChangedListener?.invoke(l)
        }
    }
}
