/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.ui.fragments.dialogs

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import org.teslasoft.assistant.R
import java.io.File

/**
 * Image Detail and View Usage bottom sheet (profile-images-plan.md, IMAGE
 * DETAIL AND VIEW USAGE): a large plain-square preview, whether the file is
 * Missing, when it was added, and either its usage (in-use) or a Delete
 * Permanently action (unused). All content arrives pre-formatted through
 * arguments - this fragment renders, it does not compute usage or dates.
 *
 * The delete callback goes through the host Activity (must implement
 * [Listener]) rather than a settable field, so it survives fragment
 * recreation instead of being lost as a transient listener would be.
 */
class ProfileImageDetailBottomSheetDialogFragment : BottomSheetDialogFragment() {

    interface Listener {
        /** The user tapped Delete Permanently on this (unused) image. The
         *  host is responsible for the confirmation dialog and the actual
         *  delete - this fragment only reports the request and dismisses. */
        fun onProfileImageDeletePermanentlyRequested(hash: String)
    }

    companion object {
        private const val ARG_HASH = "hash"
        private const val ARG_FILE_PATH = "file_path"
        private const val ARG_CORRUPTED = "corrupted"
        private const val ARG_DATE_ADDED_LINE = "date_added_line"
        private const val ARG_USED = "used"
        private const val ARG_USAGE_TOTAL_LINE = "usage_total_line"
        private const val ARG_IDENTITY_LINES = "identity_lines"

        fun newInstance(
            hash: String,
            /** Absolute path of the permanent file, or null for a Missing record. */
            filePath: String?,
            /** True when [filePath] is non-null but its content will not decode
             *  as an image (Corrupted - distinct from Missing). Ignored when
             *  [filePath] is null. */
            corrupted: Boolean,
            /** Already formatted, e.g. "Date Added: July 5, 2026". */
            dateAddedLine: String,
            used: Boolean,
            /** Already formatted, e.g. "Used by 3 profiles". Ignored when !used. */
            usageTotalLine: String?,
            /** Already formatted lines, e.g. "Companion: Ash". Ignored when !used. */
            identityLines: ArrayList<String>
        ): ProfileImageDetailBottomSheetDialogFragment {
            val fragment = ProfileImageDetailBottomSheetDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_HASH, hash)
                putString(ARG_FILE_PATH, filePath)
                putBoolean(ARG_CORRUPTED, corrupted)
                putString(ARG_DATE_ADDED_LINE, dateAddedLine)
                putBoolean(ARG_USED, used)
                putString(ARG_USAGE_TOTAL_LINE, usageTotalLine)
                putStringArrayList(ARG_IDENTITY_LINES, identityLines)
            }
            return fragment
        }
    }

    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? Listener
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), R.style.ThemeOverlay_App_BottomSheetDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_profile_image_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        val hash = args.getString(ARG_HASH) ?: return
        val filePath = args.getString(ARG_FILE_PATH)
        val missing = filePath == null
        val corrupted = !missing && args.getBoolean(ARG_CORRUPTED)

        val imgPreview = view.findViewById<ImageView>(R.id.img_detail_preview)
        val imgMissingIcon = view.findViewById<ImageView>(R.id.img_detail_missing_icon)
        if (missing || corrupted) {
            imgPreview.visibility = View.INVISIBLE
            imgMissingIcon.visibility = View.VISIBLE
        } else {
            imgPreview.visibility = View.VISIBLE
            imgMissingIcon.visibility = View.GONE
            Glide.with(this).load(File(filePath!!)).centerCrop().into(imgPreview)
        }

        view.findViewById<TextView>(R.id.text_detail_status).apply {
            visibility = if (missing || corrupted) View.VISIBLE else View.GONE
            setText(if (corrupted) R.string.profile_image_status_corrupted else R.string.profile_image_status_missing)
        }

        view.findViewById<TextView>(R.id.text_detail_date_added).text = args.getString(ARG_DATE_ADDED_LINE)

        val used = args.getBoolean(ARG_USED)
        val usageTotal = view.findViewById<TextView>(R.id.text_detail_usage_total)
        val usageHeader = view.findViewById<TextView>(R.id.text_detail_usage_header)
        val identityContainer = view.findViewById<LinearLayout>(R.id.container_detail_identity_list)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btn_detail_delete_permanently)

        if (used) {
            usageTotal.visibility = View.VISIBLE
            usageTotal.text = args.getString(ARG_USAGE_TOTAL_LINE)
            usageHeader.visibility = View.VISIBLE
            identityContainer.removeAllViews()
            for (line in args.getStringArrayList(ARG_IDENTITY_LINES).orEmpty()) {
                val row = TextView(requireContext())
                row.text = line
                row.setTextColor(resources.getColor(R.color.text, requireContext().theme))
                row.textSize = 14f
                val padding = (resources.displayMetrics.density * 4).toInt()
                row.setPadding(0, padding, 0, padding)
                identityContainer.addView(row)
            }
            btnDelete.visibility = View.GONE
        } else {
            usageTotal.visibility = View.GONE
            usageHeader.visibility = View.GONE
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener {
                listener?.onProfileImageDeletePermanentlyRequested(hash)
                dismiss()
            }
        }
    }
}
