# PRUX CORE ENGINE – primera integración conservadora

## Qué se añadió
- `PruxApplication`: inicializa el Core al crear el proceso.
- `core/PruxCore`: singleton de coordinación, estado compartido, ejecutor de tareas y muestreo de salud.
- `core/PruxEventBus` y `PruxEvent`: comunicación desacoplada por eventos.
- `core/PruxModuleRegistry` y `PruxModule`: registro descriptivo y contrato para futuros módulos.
- Integración no invasiva de eventos/estados con `ServerService`, `CameraService` y `BootReceiver`.

## Compatibilidad y preservación
Los motores originales, Activities, Services, permisos, dependencias, recursos, rutas HTTP y lógica ADB/cámara/captura no se han sustituido. El Core no inicia ni detiene los servicios existentes, no cambia los permisos y no intenta reiniciar procesos automáticamente. La integración es aditiva: si el Core no se inicializa por alguna excepción, la aplicación continúa con sus componentes anteriores.

## API breve
```java
PruxCore core = PruxCore.get();
if (core != null) {
    core.publish("MY_EVENT", "MyModule", null);
    core.setState("my-module", "RUNNING");
    String state = core.getState("network");
    int taskId = core.execute(() -> {
        // Trabajo corto/no UI
    });
}
```

Suscribirse a eventos:
```java
PruxCore core = PruxCore.get();
PruxEventBus.Listener listener = event -> {
    // No bloquear; el callback corre en el hilo que publica el evento.
};
if (core != null) core.events().subscribe(listener);
// Al dejar de necesitarlo: core.events().unsubscribe(listener);
```

## Límites de esta primera etapa
- El registro describe subsistemas; todavía no carga código externo ni plugins arbitrarios.
- El monitor emite muestras de memoria/heap cada 30 segundos; no promete evitar cierres de Android ni modifica límites del sistema.
- La ejecución de tareas es interna al proceso y se pierde si Android mata el proceso.
- La recuperación automática se deja para una segunda etapa, con políticas específicas por servicio para no provocar bucles de reinicio ni romper MediaProjection, permisos o restricciones de foreground services.

## Verificación
Compilar en Android Studio con el Gradle/SDK configurado por el proyecto. Probar primero instalación/arranque, acceso a la interfaz, ADB, servidor en el puerto 8080, cámara y MediaProjection. Esta modificación no incluye una compilación APK en este entorno.

## Integración remota Firebase + Cloudflare

- `web/` contiene PRUX Central, una web estática desplegable en Firebase Hosting.
- `database.rules.json` restringe la lectura de dispositivos a UID administradores bajo `/admins` y limita la escritura de cada dispositivo a su sesión Firebase anónima.
- `DeviceRegistryManager` publica únicamente metadatos y la URL temporal del túnel; no transporta cámara, pantalla ni audio.
- `CloudflareTunnelManager` espera a que el WebServer esté escuchando, ejecuta el binario ARM64, detecta la URL `trycloudflare.com` y reintenta si el proceso termina.
- `WebAccessSettings` genera credenciales aleatorias y las conserva cifradas con AndroidX Security Crypto. La aplicación las muestra en su pantalla principal para abrir el panel remoto.
- `WebServer` rechaza peticiones sin Basic Authentication. Los endpoints originales de cámara, pantalla y audio permanecen en el mismo servidor.

La publicación del panel y las reglas requiere completar el `appId` de la aplicación Firebase Web, crear el UID administrador y configurar el secreto GitHub `FIREBASE_SERVICE_ACCOUNT`. Consulta `FIREBASE_CONFIGURACION_NECESARIA.md` antes de desplegar.
