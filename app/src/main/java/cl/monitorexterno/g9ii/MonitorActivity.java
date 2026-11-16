package cl.monitorexterno.g9ii;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MonitorActivity extends Activity {
    private static final int IMPORT_LUT = 1;
    private static final String USB_PERMISSION = "cl.monitorexterno.g9ii.monitor.USB_PERMISSION";
    private static final int INK = 0xfff1f8f6;
    private static final int MINT = 0xffacf0dc;
    private final StringBuilder diagnostic = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicReference<VideoFrame> newest = new AtomicReference<>();
    private final AtomicBoolean drawQueued = new AtomicBoolean();
    private final Runnable hideControls = () -> controls(false);
    private UsbManager usbManager;
    private UsbDevice requesting;
    private LivePreview preview;
    private volatile PhoneRecorder recorder;
    private MonitorImage image;
    private TextView status;
    private TextView notice;
    private TextView recBadge;
    private Button connect;
    private Switch recordSwitch;
    private HorizontalScrollView tools;
    private FrameLayout header;
    private boolean showingControls = true;
    private boolean frameSeen;
    private volatile boolean destroyed;
    private int sourceWidth;
    private int sourceHeight;
    private boolean settingSwitch;
    private volatile long lastScopeDraw;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = requesting;
            requesting = null;
            if (device == null || !USB_PERMISSION.equals(intent.getAction())) return;
            if (usbManager.hasPermission(device)) {
                startPreview(device);
            } else {
                showNotice("Android no concedió el acceso USB. Toca Conectar para reintentar.");
                connect.setEnabled(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams window = getWindow().getAttributes();
        window.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(window);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        buildUi();
        immerse();
        registerReceiver(permissionReceiver, new IntentFilter(USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        diagnostic.append("Monitor Lumix USB 0.2\n").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE).append('\n');
        status.setText("USB · Sin cámara");
        showNotice("Conecta la Lumix por USB‑C, selecciona PC(Tether) y toca Conectar.");
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        image = new MonitorImage(this);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        attachGestures();

        header = new FrameLayout(this);
        FrameLayout.LayoutParams headerPosition = new FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP);
        root.addView(header, headerPosition);
        status = label(13, MINT);
        status.setBackground(panel(0xdc0b1718, 18));
        status.setPadding(dp(14), dp(7), dp(14), dp(7));
        FrameLayout.LayoutParams statusPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        statusPosition.setMargins(dp(14), dp(12), 0, 0);
        header.addView(status, statusPosition);

        recordSwitch = new Switch(this);
        recordSwitch.setText("Grabar en teléfono");
        recordSwitch.setTextSize(13);
        recordSwitch.setTextColor(INK);
        recordSwitch.setSwitchPadding(dp(9));
        recordSwitch.setPadding(dp(13), 0, dp(10), 0);
        recordSwitch.setMinHeight(dp(44));
        recordSwitch.setBackground(panel(0xe50b1718, 20));
        recordSwitch.setThumbTintList(ColorStateList.valueOf(INK));
        recordSwitch.setEnabled(false);
        recordSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (settingSwitch) return;
            if (checked) startRecorder();
            else stopRecorder();
        });
        FrameLayout.LayoutParams recordPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        recordPosition.setMargins(0, dp(6), dp(14), 0);
        header.addView(recordSwitch, recordPosition);

        recBadge = label(12, Color.WHITE);
        recBadge.setText("●  REC · TELÉFONO");
        recBadge.setTypeface(null, Typeface.BOLD);
        recBadge.setPadding(dp(11), dp(7), dp(11), dp(7));
        recBadge.setBackground(panel(0xe2a21a31, 15));
        recBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        badgePosition.topMargin = dp(12);
        root.addView(recBadge, badgePosition);

        notice = label(15, INK);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(16), dp(11), dp(16), dp(11));
        notice.setMaxWidth(dp(540));
        notice.setBackground(panel(0xe20b1718, 15));
        FrameLayout.LayoutParams noticePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        noticePosition.setMargins(dp(48), 0, dp(48), 0);
        root.addView(notice, noticePosition);

        tools = new HorizontalScrollView(this);
        tools.setClipToPadding(false);
        tools.setHorizontalScrollBarEnabled(false);
        tools.setPadding(dp(10), dp(4), dp(10), dp(5));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        tools.setFillViewport(true);
        tools.addView(buttons, new FrameLayout.LayoutParams(-2, -2));
        connect = tool(buttons, "Conectar", this::connectOrStop);
        connect.setTextColor(MINT);
        tool(buttons, "Vista", this::viewMenu);
        tool(buttons, "Foco", this::focusMenu);
        tool(buttons, "Exposición", this::exposureMenu);
        tool(buttons, "Guías", this::guideMenu);
        tool(buttons, "Scopes", this::scopeMenu);
        tool(buttons, "Color", this::colorMenu);
        tool(buttons, "Más", this::moreMenu);
        FrameLayout.LayoutParams toolsPosition = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(tools, toolsPosition);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            header.setPadding(cutout.left, cutout.top, cutout.right, 0);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) header.getLayoutParams();
            int height = dp(56) + cutout.top;
            if (params.height != height) {
                params.height = height;
                header.setLayoutParams(params);
            }
            tools.setPadding(dp(10) + cutout.left, dp(4), dp(10) + cutout.right, dp(5) + cutout.bottom);
            return insets;
        });
        setContentView(root);
    }

    private void attachGestures() {
        ScaleGestureDetector scale = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                image.zoomAt(image.zoom * detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        GestureDetector taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) { return image.performClick(); }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (image.zoom > 1) image.resetZoom();
                else image.zoomAt(2, event.getX(), event.getY());
                return true;
            }
            @Override
            public boolean onScroll(MotionEvent first, MotionEvent last, float x, float y) {
                if (!scale.isInProgress() && last.getPointerCount() == 1) image.pan(-x, -y);
                return true;
            }
        });
        image.setOnClickListener(view -> {
            if (frameSeen) controls(!showingControls);
        });
        image.setOnTouchListener((view, event) -> {
            scale.onTouchEvent(event);
            return taps.onTouchEvent(event) || scale.isInProgress();
        });
    }

    private Button tool(LinearLayout group, String text, Runnable action) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(INK);
        button.setTextSize(13);
        button.setMinWidth(dp(55));
        button.setMinimumWidth(dp(55));
        button.setMinHeight(dp(45));
        button.setPadding(dp(11), 0, dp(11), 0);
        button.setBackground(panel(0xe80b1718, 16));
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-2, dp(45));
        position.setMargins(dp(3), 0, dp(3), 0);
        group.addView(button, position);
        button.setOnClickListener(view -> {
            ui.removeCallbacks(hideControls);
            action.run();
        });
        return button;
    }

    private TextView label(int size, int color) {
        TextView text = new TextView(this);
        text.setTextColor(color);
        text.setTextSize(size);
        return text;
    }

    private GradientDrawable panel(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void controls(boolean visible) {
        ui.removeCallbacks(hideControls);
        showingControls = visible;
        header.setVisibility(visible ? View.VISIBLE : View.GONE);
        tools.setVisibility(visible ? View.VISIBLE : View.GONE);
        image.controlsVisible = visible;
        image.invalidate();
        immerse();
    }

    private void immerse() {
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsets.Type.systemBars());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immerse();
    }

    private interface Selection { void choose(int index); }

    private void menu(String title, String[] options, Selection selection) {
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setItems(options, (box, index) -> selection.choose(index))
                .setNegativeButton("Cerrar", null).create();
        dialog.show();
    }

    private void viewMenu() {
        menu("Vista", new String[]{"Ajustar · imagen completa", "Llenar · recorta la imagen",
                "Píxel a píxel · 1:1", "Zoom 2×", "Zoom 4×", "Restablecer zoom", "Ocultar controles"}, index -> {
            switch (index) {
                case 0: case 1: case 2:
                    image.size = MonitorImage.Size.values()[index];
                    image.resetZoom();
                    break;
                case 3: case 4:
                    image.zoomAt(index == 3 ? 2 : 4, image.getWidth() / 2f, image.getHeight() / 2f);
                    break;
                case 5: image.resetZoom(); break;
                case 6: if (frameSeen) controls(false); break;
                default: break;
            }
        });
    }

    private void focusMenu() {
        menu("Asistencia de foco", new String[]{"Peaking: " + (image.peaking == 0 ? "apagado" : "activo"),
                "Color del peaking", "Ampliar 2×", "Ampliar 4×", "Restablecer zoom"}, index -> {
            if (index == 0) {
                menu("Intensidad de peaking", new String[]{"Apagado", "Baja", "Media", "Alta"}, value -> {
                    image.peaking = value;
                    image.invalidate();
                });
            } else if (index == 1) {
                menu("Color de bordes", new String[]{"Verde", "Rojo", "Amarillo", "Cian", "Blanco"}, value -> {
                    int[] colors = {Color.GREEN, Color.RED, Color.YELLOW, Color.CYAN, Color.WHITE};
                    image.peakingColor = colors[value];
                    image.invalidate();
                });
            } else if (index == 4) {
                image.resetZoom();
            } else {
                image.zoomAt(index == 2 ? 2 : 4, image.getWidth() / 2f, image.getHeight() / 2f);
            }
        });
    }

    private void exposureMenu() {
        menu("Exposición · niveles de la vista USB", new String[]{"Zebra amarilla · " + zebraLabel(image.zebraA),
                "Zebra roja · " + zebraLabel(image.zebraB), marked("Falso color", image.displayMode == 1),
                "Apagar ayudas de exposición"}, index -> {
            if (index < 2) {
                zebra(index == 0);
            } else if (index == 2) {
                image.displayMode = image.displayMode == 1 ? 0 : 1;
            } else {
                image.zebraA = -1;
                image.zebraB = -1;
                if (image.displayMode == 1) image.displayMode = 0;
            }
            image.invalidate();
        });
    }

    private void zebra(boolean yellow) {
        menu(yellow ? "Umbral de zebra amarilla" : "Umbral de zebra roja",
                new String[]{"Apagada", "50%", "60%", "65%", "70%", "75%", "80%", "85%", "90%", "95%", "98%", "100%"}, index -> {
            int[] levels = {-1, 50, 60, 65, 70, 75, 80, 85, 90, 95, 98, 100};
            if (yellow) image.zebraA = levels[index];
            else image.zebraB = levels[index];
            image.invalidate();
        });
    }

    private static String zebraLabel(int value) { return value < 0 ? "apagada" : value + "%"; }
    private static String marked(String name, boolean enabled) { return name + (enabled ? " ✓" : ""); }

    private void guideMenu() {
        menu("Guías de encuadre", new String[]{"Relación de aspecto", marked("Cuadrícula de tercios", image.grid),
                marked("Marca central", image.crosshair), marked("Máscara exterior", image.mask),
                "Área segura"}, index -> {
            switch (index) {
                case 0:
                    menu("Relación de aspecto", new String[]{"Sin marco", "2.39:1", "1.85:1", "16:9", "4:3", "1:1", "4:5", "9:16"}, value -> {
                        float[] ratios = {0, 2.39f, 1.85f, 16f / 9, 4f / 3, 1, 4f / 5, 9f / 16};
                        image.guideRatio = ratios[value];
                        image.invalidate();
                    });
                    break;
                case 1: image.grid = !image.grid; break;
                case 2: image.crosshair = !image.crosshair; break;
                case 3: image.mask = !image.mask; break;
                case 4:
                    menu("Área segura", new String[]{"Oculta", "80%", "90%", "93%", "95%"}, value -> {
                        int[] sizes = {0, 80, 90, 93, 95};
                        image.safeArea = sizes[value];
                        image.invalidate();
                    });
                    break;
                default: break;
            }
            image.invalidate();
        });
    }

    private void scopeMenu() {
        VideoScopes.Mode[] modes = VideoScopes.Mode.values();
        String[] options = new String[modes.length + 1];
        for (int i = 0; i < modes.length; i++) options[i] = marked(modes[i].label, modes[i] == image.scope);
        options[modes.length] = marked("Gráfico ampliado", image.scopeLarge);
        menu("Análisis de señal USB", options, index -> {
            if (index == modes.length) image.scopeLarge = !image.scopeLarge;
            else image.setScope(modes[index]);
            image.invalidate();
        });
    }

    private void colorMenu() {
        menu("Color de monitor", new String[]{"Importar LUT .cube", marked("Quitar LUT", image.hasLut()),
                marked("Canal azul", image.displayMode == 2), "Brillo de pantalla"}, index -> {
            if (index == 0) {
                Intent document = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                document.setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(document, IMPORT_LUT);
            } else if (index == 1) {
                image.setLut(null);
            } else if (index == 2) {
                image.displayMode = image.displayMode == 2 ? 0 : 2;
                image.invalidate();
            } else {
                menu("Brillo del monitor", new String[]{"Automático", "30%", "60%", "100%"}, value -> {
                    float[] levels = {-1, 0.3f, 0.6f, 1};
                    WindowManager.LayoutParams window = getWindow().getAttributes();
                    window.screenBrightness = levels[value];
                    getWindow().setAttributes(window);
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_LUT || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri address = data.getData();
        new Thread(() -> loadLut(address), "Lumix-LUT").start();
    }

    private void loadLut(Uri address) {
        try (InputStream stream = getContentResolver().openInputStream(address)) {
            if (stream == null) throw new IOException("No se pudo abrir la LUT.");
            CubeLut lut = CubeLut.read(stream);
            ui.post(() -> {
                if (destroyed) return;
                image.setLut(lut);
                toast("LUT aplicada a la pantalla.");
            });
        } catch (IOException | RuntimeException problem) {
            ui.post(() -> { if (!destroyed) toast(problem.getMessage()); });
        }
    }

    private void moreMenu() {
        menu("Monitor Lumix", new String[]{"Calidad USB", "Corrección anamórfica",
                marked("Voltear horizontal", image.flipHorizontal), marked("Voltear vertical", image.flipVertical),
                "Copiar diagnóstico"}, index -> {
            switch (index) {
                case 0: quality(); break;
                case 1:
                    menu("Corrección anamórfica", new String[]{"Sin corrección", "1.25×", "1.33×", "1.5×", "1.6×", "1.8×", "2×"}, value -> {
                        float[] stretches = {1, 1.25f, 1.33f, 1.5f, 1.6f, 1.8f, 2};
                        image.desqueeze = stretches[value];
                        image.resetZoom();
                    });
                    break;
                case 2: image.flipHorizontal = !image.flipHorizontal; break;
                case 3: image.flipVertical = !image.flipVertical; break;
                case 4: copyDiagnostic(); break;
                default: break;
            }
            image.invalidate();
        });
    }

    private void quality() {
        if (recorder != null) {
            toast("Termina el clip antes de cambiar la señal USB.");
        } else if (preview != null && frameSeen) {
            preview.queryFormats();
            toast("Consultando las resoluciones que anuncia la Lumix.");
        } else {
            toast("Conecta la cámara y espera la primera imagen.");
        }
    }

    private void showFormats(List<UsbVideoFormat> formats, UsbVideoFormat current) {
        if (formats.isEmpty()) {
            toast("La Lumix no publicó tamaños alternativos. Sigue disponible la señal actual.");
            return;
        }
        String[] names = new String[formats.size()];
        for (int i = 0; i < names.length; i++) {
            UsbVideoFormat mode = formats.get(i);
            names[i] = marked(mode.label(), mode.equals(current));
        }
        menu("Calidad USB · elige según los fps", names, index -> {
            if (preview != null && recorder == null) preview.changeFormat(formats.get(index));
        });
    }

    private void connectOrStop() {
        if (preview != null) {
            stopRecorder();
            connect.setEnabled(false);
            recordSwitch.setEnabled(false);
            preview.stop();
            showNotice("Cerrando la sesión USB.");
            return;
        }
        UsbDevice camera = null;
        log("Buscando dispositivos USB.");
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            log("USB " + PtpConnection.hex(device.getVendorId()) + ":" + PtpConnection.hex(device.getProductId()));
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface group = device.getInterface(i);
                log("Interfaz " + i + ", clase " + group.getInterfaceClass() + ", endpoints " + group.getEndpointCount());
            }
            if (device.getVendorId() == LivePreview.PANASONIC_USB_ID) camera = device;
        }
        if (camera == null) {
            showNotice("Android no detecta la Lumix. Revisa el cable y selecciona PC(Tether).");
            return;
        }
        if (usbManager.hasPermission(camera)) {
            startPreview(camera);
            return;
        }
        requesting = camera;
        Intent result = new Intent(USB_PERMISSION).setPackage(getPackageName());
        PendingIntent callback = PendingIntent.getBroadcast(this, 0, result,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        showNotice("Acepta la solicitud de acceso USB de Android.");
        connect.setEnabled(false);
        try {
            usbManager.requestPermission(camera, callback);
        } catch (RuntimeException error) {
            requesting = null;
            connect.setEnabled(true);
            showNotice("Android no pudo solicitar permiso USB. Vuelve a conectar la cámara.");
            log(error.toString());
        }
    }

    private void startPreview(UsbDevice camera) {
        frameSeen = false;
        lastScopeDraw = 0;
        sourceWidth = 0;
        sourceHeight = 0;
        connect.setText("Desconectar");
        connect.setEnabled(true);
        recordSwitch.setEnabled(false);
        preview = new LivePreview(usbManager, camera, new LivePreview.Listener() {
            @Override public void onPhase(String message) {
                ui.post(() -> { if (!destroyed) showNotice(message); });
            }

            @Override public void onFrame(Bitmap frame, double fps) {
                if (destroyed) return;
                PhoneRecorder active = recorder;
                if (active != null) active.submit(frame, System.nanoTime());
                VideoScopes.Mode mode = image.scope;
                long now = SystemClock.elapsedRealtime();
                Bitmap chart = null;
                if (mode != VideoScopes.Mode.OFF && now - lastScopeDraw >= 120) {
                    chart = VideoScopes.measure(frame, mode);
                    lastScopeDraw = now;
                }
                newest.set(new VideoFrame(frame, chart, mode, fps));
                queueDraw();
            }

            @Override public void onFormats(List<UsbVideoFormat> formats, UsbVideoFormat current) {
                ui.post(() -> {
                    if (destroyed || !frameSeen || recorder != null) return;
                    log("Modos anunciados por USB: " + formats.size());
                    for (UsbVideoFormat mode : formats) log(mode.label());
                    showFormats(formats, current);
                });
            }

            @Override public void onNotice(String message) {
                ui.post(() -> { if (!destroyed) toast(message); });
            }

            @Override public void onFinished(String error) {
                ui.post(() -> {
                    preview = null;
                    if (destroyed) return;
                    frameSeen = false;
                    newest.set(null);
                    image.show(null, null, VideoScopes.Mode.OFF);
                    stopRecorder();
                    controls(true);
                    connect.setText("Conectar");
                    connect.setEnabled(true);
                    recordSwitch.setEnabled(false);
                    status.setText("USB · Sin señal");
                    showNotice(error == null ? "Sesión USB cerrada." : error);
                });
            }
        });
        new Thread(preview, "Lumix-USB").start();
    }

    private void queueDraw() {
        if (drawQueued.compareAndSet(false, true)) ui.post(this::drawNewest);
    }

    private void drawNewest() {
        VideoFrame latest = newest.getAndSet(null);
        try {
            if (destroyed || latest == null) return;
            image.show(latest.bitmap, latest.chart, latest.mode);
            boolean newSize = sourceWidth != latest.bitmap.getWidth() || sourceHeight != latest.bitmap.getHeight();
            if (newSize) {
                sourceWidth = latest.bitmap.getWidth();
                sourceHeight = latest.bitmap.getHeight();
                log("Imagen recibida: " + sourceWidth + " × " + sourceHeight);
            }
            if (newSize || Math.abs(latest.fps - shownFps) >= 0.3 || !frameSeen) {
                shownFps = latest.fps;
                String rate = shownFps > 0 ? String.format(Locale.getDefault(), " · %.1f fps", shownFps) : "";
                status.setText("USB  " + sourceWidth + " × " + sourceHeight + rate);
            }
            if (!frameSeen) {
                frameSeen = true;
                notice.setVisibility(View.GONE);
                if (recorder == null) recordSwitch.setEnabled(true);
                ui.postDelayed(hideControls, 4000);
            }
        } finally {
            drawQueued.set(false);
            if (newest.get() != null) queueDraw();
        }
    }

    private double shownFps;

    private void startRecorder() {
        if (!frameSeen || sourceWidth < 2 || sourceHeight < 2 || recorder != null) {
            setRecordingSwitch(false);
            return;
        }
        recordSwitch.setEnabled(false);
        recorder = new PhoneRecorder(this, sourceWidth, sourceHeight, new PhoneRecorder.Listener() {
            @Override public void onRecording() {
                ui.post(() -> {
                    if (destroyed || !frameSeen || !recordSwitch.isChecked()) return;
                    recBadge.setVisibility(View.VISIBLE);
                    recordSwitch.setEnabled(true);
                });
            }
            @Override public void onClosed(String message) {
                ui.post(() -> {
                    recorder = null;
                    if (destroyed) return;
                    recBadge.setVisibility(View.GONE);
                    setRecordingSwitch(false);
                    recordSwitch.setEnabled(frameSeen);
                    toast(message);
                });
            }
        });
    }

    private void stopRecorder() {
        PhoneRecorder active = recorder;
        if (active == null) return;
        recordSwitch.setEnabled(false);
        setRecordingSwitch(false);
        active.stop();
    }

    private void setRecordingSwitch(boolean enabled) {
        settingSwitch = true;
        recordSwitch.setChecked(enabled);
        settingSwitch = false;
    }

    private void showNotice(String message) {
        notice.setText(message);
        notice.setVisibility(View.VISIBLE);
        log(message);
    }

    private void toast(String message) {
        if (message == null) return;
        log(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void log(String entry) {
        if (diagnostic.length() > 12000) diagnostic.delete(0, diagnostic.length() - 8000);
        diagnostic.append(entry).append('\n');
    }

    private void copyDiagnostic() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico Monitor Lumix", diagnostic.toString()));
        Toast.makeText(this, "Diagnóstico copiado", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onStop() {
        stopRecorder();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(hideControls);
        if (preview != null) preview.stop();
        stopRecorder();
        unregisterReceiver(permissionReceiver);
        super.onDestroy();
    }

    private static final class VideoFrame {
        final Bitmap bitmap;
        final Bitmap chart;
        final VideoScopes.Mode mode;
        final double fps;

        VideoFrame(Bitmap bitmap, Bitmap chart, VideoScopes.Mode mode, double fps) {
            this.bitmap = bitmap;
            this.chart = chart;
            this.mode = mode;
            this.fps = fps;
        }
    }
}
