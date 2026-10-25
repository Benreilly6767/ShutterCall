package com.example.shuttercall

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

class ButtonService : AccessibilityService() {

    private enum class State { IDLE, RINGING, ACTIVE }

    private var state = State.IDLE
    private var number: String? = null
    private var nameShown = false
    private var lastClick = 0L
    private var overlay: TextView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // UPDATED: Added VOLUME_DOWN (25) so it catches both clicks from the selfie stick.
    private val triggerKeys = setOf(
        KeyEvent.KEYCODE_VOLUME_UP,     // 24
        KeyEvent.KEYCODE_VOLUME_DOWN,   // 25
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    )

    // ---- 1. Track call state ----------------------------------------------
    private val phoneReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    if (state != State.RINGING) nameShown = false
                    state = State.RINGING
                    // The number can be absent in the first broadcast, so keep any earlier value.
                    intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        ?.let { number = it }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    state = State.ACTIVE
                    hideOverlay()
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    state = State.IDLE
                    number = null
                    nameShown = false
                    hideOverlay()
                }
            }
        }
    }

    override fun onServiceConnected() {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(phoneReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(phoneReceiver, filter)
        }
        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }

    override fun onDestroy() {
        unregisterReceiver(phoneReceiver)
        tts?.shutdown()
        hideOverlay()
        super.onDestroy()
    }

    // ---- 2. Capture the Bluetooth button -----------------------------------
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Not in a call, or not our key: let Android handle it normally (camera shutter etc.)
        if (state == State.IDLE || event.keyCode !in triggerKeys) return false

        // UPDATED: Ignore the phone's own physical buttons; accept only the external Bluetooth remote.
        val dev = event.device
        if (dev == null || dev.vendorId == 0) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastClick > 500) {          // debounce
                lastClick = now
                onClick()
            }
        }
        return true // consume down and up, so the volume doesn't change
    }

    // ---- 3. The 3-click state machine ---------------------------------------
    private fun onClick() {
        when (state) {
            State.RINGING -> if (!nameShown) showCaller() else answerCall()  // click 1, click 2
            State.ACTIVE  -> endCall()                                       // click 3
            State.IDLE    -> Unit
        }
    }

    private fun showCaller() {
        nameShown = true
        val name = lookupName(number)
        showOverlay(name)
        if (ttsReady) tts?.speak(name, TextToSpeech.QUEUE_FLUSH, null, "caller")
    }

    private fun lookupName(num: String?): String {
        if (num.isNullOrBlank()) return "Unknown caller"
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return num
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)
        )
        contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return num // not in contacts: show the number
    }

    private fun answerCall() {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return
        try {
            getSystemService(TelecomManager::class.java).acceptRingingCall()
        } catch (e: SecurityException) { /* permission revoked */ }
    }

    private fun endCall() {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return
        try {
            getSystemService(TelecomManager::class.java).endCall()
        } catch (e: SecurityException) { /* permission revoked */ }
    }

    // ---- 4. On-screen caller name (no extra permission needed) ------------
    private fun showOverlay(text: String) {
        hideOverlay()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            this.text = text
            textSize = 30f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 96)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        wm.addView(tv, lp)
        overlay = tv
    }

    private fun hideOverlay() {
        overlay?.let {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {}
        }
        overlay = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
