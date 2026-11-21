# PRUX – endurecimiento de persistencia

Esta actualización conserva los módulos existentes y añade recuperación escalonada.

## Cambios incluidos

- El motor ADB permite conceder `WRITE_SECURE_SETTINGS` al propio paquete y aplica `RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND` y la lista blanca de Doze.
- El arranque/reconexión ADB solicita estos privilegios sin depender de Bugjaeger. La concesión solo funciona cuando la sesión ADB de PRUX ya está emparejada y autorizada.
- `ServerService` incorpora un watchdog de 45 segundos para comprobar WebServer, Cloudflare y el monitor ADB.
- El watchdog intenta recuperar `CameraService` con limitación de frecuencia; Android puede rechazarlo por sus restricciones de cámara/micrófono en segundo plano.
- Al quitar la tarea de Recientes se programa un respaldo no exacto para `ServerService`, sin exigir `SCHEDULE_EXACT_ALARM`.
- Se añade el evento de actualización de la aplicación al receptor de arranque.
- Se deja de intentar fabricar un permiso/token `PROJECT_MEDIA`: MediaProjection sigue requiriendo el consentimiento oficial del usuario.

## Límites que permanecen

Ninguna aplicación Android ordinaria puede garantizar ejecución perpetua frente a apagado, reinicio, detención forzada por el usuario, revocación de ADB, falta de permisos, presión extrema de memoria o políticas del fabricante. Después de `Force stop`, Android bloquea los receptores y trabajos de la aplicación hasta que el usuario la abra de nuevo. La cámara, el micrófono y MediaProjection siguen sujetos a las reglas de privacidad y foreground services de la versión de Android instalada.

## Validación recomendada en el dispositivo

1. Emparejar PRUX con Wireless Debugging y confirmar que el estado ADB aparece conectado.
2. Revisar en Ajustes de Android que PRUX está excluido de optimización de batería. La lista blanca Doze no reemplaza la autorización del fabricante para autoinicio.
3. Reiniciar el teléfono y comprobar `ServerService`, WebServer, Cloudflare y ADB en Logcat/panel.
4. Apagar la pantalla durante al menos 10 minutos y comprobar que el servidor sigue respondiendo.
5. Probar cámara, micrófono y pantalla por separado, concediendo los diálogos oficiales cuando Android los solicite.
6. Probar quitar PRUX de Recientes; no usar Ajustes > Forzar detención como prueba de persistencia, porque esa acción bloquea el reinicio automático por diseño de Android.

## Nota de compilación

El ZIP de origen no incluye `gradlew` ni el JAR del wrapper, por lo que la compilación no se puede ejecutar desde este paquete tal como fue entregado. Debe abrirse con Android Studio o compilarse con una instalación local de Gradle compatible con el Android Gradle Plugin del proyecto.
