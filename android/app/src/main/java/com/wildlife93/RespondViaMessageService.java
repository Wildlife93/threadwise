package com.threadwise.app;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.SmsManager;
import android.util.Log;

/**
 * RespondViaMessageService
 *
 * One of the four mandatory components Android requires before it will show
 * an app in the "Choose default SMS app" dialog (alongside SmsReceiver,
 * MmsReceiver, and the launcher activity SMS intent filters).
 *
 * This service is bound by the OS when the user presses "Reply" from an
 * incoming-call decline screen or from a lock-screen notification.  The OS
 * passes the recipient URI and the canned reply text as Intent extras; we
 * send the SMS and then stop.
 *
 * Reference:
 *   https://developer.android.com/reference/android/telephony/TelephonyManager
 *       #ACTION_RESPOND_VIA_MESSAGE
 */
public class RespondViaMessageService extends Service {

    private static final String TAG = "Threadwise/RespondVia";

    /**
     * We do not support binding — the OS triggers this via startService,
     * so return null here.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // ── Recipient ────────────────────────────────────────────────────────
        // The OS puts the destination in the Intent data URI, e.g. smsto:+447700900000
        Uri dataUri = intent.getData();
        String recipient = null;
        if (dataUri != null) {
            // Works for both  sms:+44…  and  smsto:+44…
            recipient = dataUri.getSchemeSpecificPart();
            // Strip leading "//"  that some URIs include
            if (recipient != null && recipient.startsWith("//")) {
                recipient = recipient.substring(2);
            }
        }

        // ── Message body ─────────────────────────────────────────────────────
        String body = intent.getStringExtra(Intent.EXTRA_TEXT);

        if (recipient == null || recipient.isEmpty()) {
            Log.w(TAG, "RespondViaMessage: no recipient in intent");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (body == null || body.isEmpty()) {
            Log.w(TAG, "RespondViaMessage: empty body — nothing to send");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // ── Send ─────────────────────────────────────────────────────────────
        try {
            Log.d(TAG, "Sending respond-via-message to " + recipient);
            SmsManager smsManager = SmsManager.getDefault();
            // divideMessage handles messages longer than 160 chars
            java.util.ArrayList<String> parts = smsManager.divideMessage(body);
            smsManager.sendMultipartTextMessage(recipient, null, parts, null, null);
            Log.d(TAG, "Respond-via-message sent (" + parts.size() + " part(s))");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send respond-via-message", e);
        }

        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
