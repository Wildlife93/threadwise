package com.threadwise.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.util.Log;

/**
 * MmsReceiver
 *
 * Receives WAP_PUSH_DELIVER — the broadcast the OS sends to the default SMS
 * app when an MMS notification arrives over WAP Push (the signalling layer
 * that precedes the actual MMS download).
 *
 * This receiver is a REQUIRED component for default SMS app candidacy even if
 * you do not yet implement full MMS download/display.  Without it Android will
 * not offer Threadwise in the "Choose default SMS app" dialog at all.
 *
 * Current behaviour: logs the WAP Push and acknowledges it.
 * TODO: implement full MMS download via MmsManager (API 21+) or a library
 *       such as QKSMS's mms-stack for sending/receiving MMS content.
 */
public class MmsReceiver extends BroadcastReceiver {

    private static final String TAG = "Threadwise/MmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())) {
            Log.w(TAG, "Unexpected action: " + intent.getAction());
            return;
        }

        // The MIME type will be "application/vnd.wap.mms-message" for MMS
        String mimeType = intent.getType();
        Log.d(TAG, "WAP Push received — MIME: " + mimeType);

        // TODO: Retrieve the MMS content-location header from the PDU and
        // download the message body using MmsManager.downloadMultimediaMessage().
        // For now we simply acknowledge receipt so the OS doesn't re-deliver.
        //
        // Example skeleton:
        //   byte[] pushData = intent.getByteArrayExtra("data");
        //   PduParser parser = new PduParser(pushData, true);
        //   GenericPdu pdu = parser.parse();
        //   if (pdu instanceof NotificationInd) {
        //       NotificationInd notif = (NotificationInd) pdu;
        //       String contentLocation = new String(notif.getContentLocation());
        //       // ... download via MmsManager ...
        //   }
    }
}
