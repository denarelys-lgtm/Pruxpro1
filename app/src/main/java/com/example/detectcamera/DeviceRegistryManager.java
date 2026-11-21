
package com.example.detectcamera;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Publica el estado del teléfono en Realtime Database; no transporta audio ni vídeo. */
public final class DeviceRegistryManager {
    private static final String TAG = "PruxRegistry";
    private static final String DATABASE_URL =
            "https://detectcameracentral-default-rtdb.firebaseio.com/";
    private static final String DATABASE_NODE = "dispositivos";
    private static final int MAX_REINTENTOS = 5;
    private static final AtomicReference<String> ULTIMA_URL = new AtomicReference<>(null);
    private static final Object LOCK_REGISTRO = new Object();

    private DeviceRegistryManager() { }

    public static void registrarDispositivoEnNube(Context context, String urlTunel) {
        if (context == null) {
            Log.e(TAG, "Context inválido; no se puede registrar el dispositivo.");
            return;
        }

        final String urlFinal;

        synchronized (LOCK_REGISTRO) {
            if (urlTunel != null && !urlTunel.trim().isEmpty()) {
                ULTIMA_URL.set(urlTunel.trim());
                urlFinal = urlTunel.trim();
            } else {
                // Al iniciar, no conservamos una URL rápida que podría pertenecer a un túnel viejo.
                ULTIMA_URL.set(null);
                urlFinal = "";
            }
        }

        ejecutarRegistro(context.getApplicationContext(), urlFinal, 0);
    }

    private static void ejecutarRegistro(Context appContext, String url, int intento) {
        final String androidId;

        try {
            androidId = Settings.Secure.getString(
                    appContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
        } catch (Exception e) {
            Log.e(TAG, "No se pudo obtener ANDROID_ID.", e);
            return;
        }

        if (androidId == null || androidId.trim().isEmpty()) {
            Log.e(TAG, "ANDROID_ID vacío; registro cancelado.");
            return;
        }

        final String urlActual;

        synchronized (LOCK_REGISTRO) {
            String ultima = ULTIMA_URL.get();
            urlActual = (url == null || url.isEmpty()) && ultima != null
                    ? ultima
                    : url;
        }

        try {
            FirebaseAuth auth = FirebaseAuth.getInstance();
            FirebaseUser currentUser = auth.getCurrentUser();

            if (currentUser != null) {
                escribirRegistro(
                        appContext,
                        currentUser,
                        androidId,
                        urlActual,
                        intento
                );
            } else {
                Log.i(TAG, "Iniciando autenticación anónima Firebase.");

                auth.signInAnonymously()
                        .addOnSuccessListener(result -> {
                            FirebaseUser user = result.getUser();

                            if (user == null) {
                                programarReintento(
                                        appContext,
                                        urlActual,
                                        intento + 1
                                );
                            } else {
                                escribirRegistro(
                                        appContext,
                                        user,
                                        androidId,
                                        urlActual,
                                        intento
                                );
                            }
                        })
                        .addOnFailureListener(error -> {
                            Log.e(
                                    TAG,
                                    "Falló autenticación anónima: "
                                            + error.getMessage(),
                                    error
                            );

                            programarReintento(
                                    appContext,
                                    urlActual,
                                    intento + 1
                            );
                        });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error inicializando Firebase.", e);
            programarReintento(appContext, urlActual, intento + 1);
        }
    }

    private static void escribirRegistro(
            Context appContext,
            FirebaseUser user,
            String androidId,
            String url,
            int intento
    ) {
        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance(DATABASE_URL);

            DatabaseReference deviceRef = database
                    .getReference(DATABASE_NODE)
                    .child(androidId);

            Map<String, Object> data = new HashMap<>();

            data.put("modelo", Build.MODEL == null ? "Android" : Build.MODEL);
            data.put("androidId", androidId);
            data.put("firebaseUid", user.getUid());
            data.put("plataforma", "Android");

            data.put(
                    "estado",
                    url.isEmpty()
                            ? "servidor_activo_tunel_pendiente"
                            : "conectado"
            );

            data.put("timestamp", ServerValue.TIMESTAMP);

            // updateChildren con null elimina la URL antigua mientras se crea el nuevo Quick Tunnel.
            data.put("url", url.isEmpty() ? null : url);

            Map<String, Object> disconnect = new HashMap<>();
            disconnect.put("estado", "desconectado");
            disconnect.put("timestamp", ServerValue.TIMESTAMP);

            deviceRef.onDisconnect().updateChildren(disconnect);

            deviceRef.updateChildren(
                    data,
                    (DatabaseError error, DatabaseReference ref) -> {
                        if (error != null) {
                            Log.e(
                                    TAG,
                                    "Error RTDB code=" + error.getCode()
                                            + " mensaje=" + error.getMessage()
                                            + " detalles=" + error.getDetails()
                            );

                            programarReintento(
                                    appContext,
                                    url,
                                    intento + 1
                            );

                        } else {
                            Log.i(
                                    TAG,
                                    "Registro confirmado: "
                                            + DATABASE_NODE + "/" + androidId
                                            + " estado="
                                            + (url.isEmpty()
                                            ? "pendiente"
                                            : "conectado")
                            );

                            // Inicia el listener de tickets y revocaciones
                            // usando la identidad Firebase anónima del dispositivo.
                            DeviceSessionManager.initialize(
                                    appContext,
                                    androidId
                            );
                        }
                    }
            );

        } catch (Exception e) {
            Log.e(TAG, "Excepción preparando escritura RTDB.", e);
            programarReintento(appContext, url, intento + 1);
        }
    }

    private static void programarReintento(
            Context context,
            String url,
            int intento
    ) {
        if (intento > MAX_REINTENTOS) {
            Log.e(TAG, "Se agotaron los reintentos de Firebase.");
            return;
        }

        long delay = Math.min(
                60000L,
                2000L * (1L << Math.min(intento - 1, 5))
        );

        Log.i(TAG, "Reintento " + intento + " en " + delay + " ms");

        new Handler(Looper.getMainLooper()).postDelayed(
                () -> ejecutarRegistro(context, url, intento),
                delay
        );
    }
}
