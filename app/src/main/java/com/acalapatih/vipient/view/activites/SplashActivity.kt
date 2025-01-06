package com.acalapatih.vipient.view.activites

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.acalapatih.vipient.AppSettings
import com.acalapatih.vipient.databinding.ActivitySplashBinding
import com.onesignal.OneSignal


class SplashActivity : AppCompatActivity() {

    private var binding: ActivitySplashBinding ?= null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding!!.root)

        initOneSignal()
        startMainActivity()
    }

    override fun onDestroy() {
        binding = null
        super.onDestroy()
    }

    private fun initOneSignal() {
        OneSignal.setLogLevel(OneSignal.LOG_LEVEL.VERBOSE, OneSignal.LOG_LEVEL.NONE)
        OneSignal.initWithContext(this@SplashActivity)
        OneSignal.setAppId(AppSettings.oneSignalId)
    }

    private fun startMainActivity() {
        Handler(Looper.myLooper()!!).postDelayed({
            startActivity(Intent(this@SplashActivity, ControllerActivity::class.java))
            finish()
        }, 2000)
    }
}