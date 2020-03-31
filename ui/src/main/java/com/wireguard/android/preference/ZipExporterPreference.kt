/*
 * Copyright © 2017-2019 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.util.Log
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.BiometricAuthenticator
import com.wireguard.android.util.DownloadsFileSaver
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.FragmentUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Preference implementing a button that asynchronously exports config zips.
 */
@ExperimentalCoroutinesApi
class ZipExporterPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs), CoroutineScope {
    private var exportedFilePath: String? = null
    override val coroutineContext
        get() = Job() + Dispatchers.Default

    private suspend fun exportZip(tunnels: List<ObservableTunnel>) = supervisorScope {
        val asyncConfigs = tunnels.map { it.getConfigAsync() }.toList()
        if (asyncConfigs.isEmpty()) {
            exportZipComplete(null, IllegalArgumentException(
                    context.getString(R.string.no_tunnels_error)))
            return@supervisorScope
        }
        try {
            asyncConfigs.awaitAll().let {
                val outputFile = DownloadsFileSaver.save(context, "wireguard-export.zip", "application/zip", true)
                try {
                    withContext(Dispatchers.IO) {
                        ZipOutputStream(outputFile.outputStream).use { zip ->
                            it.forEachIndexed { i, config ->
                                zip.putNextEntry(ZipEntry("${tunnels[i].name}.conf"))
                                zip.write(config.toWgQuickString().toByteArray(Charsets.UTF_8))
                            }
                            zip.closeEntry()
                        }
                    }
                } catch (e: Exception) {
                    outputFile.delete()
                    throw e
                }
                withContext(Dispatchers.Main) { exportZipComplete(outputFile.fileName, null) }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { exportZipComplete(null, e) }
        }
    }

    private fun exportZipComplete(filePath: String?, throwable: Throwable?) {
        if (throwable != null) {
            val error = ErrorMessages[throwable]
            val message = context.getString(R.string.zip_export_error, error)
            Log.e(TAG, message, throwable)
            Snackbar.make(
                    FragmentUtils.getPrefActivity(this).findViewById(android.R.id.content),
                    message, Snackbar.LENGTH_LONG).show()
            isEnabled = true
        } else {
            exportedFilePath = filePath
            notifyChanged()
        }
    }

    override fun getSummary() = if (exportedFilePath == null) context.getString(R.string.zip_export_summary) else context.getString(R.string.zip_export_success, exportedFilePath)

    override fun getTitle() = context.getString(R.string.zip_export_title)

    override fun onClick() {
        val prefActivity = FragmentUtils.getPrefActivity(this)
        val fragment = prefActivity.supportFragmentManager.fragments.first()
        BiometricAuthenticator.authenticate(R.string.biometric_prompt_zip_exporter_title, fragment) {
            when (it) {
                // When we have successful authentication, or when there is no biometric hardware available.
                is BiometricAuthenticator.Result.Success, is BiometricAuthenticator.Result.HardwareUnavailableOrDisabled -> {
                    prefActivity.ensurePermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { _, grantResults ->
                        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                            isEnabled = false
                            launch { exportZip(Application.getTunnelManager().getTunnelsAsync()) }
                        }
                    }
                }
                is BiometricAuthenticator.Result.Failure -> {
                    Snackbar.make(
                            prefActivity.findViewById(android.R.id.content),
                            it.message,
                            Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/ZipExporterPreference"
    }
}
