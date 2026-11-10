package app.simple.felicity.shared.shiroikuma

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.simple.felicity.shared.R

/** The two buttons on the recording notification: drop a numbered mark, or stop recording. */
class SkDiagReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SkDiag.ACTION_MARK -> {
                val number = SkDiag.mark(context)
                Toast.makeText(context, context.getString(R.string.sk_diag_mark_toast, number),
                               Toast.LENGTH_SHORT).show()
            }
            SkDiag.ACTION_ALL_OFF -> SkDiag.allOff(context)
        }
    }
}
