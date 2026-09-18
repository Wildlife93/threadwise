package com.wildlife93.threadwise;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;
import android.telephony.SmsManager;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
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
        @Permission(alias = "sendSms",    strings = { Manifest.permission.SEND_SMS }),
        @Permission(alias = "readSms",    strings = { Manifest.permission.READ_SMS }),
        @Permission(alias = "receiveSms", strings = { Manifest.permission.RECEIVE_SMS })
    }
)
public class SmsPlugin extends Plugin {
    @Override
    public void load() {
        SmsReceiver.staticBridge = this.bridge;
    }

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

    /**
     * Sends the SMS and registers a real "sent" confirmation callback from the
     * radio/modem via a broadcast receiver, instead of assuming success the
     * instant Android accepts the request. Resolves/rejects only once the modem
     * actually reports back (or after a safety timeout if it never does).
     */
    private void doSend(PluginCall call, String phoneNumber, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            final java.util.ArrayList<String> parts = message.length() > 160
                ? smsManager.divideMessage(message)
                : new java.util.ArrayList<>(java.util.Collections.singletonList(message));
            final int totalParts = parts.size();

            final int[] remaining = { totalParts };
            final boolean[] settled = { false };
            final int[] failureCode = { Integer.MIN_VALUE };

            final String action = "com.wildlife93.threadwise.SMS_SENT_"
                + System.currentTimeMillis() + "_" + Math.abs(phoneNumber.hashCode());
            android.content.IntentFilter filter = new android.content.IntentFilter(action);

            final android.content.BroadcastReceiver[] receiverHolder = new android.content.BroadcastReceiver[1];
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    int rc = getResultCode();
                    if (rc != android.app.Activity.RESULT_OK && failureCode[0] == Integer.MIN_VALUE) {
                        failureCode[0] = rc;
                    }
                    remaining[0]--;
                    if (remaining[0] <= 0 && !settled[0]) {
                        settled[0] = true;
                        try { getContext().unregisterReceiver(receiverHolder[0]); } catch (Exception ignored) {}
                        if (failureCode[0] == Integer.MIN_VALUE) {
                            JSObject result = new JSObject();
                            result.put("success", true);
                            call.resolve(result);
                        } else {
                            String reason;
                            switch (failureCode[0]) {
                                case SmsManager.RESULT_ERROR_NO_SERVICE:      reason = "No cell service"; break;
                                case SmsManager.RESULT_ERROR_RADIO_OFF:       reason = "Radio off (airplane mode)"; break;
                                case SmsManager.RESULT_ERROR_NULL_PDU:        reason = "Null PDU"; break;
                                case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:  reason = "Send limit exceeded"; break;
                                default: reason = "Generic failure (code " + failureCode[0] + ")";
                            }
                            call.reject("SMS send failed: " + reason);
                        }
                    }
                }
            };
            receiverHolder[0] = receiver;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext().registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(receiver, filter);
            }

            int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? android.app.PendingIntent.FLAG_MUTABLE : 0);

            if (totalParts > 1) {
                java.util.ArrayList<android.app.PendingIntent> sentIntents = new java.util.ArrayList<>();
                for (int i = 0; i < totalParts; i++) {
                    sentIntents.add(android.app.PendingIntent.getBroadcast(
                        getContext(), i, new Intent(action).setPackage(getContext().getPackageName()), piFlags));
                }
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, null);
            } else {
                android.app.PendingIntent sentPI = android.app.PendingIntent.getBroadcast(
                    getContext(), 0, new Intent(action).setPackage(getContext().getPackageName()), piFlags);
                smsManager.sendTextMessage(phoneNumber, null, message, sentPI, null);
            }

            // Safety net: some OEMs occasionally never fire the callback. Don't hang forever.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!settled[0]) {
                    settled[0] = true;
                    try { getContext().unregisterReceiver(receiver); } catch (Exception ignored) {}
                    call.reject("SMS send timed out waiting for radio confirmation");
                }
            }, 15000);

        } catch (Exception e) {
            call.reject("Failed to send SMS: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getMessages(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionForAlias("readSms", call, "readSmsPermissionCallback");
            return;
        }
        doGetMessages(call, call.getString("address", ""), call.getInt("limit", 60));
    }

    @PermissionCallback
    private void readSmsPermissionCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            doGetMessages(call, call.getString("address", ""), call.getInt("limit", 60));
        } else {
            call.reject("READ_SMS permission denied");
        }
    }

    private void doGetMessages(PluginCall call, String address, int limit) {
        JSArray results = new JSArray();
        try {
            Uri uri = Uri.parse("content://sms/");
            String[] proj = { "_id", "address", "body", "date", "type", "read" };
            String sel = null;
            String[] selArgs = null;
            if (address != null && !address.isEmpty()) {
                String digits = address.replaceAll("\\D", "");
                String last7 = digits.length() > 7 ? digits.substring(digits.length() - 7) : digits;
                sel = "address LIKE ?";
                selArgs = new String[]{ "%" + last7 };
            }
            Cursor cursor = getContext().getContentResolver().query(uri, proj, sel, selArgs, "date DESC LIMIT " + limit);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSObject msg = new JSObject();
                    msg.put("id",      cursor.getString(cursor.getColumnIndexOrThrow("_id")));
                    msg.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")));
                    msg.put("body",    cursor.getString(cursor.getColumnIndexOrThrow("body")));
                    msg.put("date",    cursor.getLong(cursor.getColumnIndexOrThrow("date")));
                    msg.put("type",    cursor.getInt(cursor.getColumnIndexOrThrow("type")));
                    msg.put("read",    cursor.getInt(cursor.getColumnIndexOrThrow("read")) == 1);
                    results.put(msg);
                }
                cursor.close();
            }
            JSObject ret = new JSObject();
            ret.put("messages", results);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("getMessages failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getConversations(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionForAlias("readSms", call, "readSmsForConvosCallback");
            return;
        }
        doGetConversations(call);
    }

    @PermissionCallback
    private void readSmsForConvosCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            doGetConversations(call);
        } else {
            call.reject("READ_SMS permission denied");
        }
    }

    private void doGetConversations(PluginCall call) {
        JSArray convos = new JSArray();
        try {
            Uri uri = Uri.parse("content://sms/conversations");
            String[] proj = { "thread_id", "snippet", "msg_count" };
            Cursor cursor = getContext().getContentResolver().query(uri, proj, null, null, "date DESC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    int threadId = cursor.getInt(cursor.getColumnIndexOrThrow("thread_id"));
                    String snippet = cursor.getString(cursor.getColumnIndexOrThrow("snippet"));
                    int msgCount = cursor.getInt(cursor.getColumnIndexOrThrow("msg_count"));
                    String address = getAddressForThread(threadId);
                    JSObject convo = new JSObject();
                    convo.put("threadId", threadId);
                    convo.put("address",  address);
                    convo.put("snippet",  snippet);
                    convo.put("msgCount", msgCount);
                    convos.put(convo);
                }
                cursor.close();
            }
            JSObject ret = new JSObject();
            ret.put("conversations", convos);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("getConversations failed: " + e.getMessage());
        }
    }

    private String getAddressForThread(int threadId) {
        try {
            Cursor c = getContext().getContentResolver().query(
                Uri.parse("content://sms/"),
                new String[]{ "address" },
                "thread_id=?",
                new String[]{ String.valueOf(threadId) },
                "date DESC LIMIT 1");
            if (c != null && c.moveToFirst()) {
                String addr = c.getString(0);
                c.close();
                return addr != null ? addr : "";
            }
            if (c != null) c.close();
        } catch (Exception ignored) {}
        return "";
    }

    @PluginMethod
    public void markRead(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            call.reject("READ_SMS permission denied"); return;
        }
        String threadIdStr = call.getString("threadId", "");
        if (threadIdStr.isEmpty()) { call.reject("threadId required"); return; }
        try {
            ContentValues cv = new ContentValues();
            cv.put("read", 1);
            getContext().getContentResolver().update(
                Uri.parse("content://sms/"), cv,
                "thread_id=? AND read=0",
                new String[]{ threadIdStr });
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("markRead failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void checkDefaultSmsApp(PluginCall call) {
        boolean isDefault = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = getContext().getSystemService(RoleManager.class);
            isDefault = rm != null && rm.isRoleHeld(RoleManager.ROLE_SMS);
        } else {
            String def = Telephony.Sms.getDefaultSmsPackage(getContext());
            isDefault = getContext().getPackageName().equals(def);
        }
        JSObject ret = new JSObject();
        ret.put("isDefault", isDefault);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestDefaultSmsApp(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = getContext().getSystemService(RoleManager.class);
                if (rm != null && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                    Intent intent = rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
                    getActivity().startActivityForResult(intent, 101);
                }
            } else {
                Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getContext().getPackageName());
                getActivity().startActivity(intent);
            }
            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("requestDefaultSmsApp failed: " + e.getMessage());
        }
    }
}

