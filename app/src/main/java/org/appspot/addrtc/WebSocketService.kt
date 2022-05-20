package org.appspot.addrtc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.*
import java.util.*
import java.util.concurrent.Executors

class WebSocketService : Service() {
    interface NsdEvents {
        fun onServiceResolved(service: NsdServiceInfo)
        fun onServiceLost(service: NsdServiceInfo)
    }

    inner class WebSocketBinder : Binder() {
        private var portNumber = 8888
        private var deviceName = "MyDevice"
        fun startServer(signalingEvents: SignalingServer.Events, port: Int, name: String, nsdEvents: NsdEvents? = null) {
            portNumber = port
            deviceName = name
            server?.stop()
            this@WebSocketService.nsdEvents = nsdEvents
            server = SignalingServer(signalingEvents, port).apply {
                start()
                startDiscovery()
            }
        }

        fun updateEvents(events: SignalingServer.Events) {
            server?.updateEvents(events)
        }

        fun send(message: String) : Boolean {
            if (socketClient?.isOpen == true) {
                socketClient?.send(message)
            } else if (server?.connections?.isNotEmpty() == true) {
                server?.broadcast(message)
            } else {
                return false
            }
            return true
        }

        fun closeConnections() {
            socketClient?.close()
            server?.connections?.forEach {
                it.close()
            }
        }

        fun connectClient(events: SignalingServer.Events, uri: Uri) {
            socketClient = SocketClient(events, uri)
            socketClient?.connect()
        }

        fun stopDiscovery() {
            executor.execute{
                unregisterNsdService()
            }
        }

        fun startDiscovery() {
            executor.execute {
                registerNsdService(deviceName, portNumber)
                nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }
        }
    }

    private class SocketClient(private val socketEvents: SignalingServer.Events, uri: Uri) : WebSocketClient(
        createURI(uri.toString())
    ) {
        override fun onOpen(handshakedata: ServerHandshake) {
            socketEvents.onOpen()
        }

        override fun onMessage(message: String) {
            SignalingServer.stringToJson(message)?.let {
                socketEvents.onMessage(it)
            }
        }

        override fun onClose(code: Int, reason: String, remote: Boolean) {
            socketEvents.onClose(code, reason, remote)
        }

        override fun onError(ex: Exception) {
            socketEvents.onError(ex)
        }
    }

    private val binder = WebSocketBinder()
    private var server: SignalingServer? = null
    private var localIpAddress = getLocalIpAddress()
    private var nsdManager: NsdManager? = null
    private var nsdEvents: NsdEvents? = null
    private var socketClient: SocketClient? = null
    private val executor = Executors.newSingleThreadExecutor()

    private fun getLocalIpAddress(): String? {
        try {
            val en: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val `interface`: NetworkInterface = en.nextElement()
                val enumIpAddress: Enumeration<InetAddress> = `interface`.inetAddresses
                while (enumIpAddress.hasMoreElements()) {
                    val inetAddress: InetAddress = enumIpAddress.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.getHostAddress()
                    }
                }
            }
        } catch (ex: SocketException) {
            ex.printStackTrace()
        }
        return null
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private val registrationListener = object : NsdManager.RegistrationListener {

        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
            // Save the service name. Android may have changed it in order to
            // resolve a conflict, so update the name you initially requested
            // with the name Android actually used.
            Log.d(TAG, "Service Registered. $NsdServiceInfo")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Registration failed! Put debugging code here to determine why.
            Log.e(TAG, "Service Registration Failed.")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            // Service has been unregistered. This only happens when you call
            // NsdManager.unregisterService() and pass in this listener.
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Unregistration failed. Put debugging code here to determine why.
        }
    }

    private fun unregisterNsdService() {
        nsdManager?.apply {
            unregisterService(registrationListener)
            stopServiceDiscovery(discoveryListener)
        }
    }

    private fun registerNsdService(name: String, portNumber: Int) {
        // Create the NsdServiceInfo object, and populate it.
        val serviceInfo = NsdServiceInfo().apply {
            // The name is subject to change based on conflicts
            // with other services advertised on the same network.
            serviceName = name
            serviceType = SERVICE_TYPE
            port = portNumber
        }

        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }
        initializeDiscoveryListener()
    }

    private inner class ResolveListener : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            // Called when the resolve fails. Use the error code to debug.
            Log.e(TAG, "Resolve failed, code: $errorCode $info")
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            Log.d(TAG, "Resolve Succeeded. $info")
            val host = info.host
            if (host.isLoopbackAddress || (host.hostAddress == localIpAddress)) {
                Log.d(TAG, "Resolve local service ${host.hostAddress}")
            } else {
                Log.d(TAG, "Resolve external service ${info.serviceName} on ${host.hostAddress}:${info.port}")
                nsdEvents?.onServiceResolved(info)
            }
        }
    }

    // Instantiate a new DiscoveryListener
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private fun initializeDiscoveryListener() {
        discoveryListener = object : NsdManager.DiscoveryListener {

            // Called as soon as service discovery begins.
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // A service was found! Do something with it.
                Log.d(TAG, "Service discovery success $service")
                if (service.serviceType.equals(SERVICE_TYPE)) {
                    executor.execute {
                        // It seems that generating instances of ResolveListener for each services is necessary.
                        // https://android.googlesource.com/platform/frameworks/base/+/e7369bd4dfa4fb3fdced5b52160a5d0209132292
                        // https://stackoverflow.com/questions/25815162/listener-already-in-use-service-discovery
                        val listener = ResolveListener()
                        nsdManager?.resolveService(service, listener)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // When the network service is no longer available.
                // Internal bookkeeping code goes here.
                Log.e(TAG, "service lost: $service")
                executor.execute {
                    nsdEvents?.onServiceLost(service)
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager?.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager?.stopServiceDiscovery(this)
            }
        }
    }

    companion object {
        private const val TAG = "RTCClientService"
        private const val SERVICE_TYPE = "_fax._tcp."
        private fun createURI(address: String): URI {
            val correctedAddress =
                address.replaceFirst("^http".toRegex(), "ws").replaceFirst("/$".toRegex(), "")
            return URI.create(correctedAddress)
        }
    }
}
