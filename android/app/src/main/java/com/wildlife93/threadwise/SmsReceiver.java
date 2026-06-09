package com.wildlife93.threadwise;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * SmsReceiver
 *
 * Listens for android.provider.Telephony.SMS_DELIVER — the broadcast that
 * Android delivers EXCLUSIVELY to the currently-selected default SMS app.
 *
 * Key responsibilities:
 *   1. Parse incoming PDUs into readable SmsMessage objects.
 *   2. Write the message to the system SMS content provider (inbox).
 *      – Non-default apps cannot write to Telephony.Sms; we must do this
 *        ourselves or the message will not appear in any other SMS reader.
 *   3. Forward message data to the WebView via SmsPlugin / JS event.
 *   4. Post a notification so the user sees the message even when the app
 *      is backgrounded.
 *
 * Why SMS_DELIVER and not SMS_RECEIVED?
 *   SMS_RECEIVED is a broadcast all apps can intercept (with permission).
 *   SMS_DELIVER is a directed broadcast sent only to the default SMS app,
 *   which is also the app responsible for persisting the message.
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "Threadwise/SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Ignoring unexpected action: " + intent.getAction());
            return;
        }

        // ── 1. Parse PDUs ────────────────────────────────────────────────────
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) {
            Log.w(TAG, "No messages in intent");
            return;
        }

        // Concatenate multi-part messages into a single body string
        StringBuilder bodyBuilder = new StringBuilder();
        String sender = null;
        long timestamp = 0;

        for (SmsMessage msg : messages) {
            if (sender == null) {
                sender = msg.getDisplayOriginatingAddress();
                timestamp = msg.getTimestampMillis();
            }
            bodyBuilder.append(msg.getMessageBody());
        }

        String body = bodyBuilder.toString();
        Log.d(TAG, "SMS_DELIVER from " + sender + " — " + body.length() + " chars");

        // ── 2. Persist to system SMS inbox ───────────────────────────────────
        // As the default SMS app we MUST write incoming messages ourselves.
        persistToInbox(context, sender, body, timestamp);

        // ── 3. Bridge to WebView / JS layer ──────────────────────────────────
        try {
            JSONObject payload = new JSONObject();
            payload.put("sender", sender != null ? sender : "unknown");
            payload.put("body", body);
            payload.put("timestamp", timestamp);

            // SmsPlugin stores the latest message for the WebView to poll,
            // and also fires a JSEvent if the WebView is alive.
            SmsPlugin.onSmsReceived(context, payload);
        } catch (JSONException e) {
            Log.e(TAG, "JSON build failed", e);
        }

        // ── 4. Show a notification ───────────────────────────────────────────
        SmsNotificationHelper.show(context, sender, body);
    }

    /**
     * Write the incoming message into the system Telephony SMS inbox.
     * This is mandatory for the default SMS app; if we skip it the message
     * is silently dropped from the system database.
     */
    private void persistToInbox(Context context, String sender, String body, long timestamp) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(Telephony.Sms.ADDRESS, sender);
            cv.put(Telephony.Sms.BODY, body);
            cv.put(Telephony.Sms.DATE, timestamp);
            cv.put(Telephony.Sms.DATE_SENT, timestamp);
            cv.put(Telephony.Sms.READ, 0);    // unread
            cv.put(Telephony.Sms.SEEN, 0);    // unseen
            cv.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);

            Uri inserted = context.getContentResolver()
                    .insert(Telephony.Sms.Inbox.CONTENT_URI, cv);

            if (inserted != null) {
                Log.d(TAG, "Persisted SMS to inbox: " + inserted);
            } else {
                Log.w(TAG, "Insert returned null — are we the default SMS app?");
            }
        } catch (SecurityException se) {
            // This fires if we are NOT the default SMS app at runtime.
            // The user must select Threadwise as default before messages persist.
            Log.e(TAG, "Cannot write to SMS inbox — not default SMS app", se);
        } catch (Exception e) {
            Log.e(TAG, "Failed to persist SMS", e);
        }
    }
}
