package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mantiene un Cloudflare Quick Tunnel hacia el WebServer local. */
public final class CloudflareTunnelManager {
    private static final String TAG = "PruxCloudflare";
    private static final Pattern URL_TUNEL = Pattern.compile(
            "https://(?!api\\.)[a-z0-9-]+\\.trycloudflare\\.com\\b");

    private static volatile Process tunnelProcess;
    private static volatile boolean requested;
    private static volatile long generation;

    private CloudflareTunnelManager() { }

    /** Indica si existe un ciclo de supervisión/reintento solicitado. */
    public static boolean isRequested() { return requested; }

    public static synchronized void iniciarTunel(Context context) {
        if (requested) {
            Log.i(TAG, "El túnel ya está solicitado.");
            return;
        }
        requested = true;
        final long runGeneration = ++generation;
        Context appContext = context.getApplicationContext();
        new Thread(() -> ejecutarBucle(appContext, runGeneration), "Prux-Tunnel-Thread").start();
    }

    private static void ejecutarBucle(Context context, long runGeneration) {
        int intento = 0;
        while (requested && generation == runGeneration) {
            Process process = null;
            try {
                if (!WebServerManager.isRunning()) {
                    Log.e(TAG, "No se inicia Cloudflare: WebServer no está escuchando.");
                    break;
                }

                File binary = new File(context.getApplicationInfo().nativeLibraryDir,
                        "libcloudflared.so");
                if (!binary.isFile()) {
                    Log.e(TAG, "No existe el ejecutable cloudflared: " + binary);
                    break;
                }
                if (!binary.canExecute() && !binary.setExecutable(true, true)) {
                    Log.e(TAG, "No se pudo marcar cloudflared como ejecutable.");
                    break;
                }

                int port = WebServerManager.getPuerto();
                ProcessBuilder builder = new ProcessBuilder(
                        binary.getAbsolutePath(), "tunnel", "--no-autoupdate",
                        "--url", "http://127.0.0.1:" + port);
                builder.redirectErrorStream(true);
                process = builder.start();
                tunnelProcess = process;
                Log.i(TAG, "cloudflared iniciado hacia 127.0.0.1:" + port);

                String ultimaUrlReportada = null;
                try (InputStream input = process.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                    String line;
                    while (requested && generation == runGeneration
                            && (line = reader.readLine()) != null) {
                        Log.d(TAG, line);
                        String url = extraerUrlDeLinea(line);
                        if (url != null && !url.equals(ultimaUrlReportada)) {
                            ultimaUrlReportada = url;
                            Log.i(TAG, "URL pública: " + url);
                            DeviceRegistryManager.registrarDispositivoEnNube(context, url);
                        }
                    }
                }

                int exitCode = process.waitFor();
                Log.w(TAG, "cloudflared terminó con código " + exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Error ejecutando Cloudflare Tunnel", e);
            } finally {
                if (tunnelProcess == process) tunnelProcess = null;
                if (process != null && process.isAlive()) process.destroyForcibly();
            }

            if (!requested || generation != runGeneration) break;
            intento++;
            long espera = Math.min(60000L, 3000L * (1L << Math.min(intento - 1, 4)));
            Log.w(TAG, "Reintentando túnel en " + espera + " ms");
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (generation == runGeneration) {
            requested = false;
            tunnelProcess = null;
        }
    }

    private static String extraerUrlDeLinea(String line) {
        if (line == null) return null;
        Matcher matcher = URL_TUNEL.matcher(line);
        return matcher.find() ? matcher.group() : null;
    }

    public static synchronized void detenerTunel() {
        requested = false;
        generation++;
        Process process = tunnelProcess;
        if (process != null) {
            try {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } catch (Exception e) {
                Log.w(TAG, "Error deteniendo cloudflared", e);
                process.destroyForcibly();
            }
        }
        tunnelProcess = null;
        Log.i(TAG, "Solicitud de detención del túnel completada.");
    }
}
