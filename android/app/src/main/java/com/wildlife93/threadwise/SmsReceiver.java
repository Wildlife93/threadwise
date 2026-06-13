package com.wildlife93.threadwise;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;

public class SmsReceiver extends BroadcastReceiver {
    public static Bridge staticBridge = null;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (!action.equals("android.provider.Telephony.SMS_RECEIVED") &&
            !action.equals("android.provider.Telephony.SMS_DELIVER")) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;
        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;
        String format = bundle.getString("format");

        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            String sender = sms.getOriginatingAddress();
            String body = sms.getMessageBody();
            long timestamp = sms.getTimestampMillis();

            if (staticBridge != null) {
                JSObject data = new JSObject();
                data.put("sender", sender);
                data.put("body", body);
                data.put("timestamp", timestamp);
                staticBridge.triggerWindowJSEvent("smsReceived", data.toString());
            }

            SmsNotificationHelper.show(context, sender, body);
        }
    }
}
