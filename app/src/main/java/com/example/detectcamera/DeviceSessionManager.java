
package com.example.detectcamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class DeviceSessionManager {

    private static final String TAG = "PruxDeviceSession";

    private static final String DATABASE_URL =
            "https://detectcameracentral-default-rtdb.firebaseio.com/";

    private static final String PREFS_NAME = "prux_device_sessions";

    private static final String KEY_PENDING_HASH = "pending_ticket_hash";
    private static final String KEY_PENDING_EXPIRY = "pending_ticket_expiry";
    private static final String KEY_PENDING_GENERATION = "pending_ticket_generation";

    private static final String KEY_CONSUMED_HASH = "consumed_ticket_hash";

    private static final String KEY_SESSION_COOKIE = "session_cookie";
    private static final String KEY_SESSION_GENERATION = "session_generation";
    private static final String KEY_SESSION_CREATED = "session_created_at";

    private static final String KEY_REMOTE_GENERATION = "remote_generation";
    private static final String KEY_GENERATION_KNOWN = "generation_known";

    private static final long TICKET_WAIT_MS = 5000L;

    private static final Object LOCK = new Object();

    private static volatile SharedPreferences preferences;

    private static DatabaseReference deviceAuthRef;
    private static ValueEventListener listener;
    private static String initializedDeviceId;

    private DeviceSessionManager() {
    }

    private static SharedPreferences getPreferences(Context context) {
        if (preferences != null) {
            return preferences;
        }

        synchronized (LOCK) {
            if (preferences != null) {
                return preferences;
            }

            try {
                Context appContext = context.getApplicationContext();

                MasterKey masterKey = new MasterKey.Builder(appContext)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();

                preferences = EncryptedSharedPreferences.create(
                        appContext,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );

                return preferences;

            } catch (Exception error) {
                throw new IllegalStateException(
                        "No se pudo inicializar el almacenamiento cifrado de sesiones.",
                        error
                );
            }
        }
    }

    /**
     * Inicializa el listener del nodo privado del dispositivo.
     * Debe llamarse después de confirmar el registro en /dispositivos.
     */
    public static void initialize(Context context, String deviceId) {
        if (context == null || deviceId == null || deviceId.trim().isEmpty()) {
            Log.e(TAG, "Contexto o identificador de dispositivo inválido.");
            return;
        }

        synchronized (LOCK) {
            if (deviceId.equals(initializedDeviceId)
                    && listener != null
                    && deviceAuthRef != null) {
                return;
            }

            if (deviceAuthRef != null && listener != null) {
                deviceAuthRef.removeEventListener(listener);
            }

            Context appContext = context.getApplicationContext();
            SharedPreferences prefs = getPreferences(appContext);

            initializedDeviceId = deviceId;

            // Hasta recibir el estado actual de Firebase no se acepta una cookie.
            prefs.edit()
                    .putBoolean(KEY_GENERATION_KNOWN, false)
                    .apply();

            deviceAuthRef = FirebaseDatabase
                    .getInstance(DATABASE_URL)
                    .getReference("deviceAuth")
                    .child(deviceId);

            listener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    procesarEstadoRemoto(snapshot);
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    Log.e(TAG,
                            "Listener deviceAuth cancelado: " + error.getMessage());
                }
            };

            deviceAuthRef.addValueEventListener(listener);
        }
    }

    private static void procesarEstadoRemoto(DataSnapshot snapshot) {
        synchronized (LOCK) {
            SharedPreferences prefs = preferences;

            if (prefs == null) {
                return;
            }

            long generation = leerLong(snapshot.child("generation"), 0L);

            prefs.edit()
                    .putLong(KEY_REMOTE_GENERATION, generation)
                    .putBoolean(KEY_GENERATION_KNOWN, true)
                    .apply();

            // Si la generación cambió, todas las cookies anteriores quedan invalidadas.
            String currentCookie = prefs.getString(KEY_SESSION_COOKIE, "");
            long sessionGeneration = prefs.getLong(KEY_SESSION_GENERATION, -1L);

            if (!currentCookie.isEmpty() && sessionGeneration != generation) {
                Log.i(TAG, "La generación cambió. Sesión local revocada.");
                clearSessionLocked(prefs);
            }

            DataSnapshot ticketSnapshot = snapshot.child("ticket");

            if (!ticketSnapshot.exists()) {
                clearPendingTicketLocked(prefs);
                LOCK.notifyAll();
                return;
            }

            String hash = ticketSnapshot.child("hash").getValue(String.class);
            long expiresAt = leerLong(ticketSnapshot.child("expiresAt"), 0L);
            long ticketGeneration = leerLong(
                    ticketSnapshot.child("generation"), -1L
            );

            if (hash == null
                    || hash.isEmpty()
                    || ticketGeneration != generation
                    || expiresAt <= System.currentTimeMillis()) {

                clearPendingTicketLocked(prefs);
                LOCK.notifyAll();
                return;
            }

            String consumedHash = prefs.getString(KEY_CONSUMED_HASH, "");

            if (hash.equals(consumedHash)) {
                clearPendingTicketLocked(prefs);
                LOCK.notifyAll();
                return;
            }

            prefs.edit()
                    .putString(KEY_PENDING_HASH, hash)
                    .putLong(KEY_PENDING_EXPIRY, expiresAt)
                    .putLong(KEY_PENDING_GENERATION, ticketGeneration)
                    .apply();

            LOCK.notifyAll();

            Log.i(TAG, "Ticket temporal recibido desde Firebase.");
        }
    }

    private static long leerLong(DataSnapshot snapshot, long defaultValue) {
        Object value = snapshot.getValue();

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }

        return defaultValue;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] result = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder output = new StringBuilder();

            for (byte b : result) {
                output.append(String.format("%02x", b & 0xff));
            }

            return output.toString();

        } catch (Exception error) {
            Log.e(TAG, "Error calculando SHA-256.", error);
            return null;
        }
    }

    private static String generarCookie() {
        try {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);

            return Base64.encodeToString(
                    secret,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
            );

        } catch (Exception error) {
            Log.e(TAG, "No se pudo generar la cookie.", error);
            return null;
        }
    }

    /**
     * Canjea un ticket temporal por una cookie persistente.
     * Espera hasta cinco segundos a que el listener reciba el ticket.
     */
    public static String exchangeTicket(String ticket) {
        if (ticket == null || ticket.trim().isEmpty()) {
            return null;
        }

        synchronized (LOCK) {
            SharedPreferences prefs = preferences;

            if (prefs == null) {
                return null;
            }

            String receivedHash = sha256(ticket);

            if (receivedHash == null) {
                return null;
            }

            long deadline = System.currentTimeMillis() + TICKET_WAIT_MS;

            while (prefs.getString(KEY_PENDING_HASH, "").isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();

                if (remaining <= 0L) {
                    return null;
                }

                try {
                    LOCK.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            String expectedHash = prefs.getString(KEY_PENDING_HASH, "");
            long expiresAt = prefs.getLong(KEY_PENDING_EXPIRY, 0L);
            long ticketGeneration = prefs.getLong(
                    KEY_PENDING_GENERATION, -1L
            );

            long remoteGeneration = prefs.getLong(
                    KEY_REMOTE_GENERATION, -1L
            );

            boolean generationKnown = prefs.getBoolean(
                    KEY_GENERATION_KNOWN, false
            );

            String consumedHash = prefs.getString(KEY_CONSUMED_HASH, "");

            if (!generationKnown
                    || expectedHash.isEmpty()
                    || !constantTimeEquals(expectedHash, receivedHash)
                    || receivedHash.equals(consumedHash)
                    || System.currentTimeMillis() >= expiresAt
                    || ticketGeneration != remoteGeneration) {

                return null;
            }

            String cookie = generarCookie();

            if (cookie == null) {
                return null;
            }

            long now = System.currentTimeMillis();

            // apply() actualiza la copia en memoria inmediatamente.
            prefs.edit()
                    .putString(KEY_CONSUMED_HASH, receivedHash)
                    .remove(KEY_PENDING_HASH)
                    .remove(KEY_PENDING_EXPIRY)
                    .remove(KEY_PENDING_GENERATION)
                    .putString(KEY_SESSION_COOKIE, cookie)
                    .putLong(KEY_SESSION_GENERATION, ticketGeneration)
                    .putLong(KEY_SESSION_CREATED, now)
                    .apply();

            Log.i(TAG, "Sesión persistente creada.");

            return cookie;
        }
    }

    public static boolean isSessionValid(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return false;
        }

        SharedPreferences prefs = preferences;

        if (prefs == null) {
            return false;
        }

        if (!prefs.getBoolean(KEY_GENERATION_KNOWN, false)) {
            return false;
        }

        String savedCookie = prefs.getString(KEY_SESSION_COOKIE, "");

        if (savedCookie.isEmpty()) {
            return false;
        }

        long sessionGeneration = prefs.getLong(
                KEY_SESSION_GENERATION, -1L
        );

        long remoteGeneration = prefs.getLong(
                KEY_REMOTE_GENERATION, -2L
        );

        return sessionGeneration == remoteGeneration
                && constantTimeEquals(savedCookie, cookie);
    }

    private static boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }

        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void clearPendingTicketLocked(SharedPreferences prefs) {
        prefs.edit()
                .remove(KEY_PENDING_HASH)
                .remove(KEY_PENDING_EXPIRY)
                .remove(KEY_PENDING_GENERATION)
                .apply();
    }

    private static void clearSessionLocked(SharedPreferences prefs) {
        prefs.edit()
                .remove(KEY_SESSION_COOKIE)
                .remove(KEY_SESSION_GENERATION)
                .remove(KEY_SESSION_CREATED)
                .apply();
    }

    /**
     * Invalida la sesión local del dispositivo.
     */
    public static void clearSession() {
        SharedPreferences prefs = preferences;

        if (prefs == null) {
            return;
        }

        synchronized (LOCK) {
            clearSessionLocked(prefs);
        }
    }
}
