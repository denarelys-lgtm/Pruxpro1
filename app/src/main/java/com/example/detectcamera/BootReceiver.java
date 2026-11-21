package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.detectcamera.core.PruxCore;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DetectCameraBoot";
    public static final String ACTION_RESTART_SERVICE = "com.example.detectcamera.RESTART_SERVICE";

    private static final long INITIAL_DELAY_MS = 1_500L;
    private static final long BETWEEN_SERVICES_DELAY_MS = 1_000L;

    private static final Set<String> BOOT_ACTIONS = Set.of(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.miui.intent.action.BOOT_COMPLETED"
    );

    private static final AtomicBoolean BOOT_IN_PROGRESS = new AtomicBoolean(false);

    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        final String action = intent.getAction();
        final boolean isBootAction = action != null && BOOT_ACTIONS.contains(action);
        final boolean isRestartComponent = intent.getComponent() != null
                && ServerService.class.getName().equals(intent.getComponent().getClassName());
        final boolean isRestartAction = ACTION_RESTART_SERVICE.equals(action);

        if (!isBootAction && !isRestartComponent && !isRestartAction) {
            return;
        }

        final String effectiveAction = action != null ? action : "RESTART";
        Log.i(TAG, "Evento de inicio detectado (" + effectiveAction + "). Programando inicio escalonado de Prux...");

        publishBootEvent(effectiveAction);

        if (!BOOT_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "Inicio escalonado ya en curso; se omite disparo duplicado.");
            return;
        }

        final Context appContext = context.getApplicationContext();
        final PendingResult pending = goAsync();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        mainHandler.postDelayed(() -> {
            Thread bootThread = new Thread(() -> {
                try {
                    startCoreServices(appContext);

                    try {
                        Thread.sleep(BETWEEN_SERVICES_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }

                    startAdbAndPrivileges(appContext);
                    Log.i(TAG, "Servicios de Prux iniciados correctamente de forma escalonada.");
                } catch (Throwable t) {
                    Log.e(TAG, "Error iniciando Prux después del boot", t);
                } finally {
                    BOOT_IN_PROGRESS.set(false);
                    try {
                        pending.finish();
                    } catch (Throwable ignored) {
                    }
                }
            }, "Prux-Boot");
            bootThread.setDaemon(true);
            bootThread.start();
        }, INITIAL_DELAY_MS);
    }

    private void publishBootEvent(@NonNull String action) {
        try {
            PruxCore core = PruxCore.get();
            if (core != null) {
                core.publish("DEVICE_BOOT", "BootReceiver", Collections.singletonMap("action", action));
            }
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo publicar DEVICE_BOOT en PruxCore: " + t.getMessage());
        }
    }

    private void startCoreServices(@NonNull Context context) {
        try {
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, ServerService.class));
        } catch (Throwable t) {
            Log.e(TAG, "Error iniciando ServerService", t);
        }

        // En arranque en frío (Direct Boot), la cámara puede requerir desbloqueo previo del usuario.
        try {
            ContextCompat.startForegroundService(
                    context,
                    new Intent(context, CameraService.class));
        } catch (Throwable t) {
            Log.w(TAG, "CameraService diferido por restricciones de arranque seguro del sistema: " + t.getMessage());
        }
    }

    private void startAdbAndPrivileges(@NonNull Context context) {
        try {
            PruxAdbEngine engine = PruxAdbEngine.get(context);
            engine.startPersistentMonitoring();

            engine.reconnect((success, message) -> {
                if (!success) {
                    Log.w(TAG, "Reconexión ADB tras el boot no completada: " + message);
                    return;
                }

                Log.i(TAG, "ADB conectado tras el boot. Otorgando permisos de pantalla...");

                try {
                    PruxPrivilegedBridge.applyBackgroundExemptions(context, () -> {
                        PruxPrivilegedBridge.prepareMediaProjection(context);
                        PruxPrivilegedBridge.startProjectionActivity(context);
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "Error aplicando privilegios tras ADB", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Error inicializando PruxAdbEngine tras el boot", t);
        }
    }
}
