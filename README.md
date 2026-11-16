# Monitor ext G9ii

Monitor Android para la Panasonic Lumix G9 II por USB‑C directo. La prueba 0.1
confirmó 640 × 360 y 30 fps recibidos con firmware 2.7 y un Galaxy S24 Ultra
(SM-S928B) en Android 17. La versión 0.2 se instala aparte para poder compararlas.

## Usar el monitor

1. Enciende la Lumix, conecta un cable USB‑C de datos y selecciona `PC(Tether)`.
2. Abre **Monitor Lumix G9 II**, toca **Conectar** y concede el acceso USB.
3. Tras la primera imagen, los controles se ocultan. Toca el video para volver a mostrarlos.
4. En **Vista**, elige **Ajustar** para ver toda la imagen o **Llenar** para aprovechar
   todo el ancho recortando parte del encuadre. También hay vista píxel a píxel,
   doble toque para ampliar, zoom con dos dedos y arrastre para inspeccionar el encuadre.
5. Para explorar tamaños mayores, entra en **Más → Calidad USB**. El menú muestra las
   opciones que anuncia la cámara y sus frecuencias nominales. La barra indica la
   resolución y los fps realmente recibidos. La conexión arranca en el modo normal
   de la cámara en cada sesión. Al desconectar desde la app, se intenta restaurar
   la calidad inicial si se obtuvo al consultar el dispositivo.

## Ayudas en pantalla

| Control | Funciones |
| --- | --- |
| Foco | Peaking con tres intensidades y color configurable, zoom 2× y 4× |
| Exposición | Dos zebras de umbral regulable y falso color con leyenda |
| Guías | Tercios, centro, área segura, máscara y marcos 2.39:1, 1.85:1, 16:9, 4:3, 1:1, 4:5 y 9:16 |
| Scopes | Histograma RGB, forma de onda de luma, parada RGB y vectorscopio con tamaño normal o ampliado |
| Color | LUT `.cube` 1D o 3D, monitor azul y brillo |
| Más | Resolución USB, volteo horizontal y vertical, corrección anamórfica y diagnóstico USB |

Las ayudas de exposición y los gráficos miden los niveles de la vista previa JPEG
recibida por USB, antes de aplicar la LUT. Una LUT se usa solo para monitorización.
El resultado de exposición se debe contrastar con la medición de la cámara en tomas críticas.

## Grabar en el teléfono

El interruptor **Grabar en teléfono** arranca apagado y se habilita al recibir video.
Enciéndelo para iniciar un clip y apágalo para guardarlo. El indicador rojo
**REC · TELÉFONO** permanece visible al ocultar los controles. El MP4 H.264 se guarda
en la carpeta `Movies/Monitor Lumix` y aparece en la galería del teléfono. Desconectar
desde la app, quitar el cable o salir al fondo finaliza la grabación si el archivo se
puede cerrar. La grabación usa los fotogramas que llegan por USB, con un techo de
1920 × 1080 y una tasa nominal de 30 fps. Se guarda sin audio, gráficos, LUT ni
recorte de pantalla. El interruptor no acciona la grabación interna de la Lumix.

## Señal de la Lumix G9 II

Panasonic documenta grabación de 5760 × 4320 (5.8K), 5728 × 3024 (5.7K),
4352 × 3264 (4.4K), 4096 × 2160 (C4K), 3840 × 2160 (4K UHD),
3840 × 2880 (3.8K MP4 Lite) y 1920 × 1080 (FHD). Las frecuencias y formas de
almacenamiento dependen del modo. La salida HDMI estándar es una interfaz
distinta y admite C4K/4K hasta 50/59.94p según el modo. La vista previa por
`PC(Tether)` se comprueba en el dispositivo y el manual no publica un modo USB 4K.

- [Manual de la G9 II](https://eww.pavc.panasonic.co.jp/dscoi/DC-G9M2/pdf/DC-G9M2_DVQP3010_full_eng.pdf)
- [Salida de video HDMI](https://eww.pavc.panasonic.co.jp/dscoi/DC-G9M2/html/DC-G9M2_DVQP3010_eng/0100.html)
- [Registro MP4 Lite](https://eww.pavc.panasonic.co.jp/dscoi/DC-G9M2/html/DC-G9M2_DVQP3010_eng/0176.html)
- [Referencia de herramientas de monitor](https://www.atomos.com/tech-specs/shinobi-ii/)
- [Comandos de vista previa PTP en libgphoto2](https://github.com/gphoto/libgphoto2/tree/master/camlibs/ptp2)

## Compilar

El proyecto usa JDK 17, SDK Android 35 y Gradle 8.9. Abre la carpeta en Android Studio
o ejecuta `gradle :app:lintDebug :app:assembleDebug`. La APK de prueba queda en
`app/build/outputs/apk/debug/`. En GitHub, entra a **Actions → Compilar APK Android**
y abre una ejecución exitosa para descargar el ZIP de **Artifacts**.
