package org.teslasoft.assistant.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.teslasoft.assistant.R
import org.teslasoft.assistant.theme.ThemeManager
import org.teslasoft.assistant.preferences.tts.SavedTtsSourcesPreferences
import org.teslasoft.assistant.tts.api.*
import java.util.concurrent.CancellationException

/** Public caller contracts. RESULT_CANCELED (including Back) never returns a choice. */
class TtsModelPickerContract : ActivityResultContract<TtsPickerRequest, TtsTarget?>() {
    override fun createIntent(context: Context, input: TtsPickerRequest): Intent {
        require(input.target.sourceId == null) { "A saved source's model is fixed" }
        return Intent(context, TtsModelPickerActivity::class.java)
            .putExtra(TtsPickerActivity.EXTRA_TARGET, TtsPickerCodec.encode(input.target))
    }
    override fun parseResult(resultCode: Int, intent: Intent?): TtsTarget? = pickerResult(resultCode, intent)
}

class TtsProviderPickerContract : ActivityResultContract<TtsPickerRequest, TtsTarget?>() {
    override fun createIntent(context: Context, input: TtsPickerRequest) =
        Intent(context, TtsProviderPickerActivity::class.java)
            .putExtra(TtsPickerActivity.EXTRA_TARGET, TtsPickerCodec.encode(input.target))
    override fun parseResult(resultCode: Int, intent: Intent?): TtsTarget? = pickerResult(resultCode, intent)
}

private fun pickerResult(code: Int, intent: Intent?): TtsTarget? {
    if (code != android.app.Activity.RESULT_OK) return null
    return intent?.getStringExtra(TtsPickerActivity.EXTRA_TARGET)?.let {
        runCatching { TtsPickerCodec.decode(it) }.getOrNull()
    }
}

/** TTS-only lifecycle and dialogs; no chat preferences, favorites or player side effects. */
abstract class TtsPickerActivity : FragmentActivity() {
    companion object { const val EXTRA_TARGET = "tts.picker.target" }
    protected val gate = TtsRequestGate()
    private var notice: androidx.appcompat.app.AlertDialog? = null
    private var resumeAttempt: (() -> Unit)? = null
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.getThemeManager().applyPalette(this)
        window.setBackgroundDrawable(SurfaceColors.SURFACE_0.getColor(this).toDrawable())
    }

    protected fun bindInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    protected fun readTarget(state: Bundle?): TtsTarget? = try {
        TtsPickerCodec.decode((state?.getString(EXTRA_TARGET)
            ?: intent.getStringExtra(EXTRA_TARGET)) ?: error("Missing TTS target"))
    } catch (_: Exception) {
        finish()
        null
    }

    protected fun <T> discover(target: TtsTarget, operation: TtsOperation,
        work: (ResolvedTtsSource, TtsRequestToken) -> T,
        success: (T) -> Unit, failure: (TtsFailure) -> Unit) {
        notice?.dismiss()
        resumeAttempt = { discover(target, operation, work, success, failure) }
        val token = gate.begin()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    target.sourceId?.let { id ->
                        val sources = SavedTtsSourcesPreferences.getPreferences(this@TtsPickerActivity).load()
                            .getOrElse { throw TtsException(TtsFailure(operation, target, "", TtsFailureKind.STORAGE)) }
                        if (sources.none { it.sourceId == id && it.endpointId == target.endpointId && it.modelId == target.modelId })
                            throw TtsException(TtsFailure(operation, target, "", TtsFailureKind.SOURCE_MISSING))
                    }
                    val source = TtsAndroidServices.resolver(this@TtsPickerActivity).resolve(target)
                    work(source, token)
                }
                token.deliver { resumeAttempt = null; if (!isFinishing && !isDestroyed) success(result) }
            } catch (_: CancellationException) {
                // Navigation and replaced attempts cannot surface an error or a late selection.
            } catch (error: Exception) {
                val problem = (error as? TtsException)?.failure?.copy(operation = operation)
                    ?: TtsFailure(operation, target, "", TtsFailureKind.UNKNOWN)
                token.deliver { resumeAttempt = null; if (!isFinishing && !isDestroyed) failure(problem) }
            }
        }
    }

    protected fun showFailure(failure: TtsFailure, retry: () -> Unit) {
        if (isFinishing || isDestroyed) return
        notice?.dismiss()
        val message = TtsFailures.message(failure)
        val actions = layoutInflater.inflate(if ("Retry" in message.actions)
            R.layout.dialog_two_actions_cancel_first else R.layout.dialog_single_action, null)
        val detail = TtsAndroidServices.providerDetails(this, failure)
        val dialog = MaterialAlertDialogBuilder(this, R.style.App_MaterialAlertDialog)
            .setTitle(message.title)
            .setMessage(listOfNotNull(message.explanation, detail).joinToString("\n\n"))
            .setView(actions).create()
        if ("Retry" in message.actions) {
            actions.findViewById<MaterialButton>(R.id.btn_dialog_destructive_action).apply {
                setText(R.string.btn_cancel)
                setOnClickListener { dialog.dismiss() }
            }
            actions.findViewById<MaterialButton>(R.id.btn_dialog_primary_action).apply {
                setText(R.string.health_btn_retry)
                setOnClickListener { dialog.dismiss(); retry() }
            }
        } else actions.findViewById<MaterialButton>(R.id.btn_dialog_action).apply {
            setText(R.string.btn_ok)
            setOnClickListener { dialog.dismiss() }
        }
        notice = dialog
        dialog.show()
    }

    protected fun returnSelection(target: TtsTarget) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TARGET, TtsPickerCodec.encode(target)))
        finish()
    }

    override fun onStart() {
        super.onStart()
        if (stopped) { stopped = false; resumeAttempt?.invoke() }
    }
    override fun onStop() {
        stopped = true
        gate.cancel()
        notice?.dismiss()
        super.onStop()
    }
    override fun finish() { resumeAttempt = null; gate.cancel(); notice?.dismiss(); super.finish() }
    override fun onDestroy() { gate.cancel(); notice?.dismiss(); super.onDestroy() }
}
