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

package org.teslasoft.assistant.ui.activities

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.preferences.profileimages.ProfileImageStore
import org.teslasoft.assistant.preferences.profileimages.ProfileImageTransform
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.ui.views.FramingView
import org.teslasoft.assistant.ui.views.RotationDialView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * The Framing editor (profile-images-plan.md, FRAMING SCREEN..FRAMING OUTPUT).
 * A thin view shell over [ProfileImageTransform]: it copies the chosen source
 * into a temporary framing-session folder, decodes it bounded and upright
 * (EXIF applied once), lets the user pan/zoom/flip/rotate/fine-tune with a
 * fixed square crop, and on Done writes exactly one 512x512 JPEG q92 into the
 * SESSION directory — never permanent storage. The bare temp path is returned
 * to the caller (the gallery, Phase 5), which performs the permanent hash,
 * dedup, catalog insert and assignment.
 */
class ProfileImageFramingActivity : FragmentActivity() {

    companion object {
        /** Input: the picked source image (content:// URI string). */
        const val EXTRA_SOURCE_URI = "source_uri"
        /** Output (RESULT_OK): absolute path of the 512x512 JPEG temp result. */
        const val EXTRA_RESULT_TEMP_PATH = "result_temp_path"

        private const val OUTPUT_SIZE = 512
        private const val OUTPUT_QUALITY = 92
        private const val MAX_DECODE_EDGE = 2048

        private const val STATE_SESSION_TOKEN = "session_token"
        private const val STATE_SOURCE_COPY = "source_copy_path"
        private const val STATE_TX = "tx"
        private const val STATE_TY = "ty"
        private const val STATE_SCALE = "scale"
        private const val STATE_FLIP = "flip"
        private const val STATE_QUARTER = "quarter"
        private const val STATE_FINE = "fine"
        private const val STATE_HAS_PARAMS = "has_params"
    }

    private var framingView: FramingView? = null
    private var rotationDial: RotationDialView? = null
    private var readout: TextView? = null
    private var btnDone: ImageButton? = null

    private var store: ProfileImageStore? = null
    private var sessionToken: String? = null
    private var sourceCopy: File? = null

    private val ioScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        setContentView(R.layout.activity_profile_image_framing)

        store = ProfileImageStore.getInstance(this)

        framingView = findViewById(R.id.framing_view)
        rotationDial = findViewById(R.id.rotation_dial)
        readout = findViewById(R.id.fine_rotation_readout)
        btnDone = findViewById(R.id.btn_done)

        findViewById<ImageButton>(R.id.btn_flip).setOnClickListener { framingView?.toggleFlip() }
        findViewById<ImageButton>(R.id.btn_rotate).setOnClickListener { framingView?.rotate90() }
        findViewById<ImageButton>(R.id.btn_cancel).setOnClickListener { cancel() }
        btnDone?.setOnClickListener { confirm() }

        rotationDial?.onAngleChanged = { deg ->
            framingView?.setFineAngle(deg)
            updateReadout(deg)
        }
        readout?.setOnClickListener { showFineRotationDialog() }

        framingView?.onReadyListener = {
            btnDone?.isEnabled = true
            updateReadout(framingView?.getFineAngle() ?: 0f)
        }

        updateReadout(0f)

