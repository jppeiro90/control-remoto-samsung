# Control Samsung para Android

App Android con interfaz local empaquetada y conexión nativa directa al Samsung UN50TU7000 por Wi‑Fi. **No necesita servidor, computadora encendida ni cuenta de Samsung en la app.** Compatible desde Android 8; incluye el S21 FE y Moto G14 si conservan Android 8 o posterior.

## Instalar y vincular

1. Copiá `Control-Samsung-Android.apk` al teléfono y abrilo. Si Android lo solicita, permití instalar desde la aplicación con la que abriste el archivo. Es una compilación de prueba firmada, para instalación directa; no está publicada en Play Store.
2. Conectá teléfono y televisor a la misma red Wi-Fi y encendé el TV.
3. Tocá el botón superior de ajustes y luego **Buscar mi televisor**, o ingresá la IP del TV manualmente. En el TU7000: Configuración → General → Red → Estado de red → Configuración IP. Los rótulos pueden variar.
4. Presioná **Conectar televisor** y aceptá **Control Samsung Android** en el televisor. Para esa primera autorización necesitás el control físico o los botones del TV.
5. Repetí la autorización en el otro teléfono; cada uno guarda su propio permiso localmente.

Si la app pasa a segundo plano, cierra la conexión. Al regresar intenta reconectar al último TV. Los permisos guardados se reutilizan.

## Netflix, HBO Max y otras apps

- Accesos rápidos a Netflix, HBO Max, YouTube, Prime Video, Disney+ y Spotify.
- **Apps del TV** consulta la lista instalada y usa los identificadores que el propio TV devuelve. Si encuentra Netflix/HBO Max, usa esa información para los accesos rápidos.
- Los accesos consultan por HTTP local los identificadores disponibles, ignoran los que el TV responde como inexistentes y abren la app instalada. No hace falta activar un método alternativo. Si el firmware no devuelve la lista completa, la pestaña Apps consulta los identificadores conocidos para mostrar los que el TV informa instalados.
- La app distingue entre apertura confirmada por `visible: true`, pedido aceptado sin confirmación de visibilidad y error. Si HTTP no está disponible, intenta el canal WebSocket y lo informa como un pedido sin confirmar. Las apps deben estar instaladas y tener la sesión iniciada en el televisor. El control no incluye suscripciones ni reproduce catálogos en el teléfono.

## Encendido y permisos

El botón Apagar funciona con la conexión activa. El encendido opcional usa Wake-on-LAN: requiere la MAC de la interfaz usada por el TV y que el firmware/red admitan encendido por red con “Encender con el móvil”. Enviar la señal no garantiza el encendido.

La app solo permite destinos IPv4 de redes privadas. Necesita acceso a red y multicast para descubrir el TV. En versiones Android que lo exijan solicita permiso de red local. No usa ubicación, micrófono, contactos ni credenciales de servicios de streaming. Guarda IP, token y huella de certificado en almacenamiento privado, sin copias de seguridad. El TLS del TV usa confianza en el primer uso y fija su huella para las conexiones siguientes. Para reemplazar TV/certificado usá **Ayuda / volver a vincular → Olvidar vínculo**.

## Compilar

Abrí esta carpeta en Android Studio, usá JDK 17 y SDK 35, y ejecutá `testDebugUnitTest assembleDebug lintDebug` con Gradle 8.11.1. AGP: 8.9.2.

En esta PC `python prepare_build.py` descarga herramientas portátiles oficiales a `%LOCALAPPDATA%/ControlSamsungBuild`, prepara SDK 35 y genera `Control-Samsung-Android.apk` en la raíz del proyecto sin cambiar PATH ni iniciar servidores. Requiere el paquete Python `requests`. En la primera preparación se aceptan las licencias estándar del SDK Android. Los archivos temporales de compilación quedan fuera de OneDrive para evitar bloqueos de sincronización.

Pruebas unitarias: destinos locales, URL de vinculación, codificación de token, protocolo de teclas, apertura de apps y lectura de identificadores instalados. Compilación y lint no sustituyen la prueba real de vinculación en el televisor ni una prueba en cada teléfono.

Referencias: [protocolo comunitario Samsung](https://github.com/xchwarze/samsung-tv-ws-api), [identificadores de aplicaciones](https://github.com/xchwarze/samsung-tv-ws-api/blob/master/APPLICATIONS.md), [permisos de red local Android](https://developer.android.com/privacy-and-security/local-network-permission).

## Versión 2.0

Control con cruceta circular, botones de volumen/canales verticales, símbolos SVG y paneles separados de Apps y Teclado. Los ajustes se abren desde el botón superior o el indicador de conexión. Seis logos originales empaquetados en la app, obtenidos de las fichas oficiales de Netflix, HBO Max, Disney+, YouTube, Prime Video y Spotify (fuentes en `app/src/main/assets/ui/logos/sources.json`). La interfaz funciona sin descargar recursos externos; WebView solo carga los recursos incluidos en el APK, con navegación externa y archivos del dispositivo bloqueados.

## Versión 2.1

Apagado usa KEY_POWER (una sola pulsación, sin reintento automático). Los toques repetidos de encendido se filtran durante 2,5 segundos para evitar alternancias accidentales. Fuente usa una pulsación Press/Release de 180 ms; mantener presionado su botón abre alternativas de clic simple y acceso a Inicio para elegir la entrada. Los comandos de teclas se envían en orden y cada pulsación mantenida termina con Release. Los errores del TV se muestran en pantalla. El efecto de cada tecla todavía depende del firmware y requieren verificación en el TV real.

## Versión 2.2

Corrige los accesos que solo enviaban `ed.apps.launch` sin comprobar el resultado. Ahora consulta `GET /api/v2/applications/{id}`, abre con `POST` y vuelve a consultar el estado. La resolución prueba variantes regionales (en el TU7000 probado, Disney+ usa `3201901017640`). La lista de Apps tiene una alternativa HTTP cuando el TV ignora la consulta WebSocket. El estado `running` no se trata como prueba de que la app esté en primer plano; tampoco se omite el POST basándose en un indicador `visible` previo que pueda estar desactualizado.

Validación: 24 pruebas unitarias, compilación/lint y prueba directa contra un Samsung UN50TU7000. YouTube abrió mediante HTTP y su apertura fue confirmada visualmente por el usuario; también confirmó que las otras pruebas estaban abriendo apps. La visibilidad informada por el TV puede tardar en actualizarse.
