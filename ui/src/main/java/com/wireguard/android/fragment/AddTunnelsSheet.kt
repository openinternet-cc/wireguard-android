/*
 * Copyright © 2020 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.zxing.integration.android.IntentIntegrator
import com.wireguard.android.R
import com.wireguard.android.activity.TunnelCreatorActivity
import com.wireguard.android.fragment.TunnelListFragment.Companion.FRAGMENT_RESULT_FILE_URI
import com.wireguard.android.fragment.TunnelListFragment.Companion.FRAGMENT_RESULT_KEY
import com.wireguard.android.util.resolveAttribute

class AddTunnelsSheet : BottomSheetDialogFragment() {

    private var behavior: BottomSheetBehavior<FrameLayout>? = null
    private val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onSlide(bottomSheet: View, slideOffset: Float) {
        }

        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                dismiss()
            }
        }
    }

    override fun getTheme(): Int {
        return R.style.BottomSheetDialogTheme
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        if (savedInstanceState != null) dismiss()
        return inflater.inflate(R.layout.add_tunnels_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val dialog = dialog as BottomSheetDialog? ?: return
                behavior = dialog.behavior
                behavior?.apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    peekHeight = 0
                    addBottomSheetCallback(bottomSheetCallback)
                }
                dialog.findViewById<View>(R.id.create_empty)?.setOnClickListener {
                    dismiss()
                    startActivity(Intent(activity, TunnelCreatorActivity::class.java))
                }
                dialog.findViewById<View>(R.id.create_from_file)?.setOnClickListener {
                    dismiss()
                    registerForActivityResult(GetContent()) { uri ->
                        setFragmentResult(FRAGMENT_RESULT_KEY, bundleOf(FRAGMENT_RESULT_FILE_URI to uri))
                    }.launch("*/*")
                }
                dialog.findViewById<View>(R.id.create_from_qrcode)?.setOnClickListener {
                    dismiss()
                    val intent = IntentIntegrator.forSupportFragment(this@AddTunnelsSheet).apply {
                        setOrientationLocked(false)
                        setBeepEnabled(false)
                        setPrompt(getString(R.string.qr_code_hint))
                        setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    }.createScanIntent()
                    registerForActivityResult(StartActivityForResult()) { result ->
                        setFragmentResult(FRAGMENT_RESULT_KEY, bundleOf(FRAGMENT_RESULT_FILE_URI to result.data))
                    }.launch(intent)
                }
            }
        })
        val gradientDrawable = GradientDrawable().apply {
            setColor(requireContext().resolveAttribute(R.attr.colorBackground))
        }
        view.background = gradientDrawable
    }

    override fun dismiss() {
        super.dismiss()
        behavior?.removeBottomSheetCallback(bottomSheetCallback)
    }
}
