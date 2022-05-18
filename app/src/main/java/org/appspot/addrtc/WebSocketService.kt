package org.appspot.addrtc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import org.java_websocket.WebSocket
import java.util.concurrent.Executors
import java.util.concurrent.Future


class WebSocketService : Service() {
    inner class WebSocketBinder : Binder() {
        private var portNumber = 8888
        private var deviceName = "MyDevice"
        fun startServer(events: SignalingServer.Events, port: Int, name: String) {
            portNumber = port
            deviceName = name
            server?.stop()
            server = SignalingServer(events, port).apply {
                start()
                startDiscovery()
            }
        }

        fun updateEvents(events: SignalingServer.Events?) {
            server?.updateEvents(events)
        }

        fun connections() : Collection<WebSocket>? {
            return server?.connections
        }

        fun broadcast(message: String) {
            server?.broadcast(message)
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

    private val binder = WebSocketBinder()
    private var server: SignalingServer? = null
    private var localIpAddress: String? = null
    private val roomId: String? = null
    private var nsdManager: NsdManager? = null
    private val executor = Executors.newSingleThreadExecutor()

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
                if (host.isSiteLocalAddress) {
                    Log.d(TAG, "site local")
                }
                Log.d(TAG, "Resolve external service ${info.serviceName} on ${host.hostAddress}:${info.port}")
/*                val roomId = roomId
                if (upper.signalingClient?.isOpen != true) {
                    upper.signalingClient = WsClient(upper, "http://${host.hostAddress}:${info.port}", roomId)
                    upper.signalingClient?.connect()
                }
 */
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
//            if (service.serviceType.contains(SERVICE_TYPE) and service.serviceName.contains(SERVICE_NAME)) {
//                this@WsRTCClient.executor.execute {
//                executor.execute {
                    // サービスが見つかる毎にそれぞれ別インスタンスのResolveListenerを渡す必要があるらしい
                    // https://android.googlesource.com/platform/frameworks/base/+/e7369bd4dfa4fb3fdced5b52160a5d0209132292
                    // https://stackoverflow.com/questions/25815162/listener-already-in-use-service-discovery
                    val listener = ResolveListener()
                    nsdManager?.resolveService(service, listener)
//                }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // When the network service is no longer available.
                // Internal bookkeeping code goes here.
                Log.e(TAG, "service lost: $service")
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
    }
}