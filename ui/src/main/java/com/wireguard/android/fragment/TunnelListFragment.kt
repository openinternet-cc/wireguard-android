/*
 * Copyright © 2017-2019 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.integration.android.IntentIntegrator
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.fragment.ConfigNamingDialogFragment.Companion.newInstance
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.widget.EdgeToEdge.setUpFAB
import com.wireguard.android.widget.EdgeToEdge.setUpRoot
import com.wireguard.android.widget.EdgeToEdge.setUpScrollingContent
import com.wireguard.android.widget.MultiselectableRelativeLayout
import com.wireguard.config.Config
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.HashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Fragment containing a list of known WireGuard tunnels. It allows creating and deleting tunnels.
 */
class TunnelListFragment : BaseFragment() {
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var binding: TunnelListFragmentBinding? = null
    private val coroutineScope
        get() = viewLifecycleOwner.lifecycleScope
    private fun importTunnel(configText: String) {
        try {
            // Ensure the config text is parseable before proceeding…
            Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))

            // Config text is valid, now create the tunnel…
            newInstance(configText).show(parentFragmentManager, null)
        } catch (e: Exception) {
            onTunnelImportFinished(emptyList(), listOf<Throwable>(e))
        }
    }

    private suspend fun importTunnel(uri: Uri?) {
        val activity = activity
        if (activity == null || uri == null) {
            return
        }
        val contentResolver = activity.contentResolver

        val deferredTunnels = ArrayList<Deferred<ObservableTunnel>>()
        val throwables = ArrayList<Throwable>()
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME)
        var name = ""
        contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0)
            }
        }
        if (name.isEmpty()) {
            name = Uri.decode(uri.lastPathSegment)
        }
        var idx = name.lastIndexOf('/')
        if (idx >= 0) {
            require(idx < name.length - 1) { resources.getString(R.string.illegal_filename_error, name) }
            name = name.substring(idx + 1)
        }
        val isZip = name.toLowerCase(Locale.ROOT).endsWith(".zip")
        if (name.toLowerCase(Locale.ROOT).endsWith(".conf")) {
            name = name.substring(0, name.length - ".conf".length)
        } else {
            require(isZip) { resources.getString(R.string.bad_extension_error) }
        }

        withContext(Dispatchers.IO) {
            supervisorScope {
                if (isZip) {
                    ZipInputStream(contentResolver.openInputStream(uri)).use { zip ->
                        val reader = BufferedReader(InputStreamReader(zip, StandardCharsets.UTF_8))
                        var entry: ZipEntry?
                        while (true) {
                            entry = zip.nextEntry ?: break
                            name = entry.name
                            idx = name.lastIndexOf('/')
                            if (idx >= 0) {
                                if (idx >= name.length - 1) {
                                    continue
                                }
                                name = name.substring(name.lastIndexOf('/') + 1)
                            }
                            if (name.toLowerCase(Locale.ROOT).endsWith(".conf")) {
                                name = name.substring(0, name.length - ".conf".length)
                            } else {
                                continue
                            }
                            try {
                                Config.parse(reader)
                            } catch (e: Exception) {
                                throwables.add(e)
                                null
                            }?.let {
                                deferredTunnels.add(async {
                                    Application.getTunnelManager().createAsync(name, it)
                                })
                            }
                        }
                    }
                } else {
                    deferredTunnels.add(async {
                        Application.getTunnelManager().createAsync(name, Config.parse(contentResolver.openInputStream(uri)!!))
                    })
                }
            }
        }

        if (deferredTunnels.isEmpty()) {
            if (throwables.size == 1) {
                throw throwables[0]
            } else {
                require(throwables.isNotEmpty()) { resources.getString(R.string.no_configs_error) }
            }
        }

        try {
            val tunnels = mutableListOf<ObservableTunnel>()
            deferredTunnels.forEach {
                try {
                    tunnels.add(it.await())
                } catch (e: Exception) {
                    throwables.add(e)
                }
            }
            onTunnelImportFinished(tunnels, throwables)
        } catch (e: Exception) {
            onTunnelImportFinished(emptyList(), listOf(e))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (i in checkedItems) actionModeListener.setItemChecked(i, true)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_IMPORT -> {
                if (resultCode == Activity.RESULT_OK && data != null) coroutineScope.launch { importTunnel(data.data) }
                return
            }
            IntentIntegrator.REQUEST_CODE -> {
                val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
                if (result != null && result.contents != null) {
                    importTunnel(result.contents)
                }
                return
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        binding?.apply {
            createFab.setOnClickListener {
                val bottomSheet = AddTunnelsSheet()
                bottomSheet.setTargetFragment(fragment, REQUEST_TARGET_FRAGMENT)
                bottomSheet.show(parentFragmentManager, "BOTTOM_SHEET")
            }
            executePendingBindings()
            setUpRoot(root as ViewGroup)
            setUpFAB(createFab)
            setUpScrollingContent(tunnelList, createFab)
        }
        return binding!!.root
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        Application.getTunnelManager().tunnels.thenAccept { tunnels ->
            if (newTunnel != null) viewForTunnel(newTunnel, tunnels).setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, tunnels).setSingleSelected(false)
        }
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        if (throwable == null) {
            message = resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    private fun onTunnelImportFinished(tunnels: List<ObservableTunnel>, throwables: Collection<Throwable>) {
        var message = ""
        for (throwable in throwables) {
            val error = ErrorMessages[throwable]
            message = getString(R.string.import_error, error)
            Log.e(TAG, message, throwable)
        }
        if (tunnels.size == 1 && throwables.isEmpty())
            message = getString(R.string.import_success, tunnels[0].name)
        else if (tunnels.isEmpty() && throwables.size == 1)
        else if (throwables.isEmpty())
            message = resources.getQuantityString(R.plurals.import_total_success,
                    tunnels.size, tunnels.size)
        else if (!throwables.isEmpty())
            message = resources.getQuantityString(R.plurals.import_partial_success,
                    tunnels.size + throwables.size,
                    tunnels.size, tunnels.size + throwables.size)
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        Application.getTunnelManager().tunnels.thenAccept { tunnels -> binding!!.tunnels = tunnels }
        val parent = this
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = parent
                binding.root.setOnClickListener {
                    if (actionMode == null) {
                        selectedTunnel = item
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                }
                binding.root.setOnLongClickListener {
                    actionModeListener.toggleItemChecked(position)
                    true
                }
                if (actionMode != null)
                    (binding.root as MultiselectableRelativeLayout).setMultiSelected(actionModeListener.checkedItems.contains(position))
                else
                    (binding.root as MultiselectableRelativeLayout).setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        binding?.let {
            Snackbar.make(it.mainContainer, message, Snackbar.LENGTH_LONG)
                    .setAnchorView(it.createFab)
                    .show()
        }
    }

    private fun viewForTunnel(tunnel: ObservableTunnel, tunnels: List<*>): MultiselectableRelativeLayout {
        return binding!!.tunnelList.findViewHolderForAdapterPosition(tunnels.indexOf(tunnel))!!.itemView as MultiselectableRelativeLayout
    }

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedItems: MutableCollection<Int> = HashSet()
        private var resources: Resources? = null
        private var initialFabTranslation = 0f

        fun getCheckedItems(): ArrayList<Int> {
            return ArrayList(checkedItems)
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val copyCheckedItems = HashSet(checkedItems)
                    binding?.createFab?.translationY = initialFabTranslation
                    Application.getTunnelManager().tunnels.thenAccept { tunnels ->
                        val tunnelsToDelete = ArrayList<ObservableTunnel>()
                        for (position in copyCheckedItems) tunnelsToDelete.add(tunnels[position])
                        coroutineScope.launch {
                            val exception = try {
                                tunnelsToDelete.map { async { it.delete() } }.toList().awaitAll()
                                null
                            } catch (e: Exception) {
                                e
                            }
                            onTunnelDeletionFinished(tunnelsToDelete.size, exception)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }
                R.id.menu_action_select_all -> {
                    Application.getTunnelManager().tunnels.thenAccept { tunnels ->
                        for (i in 0 until tunnels.size) {
                            setItemChecked(i, true)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            if (activity != null) {
                resources = activity!!.resources
            }
            binding?.createFab?.let {
                initialFabTranslation = it.translationY
                ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, 400f).apply {
                    duration = 400
                    start()
                }
            }
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            resources = null
            binding?.createFab?.let {
                if (it.translationY != initialFabTranslation) {
                    ObjectAnimator.ofFloat(it, View.TRANSLATION_Y, initialFabTranslation).apply {
                        duration = 400
                        start()
                    }
                }
            }

            checkedItems.clear()
            binding!!.tunnelList.adapter!!.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(position: Int, checked: Boolean) {
            if (checked) {
                checkedItems.add(position)
            } else {
                checkedItems.remove(position)
            }
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && !checkedItems.isEmpty() && activity != null) {
                (activity as AppCompatActivity?)!!.startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            adapter?.notifyItemChanged(position)
            updateTitle(actionMode)
        }

        fun toggleItemChecked(position: Int) {
            setItemChecked(position, !checkedItems.contains(position))
        }

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) {
                return
            }
            val count = checkedItems.size
            if (count == 0) {
                mode.title = ""
            } else {
                mode.title = resources!!.getQuantityString(R.plurals.delete_title, count, count)
            }
        }
    }

    companion object {
        const val REQUEST_IMPORT = 1
        private const val REQUEST_TARGET_FRAGMENT = 2
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "WireGuard/TunnelListFragment"
    }
}
