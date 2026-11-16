# Monitor ext G9ii

Prueba 0.1 para una Lumix G9 II (DC-G9M2, firmware 2.7) y un Galaxy S24 Ultra
(SM-S928B, Android 17). Compatibilidad física pendiente de verificación.

## APK de prueba

Abre **Actions**, selecciona la última ejecución exitosa de **Compilar APK Android** y
descarga el archivo **Monitor_Lumix_USB** en la sección **Artifacts**. Extrae la APK del ZIP
antes de instalarla en Android.

## Conexión

1. Abre la aplicación, enciende la cámara y conéctala al teléfono por USB-C con un cable de datos.
2. Selecciona `PC(Tether)` en la cámara y toca **Conectar** en el teléfono.
3. Acepta el permiso USB. Mueve la cámara para comprobar que la imagen se actualiza.
4. Prueba grabar unos segundos desde la cámara. Comprueba si la imagen continúa al iniciar y detener.
5. Si algo falla, toca **Copiar diagnóstico**. El informe incluye la fase y las respuestas USB, sin números de serie ni imágenes.

El valor de fps corresponde a imágenes recibidas y decodificadas. El retardo se mide aparte,
filmando con otro equipo una referencia en movimiento junto con la pantalla del teléfono.
La aplicación solo solicita la vista previa. No guarda imágenes, no usa Internet y no controla
la grabación ni modifica los ajustes de la cámara.

## Compilar

Abre esta carpeta en Android Studio con JDK 17 y Android SDK 35. Importa el proyecto Gradle y
ejecuta la tarea `assembleDebug`. El APK queda en `app/build/outputs/apk/debug/`.

Las operaciones de vista previa por PTP están documentadas en
[libgphoto2](https://github.com/gphoto/libgphoto2/tree/master/camlibs/ptp2).
