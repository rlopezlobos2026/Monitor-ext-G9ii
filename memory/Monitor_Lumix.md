# Memoria de Monitor Lumix G9 II

**Cierre del ciclo del 16 de noviembre de 2026.** Rodrigo solicitó dejar esta distribución como estable y pausar el desarrollo. La versión se identifica como **1.0.0** y el APK mantiene el identificador de la serie 0.5. Las pruebas de CI y los resultados con hardware se registran por separado. Para retomar, partir de la etiqueta o el commit de la versión 1.0.0, revisar la última ejecución de Actions y partir de los pendientes físicos de este archivo.

## Decisiones de la versión estable

| Área | Decisión |
| --- | --- |
| Identidad | Nombre visible **Monitor Lumix G9 II**. La G9 original es otro modelo. El diseño aprobado del ícono tiene fondo oscuro, marco gris de monitor y retícula amarilla. Se adaptó como vector de launcher Android para mantener proporciones y legibilidad. |
| Configuración | Tamaño de botones pequeño (aproximadamente 50 % de la altura normal), normal (predeterminado) y grande (120 %). El ancho se distribuye según el espacio de la pantalla. |
| Distribución | Columna izquierda opcional de botones pequeños para diafragma (apertura), obturación, balance de blancos e ISO. EV y Kelvin siguen disponibles en la fila inferior. |
| Estados | Fondo gris y punto verde dentro del botón. El punto de ISO sigue su panel y el de Scopes sigue la visualización del histograma u otro gráfico. |
| Menús | **Exposición** reúne cebras y falso color. **F. Peaking** abre las ayudas de enfoque. **Más** agrupa guías, color y las demás herramientas secundarias. |
| Captura y vista limpia | Accesos directos a Foto, REC cámara y REC teléfono. Un botón visible oculta y recupera los controles. Los dos indicadores REC siguen visibles. |
| Indicadores retirados | Se retiraron el nivel del teléfono y la distancia focal. El primero medía la inclinación del teléfono y la prueba de focal por PTP devolvió 0x2006. La barra se dedica a la resolución y los fps recibidos. |
| Vista previa USB | Mostrar por separado el modo solicitado, el modo que declara la cámara y el fotograma recibido. El archivo MP4 local tiene su propio ajuste de tamaño. |

## Resultados observados con equipo físico

El 19 de noviembre de 2026, Rodrigo confirmó que probó la aplicación en un **Samsung Galaxy S23 Ultra** y que funcionó. Se registra este resultado como prueba física adicional de compatibilidad.

- Equipo comprobado: Panasonic **DC-G9M2** (firmware 2.7 en la prueba documentada) y **Galaxy S24 Ultra SM-S928B**, Android 17, USB-C directo en PC(Tether). La app requiere Android 13+ y USB host. Se verificaron controles de exposición y balance, REC/STOP de cámara, captura de foto aceptada y guardado de MP4 en el teléfono. La G9 II configurada en 4K a aproximadamente 23,98 fps entregó una vista previa típica de **640 × 360 a 30 fps**. También aparecieron fotogramas 640 × 432 y 640 × 480.
- La Lumix anunció por PTP **1280 × 960**, **960 × 720** y **640 × 480**, todos a 30 fps nominales y con parámetros 450, 250 y 32. La lectura del modo activo informó 320 × 240 a 30 fps, parámetro 0. El tamaño efectivo se toma del JPEG recibido. En ese registro no aparece una orden de cambio al modo más alto. El enlace reportó paquetes bulk de 1024 bytes en ambas direcciones, coherentes con SuperSpeed.
- Las cebras del monitor se mantienen como ayuda visual. Rodrigo confirmó que el MP4 sale limpio al apagar las cebras propias de la Lumix, porque la app recibe esas marcas incorporadas cuando la cámara las entrega en la señal.
- Tras STOP, el registro pasó por READY y BUSY y mostró una espera de imagen. Rodrigo no percibió un corte. Se conserva como observación de diagnóstico, sin solicitud de corrección para esta versión.
- El funcionamiento y el diseño de la distribución 1.0.0 se comprueban también en el emulador. Las observaciones físicas anteriores corresponden a las compilaciones 0.5 probadas por Rodrigo.

## Temas para retomar

1. **Enfocar en el punto tocado, prioridad secundaria.** El toque actual ejecuta AF en el área ya seleccionada en la Lumix y dibuja la marca de interacción. Para mover el área se necesita identificar la orden USB y la codificación de las coordenadas. En Windows, verificar primero si LUMIX Tether y la G9 II permiten mover el área al pulsar Live View. Si funciona, registrar varios puntos separados con Wireshark y USBPcap y analizar el archivo .pcapng. Validar luego que el foco pueda pasar de un sujeto cercano al fondo. [Guía de Panasonic](https://eww.pavc.panasonic.co.jp/dscoi/lumixtether/OperationGuide%28en%29.pdf)
2. **Vista previa USB.** Con la cámara sin grabar, elegir 1280 × 960 en Más → Vista previa USB, esperar unos segundos y copiar el diagnóstico. Comparar la respuesta, la lectura del modo activo y los píxeles/fps efectivos. Hacer el mismo ensayo en LUMIX Tether si es necesario. La calidad 4K elegida en la cámara corresponde a su grabación interna. UHD nativo por PC(Tether) queda sin confirmar.
3. **Compatibilidad.** Probar primero la GH6, que Panasonic agrupa con la G9 II en su matriz de LUMIX Tether. GH7 y S5 II son candidatas. Verificar imagen, disparo, vídeo y ajustes en cada cámara antes de ampliar el nombre a Monitor Lumix. En otros Android con USB host, probar tamaños de pantalla, letra, toque, permisos USB y codificación de MP4. [Matriz de Panasonic](https://av.jpn.support.panasonic.com/support/global/cs/soft/download/d_lumixtether2.html)
4. **Nivel y focal.** Reconsiderar los indicadores si se valida la inclinación procedente de la cámara o una distancia focal fiable en el equipo físico. Revisar las consultas previas en el commit `3014728` si se retoma ese trabajo.
