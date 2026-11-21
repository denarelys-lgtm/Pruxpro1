package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

/** Replaces the small subset of Shizuku operations Prux actually needs. */
public final class PruxPrivilegedBridge {
    private static final String TAG = "PruxPrivilegedBridge";
    private PruxPrivilegedBridge() {}

    public static boolean isAvailable(Context context) {
        return PruxAdbEngine.get(context).isConnected();
    }

    public static void reconnect(Context context, PruxAdbEngine.Callback callback) {
        PruxAdbEngine.get(context).reconnect(callback);
    }

    public static void applyBackgroundExemptions(Context context, Runnable onComplete) {
        final String pkg = context.getPackageName();
        final PruxAdbEngine engine = PruxAdbEngine.get(context);
        final String[] commands = new String[] {
                "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS",
                "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow",
                "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                "dumpsys deviceidle whitelist +" + pkg
        };
        executeNext(engine, commands, 0, onComplete);
    }

    private static void executeNext(PruxAdbEngine engine, String[] commands,
                                    int index, Runnable onComplete) {
        if (index >= commands.length) {
            Log.i(TAG, "Verificación/aplicación de exenciones ADB finalizada.");
            if (onComplete != null) onComplete.run();
            return;
        }
        final String command = commands[index];
        engine.executeAllowed(command, (ok, output) -> {
            Log.i(TAG, command + " => " + ok + (output == null || output.isEmpty() ? "" : " | " + output));
            executeNext(engine, commands, index + 1, onComplete);
        });
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
