/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.addrtc

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.AdapterView.OnItemClickListener
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import org.json.JSONException
import org.json.JSONObject
import java.net.URISyntaxException

/**
 * Handles the initial setup where the user selects which room to join.
 */
class ConnectActivity : Activity(), ServiceConnection, SignalingServer.Events, WebSocketService.NsdEvents {
    private var webSocketService: WebSocketService.WebSocketBinder? = null
    private var roomListView: ListView? = null
    private var sharedPref: SharedPreferences? = null
    private var devicesList = mutableMapOf<String, String>()
    private var roomList: ArrayList<String?>? = null
    private var adapter: ArrayAdapter<String?>? = null
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Intent(this, WebSocketService::class.java).also { intent ->
            startService(intent)
        }

        // Get setting keys.
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        setContentView(R.layout.activity_connect)
        roomListView = findViewById(R.id.room_listview)
        roomListView?.emptyView = findViewById(android.R.id.empty)
        roomListView?.onItemClickListener = roomListClickListener
//        registerForContextMenu(roomListView)
        requestPermissions()
    }

    override fun onDestroy() {
        Intent(this, WebSocketService::class.java).also { intent ->
            stopService(intent)
        }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.connect_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle presses on the action bar items.
        return if (item.itemId == R.id.action_settings) {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            true
        } else if (item.itemId == R.id.action_loopback) {
            connectToRoom(null, null, false, true, false, 0)
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    public override fun onPause() {
        webSocketService?.stopDiscovery()
        super.onPause()
        unbindService(this)
    }

    public override fun onResume() {
        super.onResume()
        Intent(this, WebSocketService::class.java).also { intent ->
            bindService(intent, this, Context.BIND_AUTO_CREATE)
        }
        roomList = ArrayList()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, roomList!!)
        roomListView!!.adapter = adapter
        if (adapter!!.count > 0) {
            roomListView!!.requestFocus()
            roomListView!!.setItemChecked(0, true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (requestCode == CONNECTION_REQUEST && commandLineRun) {
            Log.d(TAG, "Return: $resultCode")
            setResult(resultCode)
            commandLineRun = false
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST) {
            val missingPermissions = missingPermissions
            if (missingPermissions.isNotEmpty()) {
                // User didn't grant all the permissions. Warn that the application might not work
                // correctly.
                AlertDialog.Builder(this)
                    .setMessage(R.string.missing_permissions_try_again)
                    .setPositiveButton(
                        R.string.yes
                    ) { dialog: DialogInterface, _: Int ->
                        // User wants to try giving the permissions again.
                        dialog.cancel()
                        requestPermissions()
                    }
                    .setNegativeButton(
                        R.string.no
                    ) { dialog: DialogInterface, _: Int ->
                        // User doesn't want to give the permissions.
                        dialog.cancel()
                        onPermissionsGranted()
                    }
                    .show()
            } else {
                // All permissions granted.
                onPermissionsGranted()
            }
        }
    }

    private fun onPermissionsGranted() {
        // If an implicit VIEW intent is launching the app, go directly to that URL.
        val intent = intent
        if ("android.intent.action.VIEW" == intent.action && !commandLineRun) {
            val loopback = intent.getBooleanExtra(CallActivity.EXTRA_LOOPBACK, false)
            val runTimeMs = intent.getIntExtra(CallActivity.EXTRA_RUNTIME, 0)
            val useValuesFromIntent =
                intent.getBooleanExtra(CallActivity.EXTRA_USE_VALUES_FROM_INTENT, false)
            connectToRoom(null, null, true, loopback, useValuesFromIntent, runTimeMs)
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Dynamic permissions are not required before Android M.
            onPermissionsGranted()
            return
        }
        val missingPermissions = missingPermissions
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions, PERMISSION_REQUEST)
        } else {
            onPermissionsGranted()
        }
    }

    @get:TargetApi(Build.VERSION_CODES.M)
    private val missingPermissions: Array<String?>
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return arrayOfNulls(0)
            }
            val info: PackageInfo = try {
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Failed to retrieve permissions.")
                return arrayOfNulls(0)
            }
            if (info.requestedPermissions == null) {
                Log.w(TAG, "No requested permissions.")
                return arrayOfNulls(0)
            }
            val missingPermissions = ArrayList<String?>()
            for (i in info.requestedPermissions.indices) {
                if (info.requestedPermissionsFlags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED == 0) {
                    missingPermissions.add(info.requestedPermissions[i])
                }
            }
            Log.d(TAG, "Missing permissions: $missingPermissions")
            return missingPermissions.toTypedArray()
        }

    private fun connectToRoom(
        offer: JSONObject?,
        address: String?,
        commandLineRun: Boolean, loopback: Boolean,
        useValuesFromIntent: Boolean, runTimeMs: Int
    ) {
        Companion.commandLineRun = commandLineRun
        // Start AppRTCMobile activity.
        try {
            val uri = address?.let {
                Uri.parse("http:/$address")
            }
            val roomId = address
            val intent = Intent(this, CallActivity::class.java)
            intent.data = uri
            if (offer != null) {
                intent.putExtra("offer", offer.toString())
            }
            intent.putExtra(CallActivity.EXTRA_ROOMID, roomId)
            intent.putExtra(CallActivity.EXTRA_LOOPBACK, loopback)
            intent.putExtra(CallActivity.EXTRA_CMDLINE, commandLineRun)
            intent.putExtra(CallActivity.EXTRA_RUNTIME, runTimeMs)
/*
            intent.putExtra(CallActivity.EXTRA_VIDEO_FILE_AS_CAMERA, videoFileAsCamera)
            intent.putExtra(
                CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE,
                saveRemoteVideoToFile
            )
            intent.putExtra(
                CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH,
                videoOutWidth
            )
            intent.putExtra(
                CallActivity.EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT,
                videoOutHeight
            )
*/
            startActivityForResult(intent, CONNECTION_REQUEST)
        } catch (e: URISyntaxException) {
            Log.d(TAG, "URI syntax error")
        }
    }

    private val roomListClickListener = OnItemClickListener { _, view, position, id ->
        val roomId = (view as TextView).text.toString()
        devicesList[roomId]?.let { device ->
            connectToRoom(null, device, false, false, false, 0)
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (service != null) {
            webSocketService = service as WebSocketService.WebSocketBinder
            val serverPort = sharedPref?.getString(
                getString(R.string.pref_room_server_port_key),
                getString(R.string.pref_room_server_port_default)
            )
            val serverPortNumber = serverPort?.toInt() ?: 8889
            val deviceName = sharedPref?.getString(getString(R.string.pref_devicename_key), getString(R.string.pref_devicename_default)) ?: getString(R.string.pref_devicename_default)
            webSocketService!!.startServer(this, serverPortNumber, deviceName, this)
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        webSocketService = null
    }

    override fun onOpen() {
        Log.d(TAG, "receive open connection")
    }

    override fun onClose(code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "receive close connection")
    }

    override fun onMessage(json: JSONObject) {
        try {
            val type = json.optString("type")
            if (type == "offer") {
                runOnUiThread {
                    connectToRoom(
                        json,
                        null,
                        commandLineRun = false,
                        loopback = false,
                        useValuesFromIntent = false,
                        runTimeMs = 0
                    )
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "JSON parsing error: $e")
        }
    }

    override fun onError(ex: Exception) {
        Log.e(TAG, "receive connection error")
    }

    override fun onServiceResolved(service: NsdServiceInfo) {
        val address = "${service.host}:${service.port}"
        devicesList[service.serviceName] = address
        val key = roomList?.find { it == service.serviceName }
        if (key == null) {
            roomList?.add(service.serviceName)
            runOnUiThread {
                adapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onServiceLost(service: NsdServiceInfo) {
        val name = service.serviceName
        devicesList.remove(name)
        roomList?.find { it == service.serviceName }?.let {
            roomList?.remove(it)
            runOnUiThread {
                adapter?.notifyDataSetChanged()
            }
        }
    }

    companion object {
        private const val TAG = "ConnectActivity"
        private const val CONNECTION_REQUEST = 1
        private const val PERMISSION_REQUEST = 2
        private var commandLineRun = false
    }
}