package com.acalapatih.vipient.view.fragments

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.acalapatih.vipient.BuildConfig
import com.acalapatih.vipient.CheckInternetConnection
import com.acalapatih.vipient.R
import com.acalapatih.vipient.SharedPreference
import com.acalapatih.vipient.constant.Const.REQUEST_PERMISSION_CODE
import com.acalapatih.vipient.databinding.FragmentHomeBinding
import com.acalapatih.vipient.db.DbHelper
import com.acalapatih.vipient.model.Server
import com.acalapatih.vipient.utils.CsvParser
import com.acalapatih.vipient.utils.toast
import com.acalapatih.vipient.view.activites.ChangeServerActivity
import com.badoo.mobile.util.WeakHandler
import com.facebook.ads.InterstitialAd
import de.blinkt.openvpn.OpenVpnApi
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.OpenVPNThread
import de.blinkt.openvpn.core.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections


class HomeFragment : Fragment() {

    private lateinit var mContext: Context

    private var binding: FragmentHomeBinding? = null
    private var connection: CheckInternetConnection? = null
    private var vpnStart = false

    private lateinit var globalServer: Server
    private lateinit var vpnThread: OpenVPNThread
    private lateinit var vpnService: OpenVPNService
    private lateinit var sharedPreference: SharedPreference

    private var isServerSelected: Boolean = false

    private val getServerResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val selectedServer = result.data!!.getParcelableExtra<Server>("serverextra");
                globalServer = selectedServer!!

                //update selected server
                binding!!.serverFlagName.text = selectedServer.getCountryLong()
                binding!!.serverFlagDes.text = selectedServer.getIpAddress()

