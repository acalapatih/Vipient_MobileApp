package com.acalapatih.vipiend.view.fragments

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
import com.acalapatih.vipiend.CheckInternetConnection
import com.acalapatih.vipiend.R
import com.acalapatih.vipiend.SharedPreference
import com.acalapatih.vipiend.constant.Const.REQUEST_PERMISSION_CODE
import com.acalapatih.vipiend.databinding.FragmentHomeBinding
import com.acalapatih.vipiend.db.DbHelper
import com.acalapatih.vipiend.model.Server
import com.acalapatih.vipiend.utils.CsvParser
import com.acalapatih.vipiend.utils.toast
import com.acalapatih.vipiend.view.activites.ChangeServerActivity
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

    private var handler: WeakHandler? = null
    private val okHttpClient = OkHttpClient()
    private var mCall: Call? = null
    private var request: Request? = null
    private lateinit var dbHelper: DbHelper

    private lateinit var server: Server
    private var isServerSelected: Boolean = false

    private var facebookInterstitialAd: InterstitialAd? = null

    private val getServerResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val selectedServer = initObserver()
                globalServer = selectedServer

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
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentHomeBinding.inflate(layoutInflater, container, false)
        return binding!!.root
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requestNotificationPermission()
        isServiceRunning()
        VpnStatus.initLogCache(mContext.cacheDir)

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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("DEPRECATION")
    private fun requestNotificationPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_PERMISSION_CODE
        )
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Toast.makeText(requireContext(), "Notifikasi Diizinkan", Toast.LENGTH_SHORT).show()
                    binding?.btnCheckIp?.setOnClickListener {
                        val ipAddress = getLocalIpAddress()
                        Log.d("YourFragment", "IP Address: $ipAddress") // Debugging
                        showNotification(ipAddress)
                    }
                } else {
                    Toast.makeText(requireContext(), "Gagal Memberikan Perizinan Notifikasi", Toast.LENGTH_SHORT).show()
                    requireActivity().onBackPressed()
                }
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses: List<InetAddress> = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress) {
                        val sAddr = address.hostAddress
                        val isIPv4 = sAddr?.indexOf(':')!! < 0
                        if (isIPv4) return sAddr
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Unknown IP"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showNotification(ipAddress: String) {
        val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel("ip_channel", "IP Notification", NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(requireContext(), HomeFragment::class.java)
        val pendingIntent = PendingIntent.getActivity(requireContext(), 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(requireContext(), "ip_channel")
            .setSmallIcon(R.drawable.ic_drawer_icon)
            .setContentTitle("Your IP Address")
            .setContentText(ipAddress)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
    }

    private fun initObserver(): Server {
        mCall = request?.let { okHttpClient.newCall(it) }
        mCall!!.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                handler?.post {
                    requireContext().toast("Failed to fetch server data")
                }
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val servers = CsvParser.parse(response)
                            dbHelper.save(servers)

                            val firstServer = servers.first()
                            server = Server(
                                firstServer.hostName,
                                firstServer.ipAddress,
                                firstServer.ping,
                                firstServer.speed,
                                firstServer.countryLong,
                                firstServer.countryShort,
                                firstServer.ovpnConfigData,
                                firstServer.port,
                                firstServer.protocol
                            )
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                requireContext().toast("Failed to process server data")
                            }
                        }
                    }
                } else {
                    handler?.post {
                        requireContext().toast("Failed to fetch server data")
                    }
                }
            }
        })
        return server
    }

    override fun onDestroyView() {
        binding = null
        if (facebookInterstitialAd != null) {
            facebookInterstitialAd!!.destroy()
        }
        super.onDestroyView()
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
                vpnStart = true // it will use after restart this activity
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

    private var broadcastReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                setStatus(intent.getStringExtra("state"))
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
            try {
                var duration = intent.getStringExtra("duration")
                if (duration == null) duration = "00:00:00"
                updateConnectionStatus(duration)
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateConnectionStatus(
        duration: String,
    ) {
        binding!!.vpnConnectionTime.text = duration
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        LocalBroadcastManager.getInstance(mContext).registerReceiver(
            broadcastReceiver!!, IntentFilter("connectionState")
        )
        if (!this::globalServer.isInitialized) {
            if (sharedPreference.isPrefsHasServer) {
                globalServer = sharedPreference.server
                //update selected server
                binding!!.connectionIp.text = globalServer.getIpAddress()
                isServerSelected = true

            } else {
                binding!!.connectionIp.text = resources.getString(R.string.IP_address)
            }
        }
        super.onResume()
    }

    @Suppress("DEPRECATION")
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

        binding!!.afterConnectionDetailBlock.visibility = View.VISIBLE
        binding!!.disconnectButton.visibility = View.VISIBLE
    }

    private fun onDisconnectDone() {
        binding!!.connectionTextBlock.visibility = View.VISIBLE
        binding!!.connectionButtonBlock.visibility = View.VISIBLE
        binding!!.afterConnectionDetailBlock.visibility = View.GONE
        binding!!.disconnectButton.visibility = View.GONE
    }
}