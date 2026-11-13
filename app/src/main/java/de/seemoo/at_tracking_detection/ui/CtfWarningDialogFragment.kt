package de.seemoo.at_tracking_detection.ui

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import de.seemoo.at_tracking_detection.R

class CtfWarningDialogFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ctf_warning_title)
            .setIcon(R.drawable.ic_baseline_error_outline_24)
            .setMessage(R.string.ctf_warning_message)
            .setCancelable(false)
            .setPositiveButton(R.string.ctf_warning_acknowledge) { _, _ ->
                acknowledged = true
            }
            .create()
            .apply { setCanceledOnTouchOutside(false) }

    companion object {
        private const val TAG = "ctf_warning"
        // Deliberately process-local: background services must not consume acknowledgment.
        private var acknowledged = false

        fun showIfNeeded(activity: FragmentActivity) {
            val manager = activity.supportFragmentManager
            if (activity.isFinishing || activity.isDestroyed || manager.isStateSaved) return

            val existing = manager.findFragmentByTag(TAG) as? CtfWarningDialogFragment
            if (acknowledged) {
                // A saved Activity state can still contain a previously acknowledged dialog.
                existing?.dismiss()
            } else if (existing == null) {
                CtfWarningDialogFragment().showNow(manager, TAG)
            }
        }
    }
}