                binding!!.connectionIp.text = selectedServer.getIpAddress()
                isServerSelected = true
            }
        }

    private val vpnResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vpnResult ->
            if (vpnResult.resultCode == Activity.RESULT_OK) {
                //Permission granted, start the VPN
                startVpn()
            } else {
                mContext.toast("For a successful VPN connection, permission must be granted.")
            }
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mContext = context
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnThread = OpenVPNThread()
        vpnService = OpenVPNService()
        connection = CheckInternetConnection()
        sharedPreference = SharedPreference(mContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        binding = FragmentHomeBinding.inflate(layoutInflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Checking is vpn already running or not
        isServiceRunning()
        VpnStatus.initLogCache(mContext.cacheDir)

        binding!!.serverSelectionBlock.setOnClickListener {
            if (!vpnStart) {
                getServerResult.launch(
                    Intent(mContext, ChangeServerActivity::class.java)
                )
            } else {
                mContext.toast(resources.getString(R.string.disconnect_first))
            }
        }

        binding!!.connectionButtonBlock.setOnClickListener {
            if (!vpnStart && isServerSelected) {
                prepareVpn()
            } else if (!isServerSelected && !vpnStart) {
                getServerResult.launch(
                    Intent(mContext, ChangeServerActivity::class.java)
                )
            } else if (vpnStart && !isServerSelected) {
                mContext.toast(resources.getString(R.string.disconnect_first))
            } else {
                mContext.toast("Unable to connect the VPN")
            }
        }

        binding!!.disconnectButton.setOnClickListener {
            if (vpnStart) {
                confirmDisconnect()
            }
        }
    }

    private fun isServiceRunning() {
        setStatus(OpenVPNService.getStatus())
    }

    private fun getInternetStatus(): Boolean {
        return connection!!.netCheck(mContext)
    }

    fun setStatus(connectionState: String?) {
        if (connectionState != null) when (connectionState) {
            "DISCONNECTED" -> {
                status("Connect")
                vpnStart = false
                OpenVPNService.setDefaultStatus()
                binding!!.connectionTextStatus.text = "Disconnected"
            }
            "CONNECTED" -> {
                vpnStart = true
                status("Connected")
                binding!!.connectionTextStatus.text = "Connected"
            }
            "WAIT" -> binding!!.connectionTextStatus.text = "Waiting for server connection"
            "AUTH" -> binding!!.connectionTextStatus.text = "Authenticating server"
            "RECONNECTING" -> {
                status("Connecting")
                binding!!.connectionTextStatus.text = "Reconnecting..."
            }
            "NONETWORK" -> binding!!.connectionTextStatus.text = "No network connection"
        }
    }

    private fun status(status: String) {
        //update UI here
        when (status) {
            "Connect" -> {
                onDisconnectDone()
            }
            "Connecting" -> {
            }
            "Connected" -> {
                onConnectionDone()
            }
            "tryDifferentServer" -> {
            }
            "loading" -> {
            }
            "invalidDevice" -> {
            }
            "authenticationCheck" -> {
            }
        }
    }

    private fun prepareVpn() {
        if (!vpnStart) {
            if (getInternetStatus()) {
                val intent = VpnService.prepare(context)
                if (intent != null) {
                    vpnResult.launch(intent)
                } else {
                    startVpn()
                }
                status("Connecting")
            } else {
                mContext.toast("No Internet Connection")
            }
        } else if (stopVpn()) {
            mContext.toast("Disconnect Successfully")
        }
    }

    private fun confirmDisconnect() {
        val builder = AlertDialog.Builder(
            mContext
        )
        builder.setMessage(mContext.getString(R.string.connection_close_confirm))
        builder.setPositiveButton(
            mContext.getString(R.string.yes)
        ) { dialog, id -> stopVpn() }
        builder.setNegativeButton(
            mContext.getString(R.string.no)
        ) { dialog, id ->
        }

        val dialog = builder.create()
        dialog.show()
    }

    private fun stopVpn(): Boolean {
        try {
            OpenVPNThread.stop()
            status("Connect")
            vpnStart = false
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun startVpn() {
        try {
            val conf = globalServer.getOvpnConfigData()
            OpenVpnApi.startVpn(context, conf, globalServer.getCountryShort(), "vpn", "vpn")
            binding!!.connectionTextStatus.text = "Connecting..."
            vpnStart = true
        } catch (exception: IOException) {
            exception.printStackTrace()
        } catch (exception: RemoteException) {
            exception.printStackTrace()
        }
    }

    /**
     * Broadcast receivers ***************************
     */

    private var broadcastReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                setStatus(intent.getStringExtra("state"))
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
            try {
                var duration = intent.getStringExtra("duration")
                var lastPacketReceive = intent.getStringExtra("lastPacketReceive")
                var byteIn = intent.getStringExtra("byteIn")
                var byteOut = intent.getStringExtra("byteOut")
                if (duration == null) duration = "00:00:00"
                if (lastPacketReceive == null) lastPacketReceive = "0"
                if (byteIn == null) byteIn = "0.0"
                if (byteOut == null) byteOut = "0.0"
                updateConnectionStatus(duration, lastPacketReceive, byteIn, byteOut)
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Update status UI
     * @param duration: running time
     * @param lastPacketReceive: last packet receive time
     * @param byteIn: incoming data
     * @param byteOut: outgoing data
     */
    fun updateConnectionStatus(
        duration: String,
        lastPacketReceive: String,
        byteIn: String,
        byteOut: String
    ) {
        binding!!.vpnConnectionTime.setText("$duration")
    }

    override fun onResume() {
        LocalBroadcastManager.getInstance(mContext).registerReceiver(
            broadcastReceiver!!, IntentFilter("connectionState")
        )
        if (!this::globalServer.isInitialized) {
            if (sharedPreference.isPrefsHasServer) {
                globalServer = sharedPreference.server
                //update selected server
                binding!!.serverFlagName.text = globalServer.getCountryLong()
                binding!!.serverFlagDes.text = globalServer.getIpAddress()

                binding!!.connectionIp.text = globalServer.getIpAddress()
                isServerSelected = true

            } else {
                binding!!.serverFlagName.text = resources.getString(R.string.country_name)
                binding!!.serverFlagDes.text = resources.getString(R.string.IP_address)

                binding!!.connectionIp.text = resources.getString(R.string.IP_address)
            }
        }
        super.onResume()
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(
            broadcastReceiver!!
        )
        super.onPause()
    }

    /**
     * Save current selected server on local shared preference
     */
    override fun onStop() {
        if (this::globalServer.isInitialized) {
            sharedPreference.saveServer(globalServer)
        }
        super.onStop()
    }

    private fun onConnectionDone() {
        binding!!.connectionTextBlock.visibility = View.GONE
        binding!!.connectionButtonBlock.visibility = View.GONE
        binding!!.serverSelectionBlock.visibility = View.GONE

        binding!!.afterConnectionDetailBlock.visibility = View.VISIBLE
        binding!!.disconnectButton.visibility = View.VISIBLE
    }

    private fun onDisconnectDone() {
        binding!!.connectionTextBlock.visibility = View.VISIBLE
        binding!!.connectionButtonBlock.visibility = View.VISIBLE
        binding!!.serverSelectionBlock.visibility = View.VISIBLE
        binding!!.afterConnectionDetailBlock.visibility = View.GONE
        binding!!.disconnectButton.visibility = View.GONE
    }
}