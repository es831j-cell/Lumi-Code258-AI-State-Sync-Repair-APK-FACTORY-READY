package com.distressedelk.lumi.guardian;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;

public final class InstallStatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!GuardianInstaller.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        int sessionId = intent.getIntExtra("session_id", -1);
        long target = intent.getLongExtra("target_version", -1L);
        boolean recovery = intent.getBooleanExtra("recovery", false);
        SharedPreferences prefs = context.getSharedPreferences("guardian", Context.MODE_PRIVATE);
        prefs.edit()
                .putInt("last_install_status", status)
                .putString("last_install_message", message == null ? "" : message)
                .putInt("last_install_session", sessionId)
                .putLong("last_install_target", target)
                .putBoolean("last_install_recovery", recovery)
                .putLong("last_install_at", System.currentTimeMillis())
                .apply();

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(confirm); } catch (Exception ignored) {}
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            GuardianLedger.append(context, "INSTALL_SUCCESS", "session=" + sessionId + " target=" + target + " recovery=" + recovery);
            prefs.edit().putBoolean("certification_pending", true).putLong("certification_target", target).apply();
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(TrustedIdentity.LUMI_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(launch); } catch (Exception ignored) {}
            }
        } else {
            GuardianLedger.append(context, "INSTALL_FAILURE", "session=" + sessionId + " target=" + target + " status=" + status + " message=" + message);
        }
    }
}
