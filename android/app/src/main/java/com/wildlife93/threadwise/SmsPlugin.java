package com.wildlife93.threadwise;

import android.Manifest;
import android.content.pm.PackageManager;
import android.telephony.SmsManager;
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
        @Permission(alias = "sendSms", strings = { Manifest.permission.SEND_SMS })
    }
)
public class SmsPlugin extends Plugin {

    @PluginMethod
    public void send(PluginCall call) {
        String phoneNumber = call.getString("phoneNumber");
        String message = call.getString("message");
        if (phoneNumber == null || phoneNumber.isEmpty()) { call.reject("Phone number required"); return; }
        if (message == null || message.isEmpty()) { call.reject("Message required"); return; }
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionForAlias("sendSms", call, "smsPermissionCallback");
            return;
        }
        doSend(call, phoneNumber, message);
    }

    @PermissionCallback
    private void smsPermissionCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            doSend(call, call.getString("phoneNumber"), call.getString("message"));
        } else {
            call.reject("SMS permission denied");
        }
    }

    private void doSend(PluginCall call, String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            if (message.length() > 160) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, smsManager.divideMessage(message), null, null);
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
