# Monitor Lumix G9 II

Monitor Android para la Panasonic Lumix G9 II por USB‑C directo. La prueba 0.1
confirmó 640 × 360 y 30 fps recibidos con firmware 2.7 y un Galaxy S24 Ultra
(SM-S928B) en Android 17. Esta distribución requiere Android 13 o superior y USB host (OTG).

También se probó en un **Samsung Galaxy S23 Ultra** y funcionó, según la confirmación de Rodrigo.

## Usar el monitor

1. Enciende la Lumix, conecta un cable USB‑C de datos y selecciona `PC(Tether)`.
2. Abre **Monitor Lumix G9 II**, toca **Conectar** y concede el acceso USB.
3. **AF área** está en la barra superior y arranca activo. Toca el video para
   accionar el enfoque en el área ya seleccionada en la Lumix. Mantén el dedo
   pulsado para ocultar o mostrar los controles, o usa el botón **Vista limpia**.
   En **Configuración**, elige **Pequeño**, **Normal** o **Grande**, y activa **Controles a la izquierda**
   si quieres diafragma, obturación, balance de blancos e ISO en una columna compacta.
   Los botones muestran un punto verde mientras su ajuste o su herramienta están activos.
   Los ajustes numéricos abren un deslizador en la misma pantalla, con botones
   −, + y **Aplicar**. Los valores que informa la Lumix se muestran en la banda.
4. En **Vista**, elige **Ajustar** para ver toda la imagen o **Llenar** para aprovechar
   todo el ancho recortando parte del encuadre. También hay vista píxel a píxel,
   doble toque para ampliar, zoom con dos dedos y arrastre para inspeccionar el encuadre.
5. En **Más → Vista previa USB**, el menú muestra los modos que anuncia la cámara
   y sus frecuencias nominales. Tras elegir un modo, la app muestra el tamaño solicitado y el tamaño y los fps recibidos. **Más → Copiar diagnóstico** incluye la respuesta al cambio, el
   modo leído de la cámara y el tamaño del video recibido después de la selección. La conexión arranca en el modo normal
   de la cámara en cada sesión. Al desconectar desde la app, se intenta restaurar
   la calidad inicial si se obtuvo al consultar el dispositivo.

## Ayudas en pantalla

| Control | Funciones |
| --- | --- |
| F. Peaking | Peaking con tres intensidades y color configurable, zoom 2× y 4× |
| Exposición | Dos zebras de umbral regulable y falso color con leyenda |
| Más → Guías de encuadre | Tercios, centro, área segura, máscara y marcos 2.39:1, 1.85:1, 16:9, 4:3, 1:1, 4:5 y 9:16 |
| Scopes | Histograma RGB, forma de onda de luma, parada RGB y vectorscopio con tamaño normal o ampliado |
| Más → Color de monitor | LUT `.cube` 1D o 3D, monitor azul y brillo |
| Más | Resolución USB, volteo horizontal y vertical, corrección anamórfica y diagnóstico USB |

Las ayudas de exposición y los gráficos miden los niveles de la vista previa JPEG
recibida por USB, antes de aplicar la LUT. Una LUT se usa solo para monitorización.
El resultado de exposición se debe contrastar con la medición de la cámara en tomas críticas.

## Grabar en el teléfono

El interruptor **REC teléfono** arranca apagado y se habilita al recibir video.
Enciéndelo para iniciar un clip y apágalo para guardarlo. El indicador rojo
**REC TELÉFONO** y su contador permanecen visibles al ocultar los controles. El MP4 AVC o HEVC se guarda
en la carpeta `Movies/Monitor Lumix` y aparece en la galería del teléfono. Desconectar
desde la app, quitar el cable o salir al fondo finaliza la grabación si el archivo se
puede cerrar. En **Más → Grabación en el teléfono** elige el tamaño según la señal USB, Full HD
o 4K UHD (3840 × 2160, cuando el codificador del teléfono lo permite). La tasa
configurada es 30 fps. Si la señal USB es menor, el resultado se amplía para
llenar la mayor área posible del MP4 conservando el aspecto. La ampliación
mantiene el detalle original. El MP4 se codifica directamente a partir de la
señal USB, sin audio ni las ayudas de la app, incluidas zebras, guías y LUT.
Si la Lumix incorpora gráficos en la señal que entrega, esos gráficos forman parte
de la imagen. El interruptor no acciona la grabación interna de la Lumix.

## Controles de cámara por USB

- **Cámara** consulta los valores que la Lumix anuncia para ISO, diafragma,
  obturación, compensación EV, balance de blancos, Kelvin, estilo de foto,
  formato y modo de enfoque. La app
  escribe únicamente valores de esa lista. La disponibilidad depende del modo
  y del lente.
