/*
 * Copyright © 2020 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.configStore

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import com.wireguard.android.R
import com.wireguard.config.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Configuration store that uses a `wg-quick`-style file for each configured tunnel. It differs from
 * [FileConfigStore] in the sense that it also encrypts the files at rest rather than storing them
 * in cleartext. This uses [EncryptedFile] from AndroidX `security-crypto` which is only supported
 * above API 23.
 */
@RequiresApi(Build.VERSION_CODES.M)
class EncryptedFileConfigStore(private val context: Context) : ConfigStore {
    private val keyGenParameterSpec = MasterKeys.AES256_GCM_SPEC
    private val masterKeyAlias = MasterKeys.getOrCreate(keyGenParameterSpec)
    private val encryptedConfigsDir = File(context.filesDir, "encrypted_store")

    // Cheap migration strategy: If unencrypted configs already exist, move them to our config dir
    // while encrypting them on the fly.
    init {
        if (!encryptedConfigsDir.exists() && !encryptedConfigsDir.mkdir())
            // TODO(msfjarvis): i18n
            throw IOException("Failed to create directory")
        val cleartextConfigs = context.fileList()
                .filter { it.endsWith(".conf") }
                .map { it.substring(0, it.length - ".conf".length) }
                .map { File(context.filesDir, "$it.conf") }
                .toSet()
        if (cleartextConfigs.isNotEmpty()) {
            cleartextConfigs.forEach { cleartextConfigFile ->
                val replacement = encryptedFileFor(cleartextConfigFile.nameWithoutExtension)
                replacement.openFileOutput().write(cleartextConfigFile.readBytes())
                cleartextConfigFile.delete()
            }
        }
    }

    override fun create(name: String, config: Config): Config {
        Log.d(TAG, "Creating configuration for tunnel $name")
        encryptedFileFor(name).openFileOutput().write(config.toWgQuickString().toByteArray(Charsets.UTF_8))
        return config
    }

    override fun delete(name: String) {
        Log.d(TAG, "Deleting configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.delete())
            throw IOException(context.getString(R.string.config_delete_error, file.name))
    }

    override fun enumerate(): Set<String> {
        return (encryptedConfigsDir.list() ?: emptyArray<String>())
                .filter { it.endsWith(".conf") }
                .map { it.substring(0, it.length - ".conf".length) }
                .toSet()
    }

    override fun load(name: String): Config {
        return Config.parse(encryptedFileFor(name).openFileInput().bufferedReader(Charsets.UTF_8))
    }

    override fun rename(name: String, replacement: String) {
        Log.d(TAG, "Renaming configuration for tunnel $name to $replacement")
        val file = fileFor(name)
        val replacementFile = fileFor(replacement)
        if (!replacementFile.createNewFile()) throw IOException(context.getString(R.string.config_exists_error, replacement))
        if (!file.renameTo(replacementFile)) {
            if (!replacementFile.delete()) Log.w(TAG, "Couldn't delete marker file for new name $replacement")
            throw IOException(context.getString(R.string.config_rename_error, file.name))
        }
    }

    override fun save(name: String, config: Config): Config {
        Log.d(TAG, "Saving configuration for tunnel $name")
        val file = fileFor(name)
        if (!file.isFile)
            throw FileNotFoundException(context.getString(R.string.config_not_found_error, file.name))
        encryptedFileFor(name).openFileOutput().write(config.toWgQuickString().toByteArray(Charsets.UTF_8))
        return config
    }

    private fun fileFor(name: String): File {
        return File(encryptedConfigsDir, "$name.conf")
    }

    private fun encryptedFileFor(name: String) = EncryptedFile.Builder(
            fileFor(name),
            context,
            masterKeyAlias,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
    ).build()

    companion object {
        private const val TAG = "WireGuard/EncryptedFileConfigStore"
    }
}
