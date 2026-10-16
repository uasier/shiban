package com.shiban.app.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shiban.app.R
import com.shiban.app.data.Changelog
import com.shiban.app.databinding.ActivityChangelogBinding

class ChangelogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityChangelogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        val density = resources.displayMetrics.density
        Changelog.releases.forEach { release ->
            binding.entries.addView(TextView(this).apply {
                text = release.name
                setTextColor(ContextCompat.getColor(this@ChangelogActivity, R.color.ink))
                textSize = 16f
                paint.isFakeBoldText = true
                setPadding(0, (18 * density).toInt(), 0, (6 * density).toInt())
            })
            binding.entries.addView(TextView(this).apply {
                text = Changelog.notes(release)
                setTextColor(ContextCompat.getColor(this@ChangelogActivity, R.color.muted))
                textSize = 14f
                setLineSpacing(4f * density, 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
    }
}
