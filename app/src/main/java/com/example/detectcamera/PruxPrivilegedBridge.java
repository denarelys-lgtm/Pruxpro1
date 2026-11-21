package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

/** Operaciones ADB autorizadas para preparar y mantener PRUX. */
public final class PruxPrivilegedBridge {
    private static final String TAG = "PruxPrivilegedBridge";

    private PruxPrivilegedBridge() {
    }

    public static boolean isAvailable(Context context) {
        return PruxAdbEngine.get(context).isConnected();
    }

    public static void reconnect(Context context, PruxAdbEngine.Callback callback) {
        PruxAdbEngine.get(context).reconnect(callback);
    }

    /** Aplica exenciones de segundo plano y concede WRITE_SECURE_SETTINGS mediante ADB. */
    public static void applyBackgroundExemptions(Context context) {
        String pkg = context.getPackageName();
        PruxAdbEngine engine = PruxAdbEngine.get(context);
        engine.executeAllowed("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS",
                (ok, out) -> Log.i(TAG, "WRITE_SECURE_SETTINGS grant=" + ok + " " + out));
        engine.executeAllowed("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow",
                (ok, out) -> Log.i(TAG, "RUN_IN_BACKGROUND=" + ok + " " + out));
        engine.executeAllowed("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                (ok, out) -> Log.i(TAG, "RUN_ANY_IN_BACKGROUND=" + ok + " " + out));
        engine.executeAllowed("cmd appops set " + pkg + " START_FOREGROUND allow",
                (ok, out) -> Log.i(TAG, "START_FOREGROUND=" + ok + " " + out));
        engine.executeAllowed("dumpsys deviceidle whitelist +" + pkg,
                (ok, out) -> Log.i(TAG, "Doze whitelist=" + ok + " " + out));
    }

    public static void prepareMediaProjection(Context context) {
        String pkg = context.getPackageName();
        PruxAdbEngine engine = PruxAdbEngine.get(context);
        engine.executeAllowed("appops set " + pkg + " PROJECT_MEDIA allow", null);
        engine.executeAllowed("pm grant " + pkg + " android.permission.PROJECT_MEDIA", null);
        Log.i(TAG, "Preparación de MediaProjection solicitada mediante ADB autorizado.");
    }

    public static void startProjectionActivity(Context context) {
        String cmd = "am start -n " + context.getPackageName() + "/.ProjectionActivity";
        PruxAdbEngine.get(context).executeAllowed(cmd, null);
    }
}
