package com.example.detectcamera;

import android.content.Context;

/**
 * Configuración de acceso web sin HTTP Basic.
 * No crea, almacena ni muestra usuarios o contraseñas.
 */
public final class WebAccessSettings {
    private WebAccessSettings() { }

    /** Compatibilidad con WebServerManager de versiones anteriores. */
    public static void apply(Context context, WebServer server) {
        // Elimina también el archivo cifrado heredado que contenía usuario/contraseña.
        // minSdk del proyecto es 26, por lo que deleteSharedPreferences está disponible.
        if (context != null) {
            context.getApplicationContext().deleteSharedPreferences("prux_web_access");
        }
        // No se configura autenticación Basic.
    }

    public static String getDisplayText(Context context) {
        return "Acceso local sin contraseña\n"
                + "En la misma red Wi-Fi abre la dirección IP del teléfono en el puerto 8080.\n"
                + "El acceso remoto se autoriza desde tu sesión de administrador en PRUX Central.";
    }
}
