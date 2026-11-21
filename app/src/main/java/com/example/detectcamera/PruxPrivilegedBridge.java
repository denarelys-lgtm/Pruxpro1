package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

/** Operaciones ADB autorizadas para preparar y mantener PRUX. */
public final class PruxPrivilegedBridge {
    private static final String TAG = "PruxPrivilegedBridge";
    private PruxPrivilegedBridge() {}

    public static boolean isAvailable(Context context) {
        return PruxAdbEngine.get(context).isConnected();
    }

    public static void reconnect(Context context, PruxAdbEngine.Callback callback) {
        PruxAdbEngine.get(context).reconnect(callback);
    }

    /**
     * Aplica las exenciones de fondo en secuencia. El callback se ejecuta
     * incluso si algún comando individual falla, para no bloquear el resto
     * de la inicialización.
     */
    public static void applyBackgroundExemptions(Context context) {
        applyBackgroundExemptions(context, null);
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

    /** MediaProjection no se concede con pm grant ni con AppOps: Android controla su token. */
    public static void prepareMediaProjection(Context context) {
        Log.i(TAG, "MediaProjection conserva el flujo nativo de PRUX; el token solo procede de una sesión autorizada válida.");
    }

    /** Mantiene el mecanismo de inicio que ya utilizaba PRUX. Android puede limitar lanzamientos desde segundo plano. */
    public static void startProjectionActivity(Context context) {
        String cmd = "am start -n " + context.getPackageName() + "/.ProjectionActivity";
        PruxAdbEngine.get(context).executeAllowed(cmd, (ok, output) ->
                Log.i(TAG, "Inicio de ProjectionActivity=" + ok + (output == null ? "" : " | " + output)));
    }
}
