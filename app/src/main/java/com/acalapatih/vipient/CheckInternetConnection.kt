package com.acalapatih.vipient

import android.content.Context
import android.net.ConnectivityManager

@Suppress("DEPRECATION")
class CheckInternetConnection {
    fun netCheck(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nInfo = cm.activeNetworkInfo

        val isConnected = nInfo != null && nInfo.isConnectedOrConnecting
        return isConnected
    }
}