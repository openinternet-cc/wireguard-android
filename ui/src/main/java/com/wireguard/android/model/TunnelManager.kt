/*
 * Copyright © 2017-2019 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.Application.Companion.get
import com.wireguard.android.Application.Companion.getAsyncWorker
import com.wireguard.android.Application.Companion.getBackend
import com.wireguard.android.Application.Companion.getSharedPreferences
import com.wireguard.android.Application.Companion.getTunnelManager
import com.wireguard.android.BR
import com.wireguard.android.R
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.configStore.ConfigStore
import com.wireguard.android.databinding.ObservableSortedKeyedArrayList
import com.wireguard.android.util.ExceptionLoggers
import com.wireguard.config.Config
import java9.util.concurrent.CompletableFuture
import java9.util.concurrent.CompletionStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList

/**
 * Maintains and mediates changes to the set of available WireGuard tunnels,
 */
class TunnelManager(private val configStore: ConfigStore) : BaseObservable() {
    val tunnels = CompletableFuture<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private val tunnelsAsync = CompletableDeferred<ObservableSortedKeyedArrayList<String, ObservableTunnel>>()
    private val context: Context = get()
    private val delayedLoadRestoreTunnels = ArrayList<CompletableFuture<Void>>()
    private val tunnelMap: ObservableSortedKeyedArrayList<String, ObservableTunnel> = ObservableSortedKeyedArrayList(TunnelComparator)
    private var haveLoaded = false

    private fun addToList(name: String, config: Config?, state: Tunnel.State): ObservableTunnel {
        val tunnel = ObservableTunnel(this, name, config, state)
        tunnelMap.add(tunnel)
        return tunnel
    }

    suspend fun create(name: String, config: Config?): ObservableTunnel {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        val newConfig = configStore.create(name, config!!)
        return withContext(Dispatchers.Main) { addToList(name, newConfig, Tunnel.State.DOWN) }
    }

