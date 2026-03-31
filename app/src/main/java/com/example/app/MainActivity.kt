package com.example.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.text.text = buildString {
            appendLine("ASM Method Trace sample")
            appendLine("Open Logcat and filter by tag: MethodTrace")
            appendLine("The sdk module is instrumented automatically by the Gradle plugin.")
        }
    }
}
