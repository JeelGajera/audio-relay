package com.audiorelay.app.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.audiorelay.app.state.DiscoveredLaptop

/**
 * Browses for `_audiorelay._udp` via Android's built-in NSD (mDNS)
 * wrapper — no extra library needed. See `docs/architecture.md` §3.2 and
 * `/protocol-spec.md` §2.
 *
 * **Unverified over an actual Android hotspot** — see `docs/roadmap.md`
 * Phase 0. NSD over a home router is the well-trodden path; multicast over
 * a phone-hosted hotspot is the case most likely to need follow-up work
 * (e.g. holding a `WifiManager.MulticastLock`, done in
 * `service/RelayService.kt`, not here).
 */
class NsdDiscovery(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val found = mutableMapOf<String, DiscoveredLaptop>()

    fun start(onUpdated: (List<DiscoveredLaptop>) -> Unit) {
        stop()
        found.clear()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "mDNS discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, ResolveListener(service.serviceName, onUpdated))
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
                onUpdated(found.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start: $errorCode")
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to stop: $errorCode")
            }
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    private inner class ResolveListener(
        private val serviceName: String,
        private val onUpdated: (List<DiscoveredLaptop>) -> Unit,
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "failed to resolve $serviceName: $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress ?: return
            val deviceId = serviceInfo.attributeString("id") ?: serviceName
            val name = serviceInfo.attributeString("name") ?: serviceName
            found[serviceName] = DiscoveredLaptop(deviceId, name, host, serviceInfo.port)
            onUpdated(found.values.toList())
        }
    }

    private fun NsdServiceInfo.attributeString(key: String): String? =
        attributes[key]?.toString(Charsets.UTF_8)

    companion object {
        private const val TAG = "NsdDiscovery"
        /** Must match `SERVICE_TYPE` in `windows-app/src/protocol/mod.rs`. */
        const val SERVICE_TYPE = "_audiorelay._udp."
    }
}
