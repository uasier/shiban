package com.shiban.app.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.shiban.app.R

class ListRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {
    override fun canChildScrollUp(): Boolean {
        val list = findViewById<RecyclerView>(R.id.list) ?: return false
        return list.isVisible && list.canScrollVertically(-1)
    }
}
