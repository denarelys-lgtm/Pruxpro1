# Configuración Firebase + PRUX Central

Esta integración separa dos responsabilidades:

- **Firebase Hosting + Authentication + Realtime Database:** panel central, acceso administrativo y directorio de teléfonos.
- **Cloudflare Quick Tunnel + WebServer Android:** acceso remoto al panel individual, cámara, pantalla y audio. Firebase no retransmite esos medios.

## 1. Registrar la aplicación web de Firebase

1. Abre Firebase Console y selecciona el proyecto `detectcameracentral`.
2. En Configuración del proyecto > General > Tus aplicaciones, registra una aplicación **Web**.
3. Copia el `appId` que Firebase entrega para la aplicación web.
4. Abre `web/index.html` y sustituye `REEMPLAZAR_CON_FIREBASE_WEB_APP_ID` por ese `appId`.
5. Los demás valores de configuración ya corresponden al proyecto de este repositorio. Si Firebase muestra valores distintos para la aplicación web, reemplaza el objeto `firebaseConfig` completo con el que entrega la consola.

El `appId` Android de `google-services.json` no debe usarse como sustituto del `appId` web.

## 2. Activar los proveedores de inicio de sesión

En Firebase Console > Authentication > Sign-in method, habilita:

- **Anonymous:** lo usa cada teléfono PRUX para registrar su propio estado.
- **Email/Password:** lo usa el administrador para entrar al panel web.

Crea desde Authentication > Users una cuenta de correo y contraseña para el administrador del panel.

## 3. Autorizar al administrador para leer los dispositivos

1. En Authentication > Users, abre el usuario administrador y copia su **UID**.
2. Abre Realtime Database > Data.
3. Crea esta entrada:

```json
{
  "admins": {
    "PEGA_AQUI_EL_UID_DEL_ADMIN": true
  }
}
```

No uses el correo como clave: debe ser el UID exacto.

## 4. Reglas de Realtime Database

El archivo `database.rules.json` contiene las reglas que usa esta versión:

- Los dispositivos autenticados anónimamente solo pueden escribir el registro cuyo `firebaseUid` coincide con su sesión.
- El panel solo puede leer `dispositivos` si el UID autenticado está autorizado en `/admins`.
- Los clientes no pueden modificar `/admins`.
- No se habilita lectura pública.

Despliega estas reglas junto con Hosting mediante el flujo de GitHub descrito abajo, o publícalas desde Realtime Database > Rules copiando el contenido de `database.rules.json`.

## 5. Activar Firebase Hosting y publicar PRUX Central

En Firebase Console > Hosting, inicia la configuración de Hosting para el proyecto. El flujo de GitHub también intenta crear el sitio predeterminado `detectcameracentral` si todavía no existe.

El repositorio incluye `.github/workflows/firebase-deploy.yml`. Para habilitarlo:

1. En Google Cloud Console del proyecto, crea una cuenta de servicio con permisos para Firebase Hosting y Realtime Database Rules (Firebase Admin es el rol amplio más sencillo, pero puedes asignar roles mínimos equivalentes).
2. Genera una clave JSON para esa cuenta de servicio.
3. En GitHub > Settings > Secrets and variables > Actions, crea el secreto `FIREBASE_SERVICE_ACCOUNT` y pega el JSON completo como valor.
4. Haz push a `main`/`master` o ejecuta Actions > Deploy PRUX Firebase Hosting and Database Rules > Run workflow.
5. Cuando finalice, abre `https://detectcameracentral.web.app` (o el dominio que muestre Firebase Hosting).

No subas la clave JSON de la cuenta de servicio al repositorio ni la incluyas en el APK.

## 6. Usar el panel

1. Abre el sitio de Hosting desde cualquier navegador, móvil o PC.
2. Inicia sesión con la cuenta Email/Password que creaste.
3. El panel mostrará los teléfonos que hayan arrancado el WebServer y completado su registro.
4. Pulsa **Abrir panel remoto** para entrar al WebServer de ese teléfono.
5. El navegador pedirá autenticación HTTP Basic. En la aplicación PRUX, copia el usuario y la contraseña que aparecen en la sección **Acceso web protegido**.

Cada instalación genera una contraseña aleatoria persistente y la conserva cifrada mediante AndroidX Security Crypto / Android Keystore. La aplicación muestra las credenciales en su pantalla principal para que puedas copiarlas al diálogo HTTP Basic del navegador. No compartas esa contraseña ni la URL pública del túnel.

## 7. Límites de Cloudflare Quick Tunnel

La integración actual utiliza `trycloudflare.com` sin cuenta ni dominio propio. La URL es temporal y puede cambiar cuando el túnel se reinicia. PRUX actualiza el registro de Firebase cuando detecta la nueva URL y reintenta levantar el túnel si el proceso termina.

Para una URL estable se necesita un dominio propio y un túnel Cloudflare nombrado con credenciales/configuración administradas; no se debe asumir que el Quick Tunnel ofrece una dirección permanente.

## 8. Diagnóstico en Android

Filtra Logcat por:

- `WebServerManager`: confirma el puerto local 8080.
- `PruxCloudflare`: confirma ejecución del binario, URL pública y reintentos.
- `PruxRegistry`: confirma autenticación anónima y escritura de `dispositivos/<androidId>`.

Si el panel carga pero no muestra teléfonos, comprueba primero que el UID del administrador esté en `/admins` y que Anonymous esté habilitado. Si el teléfono no aparece, comprueba `PruxRegistry`. Si aparece sin URL, comprueba `PruxCloudflare`.
