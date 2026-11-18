package com.iptv.tv;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class UpdateInstaller extends BroadcastReceiver {
    static void stage(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams parameters = new PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        parameters.setAppPackageName(context.getPackageName());
        parameters.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) {
            parameters.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int id = installer.createSession(parameters);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (FileInputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[8192];
                for (int length; (length = input.read(buffer)) != -1;) output.write(buffer, 0, length);
                session.fsync(output);
            }
            int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent callback = PendingIntent.getBroadcast(context, id,
                new Intent(context, UpdateInstaller.class), flags);
            session.commit(callback.getIntentSender());
        } catch (IOException | RuntimeException failure) {
            installer.abandonSession(id);
            throw failure;
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                try {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (ActivityNotFoundException | SecurityException failure) {
                    android.util.Log.w("IptvUpdates", "Cannot open confirmation", failure);
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            new File(context.getCacheDir(), "updates/IPTV-TV.apk").delete();
            return;
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            return;
        }
        android.util.Log.w("IptvUpdates", "Install status " + status + ": "
            + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        Toast.makeText(context, "系统未能安装更新", Toast.LENGTH_SHORT).show();
    }
}