    suspend fun delete(tunnel: ObservableTunnel) {
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        withContext(Dispatchers.Default) {
            if (originalState == Tunnel.State.UP)
                getBackend().setState(tunnel, Tunnel.State.DOWN, null)
            try {
                configStore.delete(tunnel.name)
            } catch (e: Exception) {
                if (originalState == Tunnel.State.UP)
                    getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config)
                tunnelMap.add(tunnel)
                if (wasLastUsed)
                    lastUsedTunnel = tunnel
                throw e
            }
        }
    }

    @get:Bindable
    @SuppressLint("ApplySharedPref")
    var lastUsedTunnel: ObservableTunnel? = null
        private set(value) {
            if (value == field) return
            field = value
            notifyPropertyChanged(BR.lastUsedTunnel)
            if (value != null)
                getSharedPreferences().edit().putString(KEY_LAST_USED_TUNNEL, value.name).commit()
            else
                getSharedPreferences().edit().remove(KEY_LAST_USED_TUNNEL).commit()
        }

    suspend fun getTunnelsAsync() = tunnelsAsync.await()

    suspend fun getTunnelConfig(tunnel: ObservableTunnel): Config {
        val config = withContext(Dispatchers.IO) {
            configStore.load(tunnel.name)
        }
        tunnel.onConfigChanged(config)
        return config
    }

    suspend fun onCreate() = withContext(Dispatchers.IO) {
        val storedConfigs = async { configStore.enumerate() }
        val runningTunnels = async { getBackend().runningTunnelNames }
        onTunnelsLoaded(storedConfigs.await(), runningTunnels.await())
    }

    private fun onTunnelsLoaded(present: Iterable<String>, running: Collection<String>) {
        for (name in present)
            addToList(name, null, if (running.contains(name)) Tunnel.State.UP else Tunnel.State.DOWN)
        val lastUsedName = getSharedPreferences().getString(KEY_LAST_USED_TUNNEL, null)
        if (lastUsedName != null)
            lastUsedTunnel = tunnelMap[lastUsedName]
        var toComplete: Array<CompletableFuture<Void>>
        synchronized(delayedLoadRestoreTunnels) {
            haveLoaded = true
            toComplete = delayedLoadRestoreTunnels.toTypedArray()
            delayedLoadRestoreTunnels.clear()
        }
        restoreState(true).whenComplete { v: Void?, t: Throwable? ->
            for (f in toComplete) {
                if (t == null)
                    f.complete(v)
                else
                    f.completeExceptionally(t)
            }
        }
        tunnels.complete(tunnelMap)
        tunnelsAsync.complete(tunnelMap)
    }

    fun refreshTunnelStates() {
        getAsyncWorker().supplyAsync { getBackend().runningTunnelNames }
                .thenAccept { running: Set<String> -> for (tunnel in tunnelMap) tunnel.onStateChanged(if (running.contains(tunnel.name)) Tunnel.State.UP else Tunnel.State.DOWN) }
                .whenComplete(ExceptionLoggers.E)
    }

    fun restoreState(force: Boolean): CompletionStage<Void> {
        if (!force && !getSharedPreferences().getBoolean(KEY_RESTORE_ON_BOOT, false))
            return CompletableFuture.completedFuture(null)
        synchronized(delayedLoadRestoreTunnels) {
            if (!haveLoaded) {
                val f = CompletableFuture<Void>()
                delayedLoadRestoreTunnels.add(f)
                return f
            }
        }
        val previouslyRunning = getSharedPreferences().getStringSet(KEY_RUNNING_TUNNELS, null)
                ?: return CompletableFuture.completedFuture(null)
        return CompletableFuture.allOf(*tunnelMap.filter { previouslyRunning.contains(it.name) }.map { setTunnelState(it, Tunnel.State.UP).toCompletableFuture() }.toTypedArray())
    }

    @SuppressLint("ApplySharedPref")
    fun saveState() {
        getSharedPreferences().edit().putStringSet(KEY_RUNNING_TUNNELS, tunnelMap.filter { it.state == Tunnel.State.UP }.map { it.name }.toSet()).commit()
    }

    suspend fun setTunnelConfig(tunnel: ObservableTunnel, config: Config): Config {
        getBackend().setState(tunnel, tunnel.state, config)
        val conf = configStore.save(tunnel.name, config)
        tunnel.onConfigChanged(conf)
        return conf
    }

    suspend fun setTunnelName(tunnel: ObservableTunnel, name: String): String {
        if (Tunnel.isNameInvalid(name))
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_invalid_name))
        if (tunnelMap.containsKey(name)) {
            throw IllegalArgumentException(context.getString(R.string.tunnel_error_already_exists, name))
        }
        val originalState = tunnel.state
        val wasLastUsed = tunnel == lastUsedTunnel
        // Make sure nothing touches the tunnel.
        if (wasLastUsed)
            lastUsedTunnel = null
        tunnelMap.remove(tunnel)
        val newName = try {
            if (originalState == Tunnel.State.UP)
                getBackend().setState(tunnel, Tunnel.State.DOWN, null)
            configStore.rename(tunnel.name, name)
            val newName = tunnel.onNameChanged(name)
            if (originalState == Tunnel.State.UP)
                getBackend().setState(tunnel, Tunnel.State.UP, tunnel.config)
            // Add the tunnel back to the manager, under whatever name it thinks it has.
            tunnelMap.add(tunnel)
            if (wasLastUsed)
                lastUsedTunnel = tunnel
            newName
        } catch (e: Exception) {
            // On failure, we don't know what state the tunnel might be in. Fix that.
            getTunnelState(tunnel)
            null
        }
        return newName!!
    }

    suspend fun setTunnelState(tunnel: ObservableTunnel, state: Tunnel.State): Tunnel.State {
        tunnel.getConfigAsync()
                .apply {
                    val newState = try {
                        getBackend().setState(tunnel, state, this)
                    } catch (e: Exception){
                        tunnel.state
                    }
                    tunnel.onStateChanged(newState)
                    if (newState == Tunnel.State.UP) {
                        lastUsedTunnel = tunnel
                    }
                    saveState()
                    return newState
                }
    }

    class IntentReceiver : BroadcastReceiver(), CoroutineScope {
        override val coroutineContext
            get() = Job() + Dispatchers.Default
        override fun onReceive(context: Context, intent: Intent?) {
            val manager = getTunnelManager()
            if (intent == null) return
            val action = intent.action ?: return
            if ("com.wireguard.android.action.REFRESH_TUNNEL_STATES" == action) {
                manager.refreshTunnelStates()
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !getSharedPreferences().getBoolean("allow_remote_control_intents", false))
                return
            val state: Tunnel.State
            state = when (action) {
                "com.wireguard.android.action.SET_TUNNEL_UP" -> Tunnel.State.UP
                "com.wireguard.android.action.SET_TUNNEL_DOWN" -> Tunnel.State.DOWN
                else -> return
            }
            val tunnelName = intent.getStringExtra("tunnel") ?: return
            launch {
                manager.getTunnelsAsync().let {
                    val tunnel = it[tunnelName] ?: return@launch
                    manager.setTunnelState(tunnel, state)
                }
            }
        }
    }

    suspend fun getTunnelState(tunnel: ObservableTunnel): Tunnel.State {
        val state = withContext(Dispatchers.IO) { getBackend().getState(tunnel) }
        tunnel.onStateChanged(state)
        return state
    }

    suspend fun getTunnelStatistics(tunnel: ObservableTunnel): Statistics {
        val statistics = withContext(Dispatchers.IO) { getBackend().getStatistics(tunnel) }
        tunnel.onStatisticsChanged(statistics)
        return statistics
    }

    companion object {
        private const val KEY_LAST_USED_TUNNEL = "last_used_tunnel"
        private const val KEY_RESTORE_ON_BOOT = "restore_on_boot"
        private const val KEY_RUNNING_TUNNELS = "enabled_configs"
    }
}
