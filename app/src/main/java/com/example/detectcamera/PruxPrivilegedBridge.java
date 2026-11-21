package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/** Operaciones ADB autorizadas para preparar y mantener PRUX. */
public final class PruxPrivilegedBridge {

    private static final String TAG = "PruxPrivilegedBridge";

    private PruxPrivilegedBridge() {
    }

    public static boolean isAvailable(@NonNull Context context) {
        return PruxAdbEngine.get(context.getApplicationContext()).isConnected();
    }

    public static void reconnect(@NonNull Context context, @Nullable PruxAdbEngine.Callback callback) {
        PruxAdbEngine.get(context.getApplicationContext()).reconnect(callback);
    }

    public static void applyBackgroundExemptions(@NonNull Context context) {
        applyBackgroundExemptions(context, null);
    }

    public static void applyBackgroundExemptions(@NonNull Context context, @Nullable Runnable onComplete) {
        final Context appContext = context.getApplicationContext();
        final String pkg = appContext.getPackageName();
        final PruxAdbEngine engine = PruxAdbEngine.get(appContext);

        final String[] commands = new String[] {
                "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS",
                "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow",
                "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                "cmd appops set " + pkg + " START_FOREGROUND allow",
                "dumpsys deviceidle whitelist +" + pkg
        };

        if (onComplete == null) {
            for (String cmd : commands) {
                engine.executeAllowed(cmd, null);
            }
            return;
        }

        final AtomicInteger remaining = new AtomicInteger(commands.length);
        for (String cmd : commands) {
            engine.executeAllowed(cmd, (ok, output) -> {
                if (remaining.decrementAndGet() == 0) {
                    Log.i(TAG, "Exenciones ADB completadas.");
                    onComplete.run();
                }
            });
        }
    }

    public static void prepareMediaProjection(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        final String pkg = appContext.getPackageName();
        final PruxAdbEngine engine = PruxAdbEngine.get(appContext);
        engine.executeAllowed("appops set " + pkg + " PROJECT_MEDIA allow", null);
        engine.executeAllowed("pm grant " + pkg + " android.permission.PROJECT_MEDIA", null);
        Log.i(TAG, "Preparación de MediaProjection solicitada mediante ADB autorizado.");
    }

    public static void startProjectionActivity(@NonNull Context context) {
        final Context appContext = context.getApplicationContext();
        final String cmd = "am start -n " + appContext.getPackageName() + "/.ProjectionActivity";
        PruxAdbEngine.get(appContext).executeAllowed(cmd, null);
    }
}
