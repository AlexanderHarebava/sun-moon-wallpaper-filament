package com.example.sunrisewallpaperfilament

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.statusBarColor = 0xFF1C1B1F.toInt()
        window?.navigationBarColor = 0xFF1C1B1F.toInt()

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF1C1B1F.toInt())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }


        val title = TextView(this).apply {
            text = getString(R.string.about_title)
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 40 }
        )


        val appDescription = TextView(this).apply {
            text = getString(R.string.about_app_description)
            textSize = 16f
            setTextColor(0xFFE0E0E0.toInt())
            setLineSpacing(0f, 1.3f)
        }
        root.addView(
            appDescription,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 40 }
        )


        val privacyTitle = TextView(this).apply {
            text = getString(R.string.privacy_title)
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(
            privacyTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        )


        val privacyText = TextView(this).apply {
            text = getString(R.string.privacy_text).trimIndent()
            textSize = 16f
            setTextColor(0xFFE0E0E0.toInt())
            setLineSpacing(0f, 1.3f)
        }
        root.addView(
            privacyText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 40 }
        )


        val authorTitle = TextView(this).apply {
            text = getString(R.string.author_title)
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(
            authorTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        )


        val authorLink = Button(this).apply {
            text = "GitHub"
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/AlexanderHarebava")
                )
                startActivity(intent)
            }
        }
        root.addView(
            authorLink,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 32 }
        )

        scrollView.addView(root)
        setContentView(scrollView)
    }
}