        val restored = savedInstanceState != null && restoreSession(savedInstanceState)
        if (!restored) {
            val uriString = intent.getStringExtra(EXTRA_SOURCE_URI)
            if (uriString.isNullOrEmpty()) {
                showLoadError()
                return
            }
            startFreshSession(Uri.parse(uriString))
        }
    }

    /* ----------------------------- loading ----------------------------- */

    private fun startFreshSession(source: Uri) {
        val sessionDir = store!!.newFramingSessionDir()
        sessionToken = sessionDir.name
        ioScope.launch {
            val bmp = copyAndDecode(source, sessionDir)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (bmp == null) {
                    showLoadError()
                } else {
                    framingView?.setBitmap(bmp)
                }
            }
        }
    }

    private fun restoreSession(state: Bundle): Boolean {
        val token = state.getString(STATE_SESSION_TOKEN) ?: return false
        val copyPath = state.getString(STATE_SOURCE_COPY) ?: return false
        val copy = File(copyPath)
        // Do not promise restoration after cache eviction / missing source.
        if (!copy.exists()) {
            showLoadError()
            return true
        }
        sessionToken = token
        sourceCopy = copy

        val restoredParams = if (state.getBoolean(STATE_HAS_PARAMS, false)) {
            ProfileImageTransform.Params(
                translateX = state.getFloat(STATE_TX),
                translateY = state.getFloat(STATE_TY),
                scale = state.getFloat(STATE_SCALE),
                flipX = state.getBoolean(STATE_FLIP),
                quarterTurns = state.getInt(STATE_QUARTER),
                fineAngleDeg = state.getFloat(STATE_FINE)
            )
        } else null

        ioScope.launch {
            val bmp = decodeUpright(copy)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (bmp == null) {
                    showLoadError()
                } else {
                    framingView?.setBitmap(bmp)
                    if (restoredParams != null) {
                        framingView?.setParams(restoredParams)
                        rotationDial?.setAngle(restoredParams.fineAngleDeg)
                        updateReadout(restoredParams.fineAngleDeg)
                    }
                }
            }
        }
        return true
    }

    /** Copies [source] into [sessionDir] off the main thread, then decodes upright. */
    private fun copyAndDecode(source: Uri, sessionDir: File): Bitmap? {
        return try {
            val copy = File(sessionDir, "source")
            contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(copy).use { output -> input.copyTo(output) }
            } ?: return null
            sourceCopy = copy
            decodeUpright(copy)
        } catch (_: Exception) {
            null
        }
    }

    /** Bounded decode (~2048 px longest edge) with EXIF orientation applied once. */
    private fun decodeUpright(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_DECODE_EDGE) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

            val orientation = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            applyOrientation(decoded, orientation)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /* ----------------------------- actions ----------------------------- */

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun confirm() {
        val result = framingView?.getResultBitmap(OUTPUT_SIZE) ?: return
        val token = sessionToken ?: return
        ioScope.launch {
            val path = writeTempResult(result, token)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (path == null) {
                    showLoadError()
                } else {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_TEMP_PATH, path))
                    finish()
                }
            }
        }
    }

    /** Writes exactly one 512x512 JPEG q92 into the session dir; returns its path. */
    private fun writeTempResult(bitmap: Bitmap, token: String): String? {
        return try {
            val sessionDir = store!!.framingSessionDir(token)
            if (!sessionDir.exists()) sessionDir.mkdirs()
            val out = File(sessionDir, "framed_${System.nanoTime()}.jpg")
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, OUTPUT_QUALITY, it) }
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /* --------------------------- fine rotation --------------------------- */

    private fun showFineRotationDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_fine_rotation, null)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.fine_rotation_input_layout)
        val input = view.findViewById<TextInputEditText>(R.id.fine_rotation_input)
        input.setText(formatAngle(framingView?.getFineAngle() ?: 0f))

        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.fine_rotation_title)
            .setView(view)
            .setPositiveButton(R.string.fine_rotation_apply, null)
            .setNegativeButton(R.string.fine_rotation_cancel, null)
            .setNeutralButton(R.string.fine_rotation_reset, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim()?.toFloatOrNull()
                if (value == null || value < -45f || value > 45f) {
                    inputLayout.error = getString(R.string.fine_rotation_invalid)
                } else {
                    applyFineAngle(value)
                    dialog.dismiss()
                }
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                // Reset to 0° immediately sets the fine angle to zero.
                applyFineAngle(0f)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun applyFineAngle(deg: Float) {
        framingView?.setFineAngle(deg)
        rotationDial?.setAngle(deg)
        updateReadout(deg)
    }

    /* ----------------------------- readout ----------------------------- */

    private fun updateReadout(deg: Float) {
        val formatted = formatAngle(deg)
        readout?.text = getString(R.string.framing_fine_rotation_readout, formatted)
        val desc = getString(R.string.framing_fine_rotation_content_description, formatted)
        readout?.contentDescription = desc
        // Announce the current value for screen readers as it changes.
        readout?.announceForAccessibility(desc)
    }

    /**
     * Formats the fine angle for the readout with an explicit sign and the plan's
     * minus glyph: "+7", "0", "−12.5". A trailing ".0" is dropped so whole
     * degrees read cleanly.
     */
    private fun formatAngle(deg: Float): String {
        val rounded = (deg * 10f).toInt() / 10f
        if (rounded == 0f) return "0"
        val magnitude = abs(rounded)
        val body = if (magnitude % 1f == 0f) magnitude.toInt().toString() else magnitude.toString()
        return if (rounded < 0f) "−$body" else "+$body"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SESSION_TOKEN, sessionToken)
        outState.putString(STATE_SOURCE_COPY, sourceCopy?.absolutePath)
        val view = framingView
        if (view != null && view.hasImage()) {
            val p = view.getParams()
            outState.putBoolean(STATE_HAS_PARAMS, true)
            outState.putFloat(STATE_TX, p.translateX)
            outState.putFloat(STATE_TY, p.translateY)
            outState.putFloat(STATE_SCALE, p.scale)
            outState.putBoolean(STATE_FLIP, p.flipX)
            outState.putInt(STATE_QUARTER, p.quarterTurns)
            outState.putFloat(STATE_FINE, p.fineAngleDeg)
        }
    }

    override fun onDestroy() {
        ioScope.cancel()
        super.onDestroy()
    }

    private fun showLoadError() {
        MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(R.string.framing_load_error_title)
            .setMessage(R.string.framing_load_error_body)
            .setCancelable(false)
            .setView(buildSingleActionView(R.string.framing_load_error_button) { cancel() })
            .show()
    }

    /**
     * Inflates the shared single-primary-action dialog content
     * (dialog_single_action.xml) and wires its AppButton.Primary.Dialog button.
     */
    private fun buildSingleActionView(labelRes: Int, onClick: () -> Unit): View {
        val view = layoutInflater.inflate(R.layout.dialog_single_action, null)
        val button = view.findViewById<MaterialButton>(R.id.btn_dialog_action)
        button.setText(labelRes)
        button.setOnClickListener { onClick() }
        return view
    }
}
