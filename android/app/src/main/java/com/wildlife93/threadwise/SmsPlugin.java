package com.wildlife93.threadwise;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

/**
 * SmsPlugin — Capacitor bridge between Android telephony and the Threadwise WebView.
 *
 * Exposed JS API  (window.Capacitor.Plugins.Sms):
 *   sendSms({ phoneNumber, message })   → Promise<{success}>
 *   checkDefaultSmsApp()                → Promise<{isDefault}>
 *   requestDefaultSmsApp()              → Promise<void>   (opens system dialog)
 *   getConversations()                  → Promise<{conversations: []}>
 *   markRead({ threadId })              → Promise<void>
 *
 * Incoming SMS flow:
 *   SmsReceiver.onReceive()
 *     → SmsPlugin.onSmsReceived()       (static, called from receiver)
 *       → notifyListeners("smsReceived", payload)
 *         → JS: window.addEventListener('smsReceived', handler)  ← already in index.html
 */
@CapacitorPlugin(
    name = "Sms",
    permissions = {
        @Permission(strings = { Manifest.permission.SEND_SMS },        alias = "sendSms"),
        @Permission(strings = { Manifest.permission.RECEIVE_SMS },     alias = "receiveSms"),
        @Permission(strings = { Manifest.permission.READ_SMS },        alias = "readSms"),
        @Permission(strings = { Manifest.permission.WRITE_SMS },       alias = "writeSms"),
        @Permission(strings = { Manifest.permission.READ_CONTACTS },   alias = "readContacts"),
    }
)
public class SmsPlugin extends Plugin {

    private static final String TAG = "Threadwise/SmsPlugin";

    // Singleton reference so SmsReceiver can call notifyListeners without
    // needing a Context → Activity → Plugin lookup.
    private static SmsPlugin instance;

    // Last received SMS payload, buffered for when the WebView was not yet alive.
    private static JSONObject pendingMessage = null;

    @Override
    public void load() {
        instance = this;
        // Deliver any message that arrived before the WebView was ready
        if (pendingMessage != null) {
            deliverToWebView(pendingMessage);
            pendingMessage = null;
        }
    }

    // ── Called by SmsReceiver (static entry point) ───────────────────────────

    /**
     * Called from SmsReceiver on the main thread after a new SMS_DELIVER.
     * If the plugin/WebView is alive we fire the JS event immediately;
     * otherwise we buffer it for delivery once load() runs.
     */
    public static void onSmsReceived(Context context, JSONObject payload) {
        if (instance != null) {
            instance.deliverToWebView(payload);
        } else {
            Log.d(TAG, "WebView not ready — buffering incoming SMS");
            pendingMessage = payload;
        }
    }

    private void deliverToWebView(JSONObject payload) {
        try {
            JSObject jsObj = new JSObject();
            jsObj.put("sender",    payload.optString("sender",    ""));
            jsObj.put("body",      payload.optString("body",      ""));
            jsObj.put("timestamp", payload.optLong("timestamp",    0));
            notifyListeners("smsReceived", jsObj);
            Log.d(TAG, "Fired smsReceived event to WebView");
        } catch (Exception e) {
            Log.e(TAG, "deliverToWebView failed", e);
        }
    }

    // ── JS-callable plugin methods ───────────────────────────────────────────

