package com.wildlife93.threadwise;

import android.Manifest;
import android.content.pm.PackageManager;
import android.telephony.SmsManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "SmsPlugin",
    permissions = {
        @Permission(
            alias = "sms",
            strings = { Manifest.permission.SEND_SMS }
        )
    }
)
public class SmsPlugin extends Plugin {

    private static final int SMS_PERMISSION_REQUEST = 1001;
    private PluginCall savedCall;

    @PluginMethod
    public void send(PluginCall call) {
        String phoneNumber = call.getString("phoneNumber");
        String message = call.getString("message");

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            call.reject("Phone number is required");
            return;
        }
        if (message == null || message.isEmpty()) {
            call.reject("Message is required");
            return;
        }

        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            savedCall = call;
            requestPermissionForAlias("sms", call, "smsPermissionCallback");
            return;
        }

        doSend(call, phoneNumber, message);
    }

    @PermissionCallback
    private void smsPermissionCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            String phoneNumber = call.getString("phoneNumber");
            String message = call.getString("message");
            doSend(call, phoneNumber, message);
        } else {
            call.reject("SMS permission denied");
        }
    }

    private void doSend(PluginCall call, String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            if (message.length() > 160) {
                java.util.ArrayList<String> parts = smsManager.divideMessage(message);
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            }
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to send SMS: " + e.getMessage());
        }
    }
}