- **AF ahora** envía una orden de enfoque puntual en el área activa de la
  Lumix. **AF área** permite enviar la misma orden tocando el video.
  La marca amarilla indica el toque que disparó la solicitud. La posición del
  área se selecciona en la Lumix.
  El menú incluye pasos de
  enfoque manual, que la cámara puede aceptar cuando está en MF.
- **REC cámara** inicia o detiene la grabación interna. La app consulta el
  estado por USB, también cuando la grabación se inicia desde el cuerpo.
  El marco rojo se muestra cuando la cámara confirma REC. El contador mide
  tiempo desde la primera confirmación observada. La app muestra un aviso si
  la cámara acepta la orden y no envía un estado que la confirme.
- **Calidad de video en la Lumix** consulta y registra los datos USB de calidad
  interna. La selección se realiza en **Calidad grab.** en la cámara.
- **Disparar foto** envía la orden de obturador a la Lumix.
- **Consultar capacidades USB** registra respuestas de identificación,
  área AF y estilo de foto. La selección y los nombres de la **Biblioteca LUT**
  interna siguen disponibles en la cámara o en LUMIX Lab. Si la cámara
  anuncia **Real Time LUT** como estilo, es posible activarlo y usar la LUT
  que ya está seleccionada en la Lumix. Las LUT en **Color**
  corresponden a la previsualización local.

Las pruebas con la G9 II y Android 17 confirmaron ISO, obturación,
balance de blancos, REC y STOP de la Lumix y guardado local en el teléfono.
En el emulador se prueban la interpretación de estados, la serialización de
los ajustes, las herramientas del monitor y la creación de MP4.

## Kelvin

En **Kelvin**, elige primero **Temperatura 1 a 4** en **Modos WB**. La G9 II
admite 2500–10000 K. Si la Lumix no entrega la lista por USB, el deslizador
usa ese rango en pasos de 100 K solo si puede leer un valor Kelvin válido
y verificar que el WB esté en un modo K. La confirmación del ajuste se lee
de vuelta por USB.

- [Panasonic G9 II, ajuste del balance de blancos](https://eww.pavc.panasonic.co.jp/dscoi/DC-G9M2/html/DC-G9M2_DVQP3010_eng/0069.html)
- [Atomos Shinobi II, controles de cámara](https://support.atomos.com/hc/en-us/articles/10342487969807-Camera-Control)
- [SmallHD, controles táctiles en PageOS](https://guide.smallhd.com/a/1714451-user-interface-quick-start-touchscreen)

## Señal de la Lumix G9 II

Panasonic documenta grabación de 5760 × 4320 (5.8K), 5728 × 3024 (5.7K),
4352 × 3264 (4.4K), 4096 × 2160 (C4K), 3840 × 2160 (4K UHD),
3840 × 2880 (3.8K MP4 Lite) y 1920 × 1080 (FHD). Las frecuencias y formas de
almacenamiento dependen del modo. La salida HDMI estándar es una interfaz
distinta y admite C4K/4K hasta 50/59.94p según el modo. En la sesión
USB de firmware 2.7 la cámara anunció 1280 × 960, 960 × 720 y 640 × 480
a 30 fps nominales. La imagen inicial recibida en video 16:9 fue 640 × 360.
La app muestra en la barra superior el tamaño real del fotograma que recibe.

- [Manual de la G9 II](https://eww.pavc.panasonic.co.jp/dscoi/DC-G9M2/pdf/DC-G9M2_DVQP3010_full_eng.pdf)
- [Salida de video HDMI](https://eww.pavc.panasonic.co.jp/dscoi/DC-G9M2/html/DC-G9M2_DVQP3010_eng/0100.html)
- [Registro MP4 Lite](https://eww.pavc.panasonic.co.jp/dscoi/DC-G9M2/html/DC-G9M2_DVQP3010_eng/0176.html)
- [Referencia de herramientas de monitor](https://www.atomos.com/tech-specs/shinobi-ii/)
- [Controles Panasonic sobre USB en Tethr](https://github.com/baku89/tethr/blob/main/core/src/TethrPTPUSB/TethrPanasonic.ts)
- [Comandos de vista previa PTP en libgphoto2](https://github.com/gphoto/libgphoto2/tree/master/camlibs/ptp2)

## Compilar

El proyecto usa JDK 17, SDK Android 35 y Gradle 8.9. Abre la carpeta en Android Studio
o ejecuta `gradle :app:lintDebug :app:assembleDebug`. La APK de prueba queda en
`app/build/outputs/apk/debug/`. En GitHub, entra a **Actions → Compilar APK Android**
y abre una ejecución exitosa para descargar el ZIP de **Artifacts**.

## Memoria del proyecto

Las decisiones, los resultados de las pruebas físicas y los temas pendientes están en la [memoria del proyecto](memory/Monitor_Lumix.md).