    /**
     * sendSms({ phoneNumber: string, message: string })
     */
    @PluginMethod
    public void sendSms(PluginCall call) {
        String phoneNumber = call.getString("phoneNumber");
        String message     = call.getString("message");

        if (phoneNumber == null || phoneNumber.isEmpty()) {
            call.reject("phoneNumber is required");
            return;
        }
        if (message == null || message.isEmpty()) {
            call.reject("message is required");
            return;
        }

        try {
            SmsManager mgr   = SmsManager.getDefault();
            java.util.ArrayList<String> parts = mgr.divideMessage(message);
            mgr.sendMultipartTextMessage(phoneNumber, null, parts, null, null);

            // Persist the sent message to the system outbox
            persistSentMessage(phoneNumber, message);

            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "sendSms failed", e);
            call.reject("SMS send failed: " + e.getMessage());
        }
    }

    /**
     * checkDefaultSmsApp() → { isDefault: boolean }
     */
    @PluginMethod
    public void checkDefaultSmsApp(PluginCall call) {
        String defaultPackage = Telephony.Sms.getDefaultSmsPackage(getContext());
        boolean isDefault = getContext().getPackageName().equals(defaultPackage);
        JSObject result = new JSObject();
        result.put("isDefault", isDefault);
        call.resolve(result);
    }

    /**
     * requestDefaultSmsApp() — opens the system "Change default SMS app" dialog.
     */
    @PluginMethod
    public void requestDefaultSmsApp(PluginCall call) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
            intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,
                    getContext().getPackageName());
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not open default SMS dialog: " + e.getMessage());
        }
    }

    /**
     * getConversations() — returns thread list from system SMS database.
     * Returns: { conversations: [ { threadId, address, snippet, date, unreadCount } ] }
     */
    @PluginMethod
    public void getConversations(PluginCall call) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            Cursor cursor = getContext().getContentResolver().query(
                    Telephony.Sms.CONTENT_URI,
                    new String[]{
                            Telephony.Sms.THREAD_ID,
                            Telephony.Sms.ADDRESS,
                            Telephony.Sms.BODY,
                            Telephony.Sms.DATE,
                            Telephony.Sms.READ
                    },
                    null, null,
                    Telephony.Sms.DEFAULT_SORT_ORDER
            );

            if (cursor != null) {
                // Deduplicate by thread — keep the newest message per thread
                java.util.LinkedHashMap<Long, JSObject> threads = new java.util.LinkedHashMap<>();
                while (cursor.moveToNext()) {
                    long   threadId    = cursor.getLong(0);
                    String address     = cursor.getString(1);
                    String snippet     = cursor.getString(2);
                    long   date        = cursor.getLong(3);
                    int    read        = cursor.getInt(4);

                    if (!threads.containsKey(threadId)) {
                        JSObject t = new JSObject();
                        t.put("threadId",    threadId);
                        t.put("address",     address != null ? address : "");
                        t.put("snippet",     snippet != null ? snippet : "");
                        t.put("date",        date);
                        t.put("unreadCount", read == 0 ? 1 : 0);
                        threads.put(threadId, t);
                    } else if (read == 0) {
                        // Increment unread count for this thread
                        JSObject t = threads.get(threadId);
                        t.put("unreadCount", t.getInteger("unreadCount") + 1);
                    }
                }
                cursor.close();
                for (JSObject t : threads.values()) arr.put(t);
            }

            JSObject result = new JSObject();
            result.put("conversations", arr);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "getConversations failed", e);
            call.reject("getConversations failed: " + e.getMessage());
        }
    }

    /**
     * markRead({ threadId: number })
     */
    @PluginMethod
    public void markRead(PluginCall call) {
        Integer threadId = call.getInt("threadId");
        if (threadId == null) { call.reject("threadId required"); return; }
        try {
            ContentValues cv = new ContentValues();
            cv.put(Telephony.Sms.READ, 1);
            cv.put(Telephony.Sms.SEEN, 1);
            getContext().getContentResolver().update(
                    Telephony.Sms.CONTENT_URI, cv,
                    Telephony.Sms.THREAD_ID + " = ?",
                    new String[]{ String.valueOf(threadId) }
            );
            call.resolve();
        } catch (Exception e) {
            call.reject("markRead failed: " + e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void persistSentMessage(String address, String body) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(Telephony.Sms.ADDRESS, address);
            cv.put(Telephony.Sms.BODY,    body);
            cv.put(Telephony.Sms.DATE,    System.currentTimeMillis());
            cv.put(Telephony.Sms.READ,    1);
            cv.put(Telephony.Sms.TYPE,    Telephony.Sms.MESSAGE_TYPE_SENT);
            getContext().getContentResolver()
                    .insert(Telephony.Sms.Sent.CONTENT_URI, cv);
        } catch (Exception e) {
            Log.w(TAG, "Could not persist sent SMS", e);
        }
    }
}
