package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

    /**
     * Aplica las exenciones de fondo en secuencia. El callback se ejecuta
     * incluso si algún comando individual falla, para no bloquear el resto
     * de la inicialización.
     */
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
        executeNext(engine, commands, 0, onComplete);
    }

    private static void executeNext(
            @NonNull PruxAdbEngine engine,
            @NonNull String[] commands,
            int index,
            @Nullable Runnable onComplete
    ) {
        if (index >= commands.length) {
            Log.i(TAG, "Verificación/aplicación de exenciones ADB finalizada.");
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        final String command = commands[index];
        engine.executeAllowed(command, (ok, output) -> {
            Log.i(TAG, command + " => " + ok + (output == null || output.isEmpty() ? "" : " | " + output));
            executeNext(engine, commands, index + 1, onComplete);
        });
    }

    /**
     * Prepara el appop PROJECT_MEDIA vía ADB; el token de captura sigue
     * requiriendo una sesión de usuario autorizada de MediaProjection.
     */
    public static void prepareMediaProjection(@NonNull Context context) {
        prepareMediaProjection(context, null);
    }

    public static void prepareMediaProjection(@NonNull Context context, @Nullable PruxAdbEngine.Callback callback) {
        final Context appContext = context.getApplicationContext();
        final String pkg = appContext.getPackageName();
        final PruxAdbEngine engine = PruxAdbEngine.get(appContext);
        engine.executeAllowed("appops set " + pkg + " PROJECT_MEDIA allow", (ok, output) -> {
            Log.i(TAG, "Preparación de MediaProjection (PROJECT_MEDIA)=" + ok
                    + (output == null || output.isEmpty() ? "" : " | " + output));
            if (callback != null) {
                callback.onResult(ok, output);
            }
        });
    }

    /** Mantiene el mecanismo de inicio de ProjectionActivity mediante ADB autorizado. */
    public static void startProjectionActivity(@NonNull Context context) {
        startProjectionActivity(context, null);
    }

    public static void startProjectionActivity(@NonNull Context context, @Nullable PruxAdbEngine.Callback callback) {
        final Context appContext = context.getApplicationContext();
        final String cmd = "am start -n " + appContext.getPackageName() + "/.ProjectionActivity";
        PruxAdbEngine.get(appContext).executeAllowed(cmd, (ok, output) -> {
            Log.i(TAG, "Inicio de ProjectionActivity=" + ok
                    + (output == null || output.isEmpty() ? "" : " | " + output));
            if (callback != null) {
                callback.onResult(ok, output);
            }
        });
    }
}
