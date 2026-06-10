package com.wildlife93.threadwise;

import com.getcapacitor.BridgeActivity;
import com.wildlife93.threadwise.SmsPlugin;

/**
 * MainActivity
 *
 * Extends BridgeActivity (Capacitor's base).  All custom Capacitor plugins
 * are registered here via registerPlugin().
 *
 * The AndroidManifest intent filters on this activity (sms:/smsto: SENDTO/VIEW)
 * are what make Threadwise appear in the system "Choose default SMS app" dialog
 * alongside the four required components:
 *   - SmsReceiver     (SMS_DELIVER)
 *   - MmsReceiver     (WAP_PUSH_DELIVER)
 *   - RespondViaMessageService
 *   - This activity   (SMS intent filters)
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        // Register custom plugins BEFORE super.onCreate so the bridge is
        // aware of them when the WebView first loads.
        registerPlugin(SmsPlugin.class);

        super.onCreate(savedInstanceState);

        // ── Handle "default SMS app" prompt on first launch ──────────────────
        // If Threadwise is not yet the default, show the system dialog once.
        checkAndRequestDefaultSmsApp();
    }

    /**
     * If we're not the default SMS app, open the system change-default dialog.
     * This is non-blocking — the user can dismiss it.  We only ask once per
     * install by storing a flag in SharedPreferences.
     */
    private void checkAndRequestDefaultSmsApp() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("tw_prefs", MODE_PRIVATE);
        boolean askedBefore = prefs.getBoolean("asked_default_sms", false);
        if (askedBefore) return;

        String defaultPkg = android.provider.Telephony.Sms.getDefaultSmsPackage(this);
        if (!getPackageName().equals(defaultPkg)) {
            prefs.edit().putBoolean("asked_default_sms", true).apply();
            try {
                android.content.Intent intent = new android.content.Intent(
                        android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
                intent.putExtra(
                        android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME,
                        getPackageName());
                startActivity(intent);
            } catch (Exception e) {
                android.util.Log.w("Threadwise", "Could not launch default SMS dialog", e);
            }
        }
    }
}
