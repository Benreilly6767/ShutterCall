package com.example.shuttercall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var keyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        fun addButton(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }

        addButton("1. Grant phone / contacts permissions") {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.ANSWER_PHONE_CALLS
                ), 1
            )
        }
        addButton("2. App info (⋮ → Allow restricted settings)") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        addButton("3. Enable the accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("4. Disable battery optimization") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        keyView = TextView(this).apply {
            text = "Key tester: press your shutter button while this screen is open."
            textSize = 18f
            setPadding(0, 48, 0, 0)
        }
        root.addView(keyView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dev = event.device
        keyView.text = "Button sent: ${KeyEvent.keyCodeToString(keyCode)} ($keyCode)\n" +
                "Device: ${dev?.name}\nVendorID: ${dev?.vendorId}  ProductID: ${dev?.productId}"
        return true
    }
}
