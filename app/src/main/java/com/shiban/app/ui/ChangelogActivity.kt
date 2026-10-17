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
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val pad = (16 * density).toInt()
                setPadding(pad, pad, pad, pad)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = (12 * density).toInt()
                layoutParams = params
            }
            card.addView(TextView(this).apply {
                text = release.name
                setTextColor(ContextCompat.getColor(this@ChangelogActivity, R.color.ink))
                textSize = 16f
                paint.isFakeBoldText = true
            })
            card.addView(TextView(this).apply {
                text = Changelog.notes(release)
                setTextColor(ContextCompat.getColor(this@ChangelogActivity, R.color.ink_secondary))
                textSize = 13.5f
                setLineSpacing(3f * density, 1f)
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                p.topMargin = (8 * density).toInt()
                layoutParams = p
            })
            binding.entries.addView(card)
        }
    }
}
