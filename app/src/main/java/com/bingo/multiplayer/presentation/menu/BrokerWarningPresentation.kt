package com.bingo.multiplayer.presentation.menu

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bingo.multiplayer.domain.network.AppLifecycleObserver
import com.bingo.multiplayer.domain.network.EmqxBrokerMonitor
import com.bingo.multiplayer.domain.network.brokerWarningVisibility
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun rememberBrokerWarningVisible(): Boolean {
    val context = LocalContext.current.applicationContext
    val visibility = remember(context) {
        brokerWarningVisibility(internetAvailability(context),
            AppLifecycleObserver.isAppInForeground, EmqxBrokerMonitor.isBrokerDown)
    }
    val visible by visibility.collectAsState(initial = false)
    return visible
}

/** Observe Android connectivity without adding HTTP probes or changing the MQTT connection. */
private fun internetAvailability(context: Context): Flow<Boolean> = callbackFlow {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (manager == null) {
        trySend(false)
        awaitClose {}
        return@callbackFlow
    }
    fun NetworkCapabilities?.hasInternet() = this?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true

    var current = manager.activeNetwork
    var validated = current?.let(manager::getNetworkCapabilities).hasInternet()
    var blocked = false
    fun publishAvailability() { trySend(validated && !blocked) }
    publishAvailability()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            current = network
            validated = false
            blocked = false
            // Wait for this network's capabilities, rather than reuse the previous network's state.
            publishAvailability()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == current) {
                validated = capabilities.hasInternet()
                publishAvailability()
            }
        }
        override fun onBlockedStatusChanged(network: Network, isBlocked: Boolean) {
            if (network == current) {
                blocked = isBlocked
                publishAvailability()
            }
        }
        override fun onLost(network: Network) {
            if (network == current) {
                current = null
                validated = false
                publishAvailability()
            }
        }
    }
    val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
    if (!registered) trySend(false)
    awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
}.conflate().distinctUntilChanged()
