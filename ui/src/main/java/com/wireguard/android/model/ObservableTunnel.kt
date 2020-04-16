/*
 * Copyright © 2017-2019 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.model

import androidx.databinding.BaseObservable
import androidx.databinding.Bindable
import com.wireguard.android.Application
import com.wireguard.android.BR
import com.wireguard.android.backend.Statistics
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.Keyed
import com.wireguard.android.util.ExceptionLoggers
import com.wireguard.config.Config
import java9.util.concurrent.CompletableFuture
import java9.util.concurrent.CompletionStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Encapsulates the volatile and nonvolatile state of a WireGuard tunnel.
 */
class ObservableTunnel internal constructor(
        private val manager: TunnelManager,
        private var name: String,
        config: Config?,
        state: Tunnel.State
) : BaseObservable(), Keyed<String>, Tunnel {
    override val key
        get() = name

    @Bindable
    override fun getName() = name

    suspend fun setNameAsync(name: String): String = if (name != this.name)
        manager.setTunnelName(this, name)
    else
        this.name

    fun onNameChanged(name: String): String {
        this.name = name
        notifyPropertyChanged(BR.name)
        return name
    }


    @get:Bindable
    var state = state
        private set

    override fun onStateChange(newState: Tunnel.State) {
        onStateChanged(newState)
    }

    fun onStateChanged(state: Tunnel.State): Tunnel.State {
        if (state != Tunnel.State.UP) onStatisticsChanged(null)
        this.state = state
        notifyPropertyChanged(BR.state)
        return state
    }

    suspend fun setStateAsync(state: Tunnel.State): Tunnel.State = if (state != this.state)
        manager.setTunnelState(this, state)
    else
        this.state


    @get:Bindable
    var config = config
        get() {
            if (field == null)
                Application.SingletonContext.launch { manager.getTunnelConfig(this@ObservableTunnel) }
            return field
        }
        private set

    suspend fun getConfigAsync(): Config {
        return if (config == null)
            manager.getTunnelConfig(this)
        else
            config!!
    }

    suspend fun setConfigAsync(config: Config): Config = if (config != this.config)
        manager.setTunnelConfig(this, config)
    else
        this.config!!

    fun onConfigChanged(config: Config?): Config? {
        this.config = config
        notifyPropertyChanged(BR.config)
        return config
    }


    @get:Bindable
    var statistics: Statistics? = null
        get() {
            if (field == null || field!!.isStale)
                manager.getTunnelStatistics(this).whenComplete(ExceptionLoggers.E)
            return field
        }
        private set

    val statisticsAsync: CompletionStage<Statistics>
        get() = if (statistics == null || statistics!!.isStale)
            manager.getTunnelStatistics(this)
        else
            CompletableFuture.completedFuture(statistics)

    fun onStatisticsChanged(statistics: Statistics?): Statistics? {
        this.statistics = statistics
        notifyPropertyChanged(BR.statistics)
        return statistics
    }


    suspend fun delete() = manager.delete(this)
}
