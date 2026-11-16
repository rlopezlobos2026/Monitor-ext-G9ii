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
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MonitorActivity extends Activity {
    private static final int IMPORT_LUT = 1;
    private static final String USB_PERMISSION = "cl.monitorexterno.g9ii.monitor.v04.USB_PERMISSION";
    private static final int INK = 0xfff1f8f6;
    private static final int MINT = 0xffacf0dc;
    private final StringBuilder diagnostic = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicReference<VideoFrame> newest = new AtomicReference<>();
    private final AtomicBoolean drawQueued = new AtomicBoolean();
    private UsbManager usbManager;
    private UsbDevice requesting;
    private LivePreview preview;
    private volatile PhoneRecorder recorder;
    private MonitorImage image;
    private TextView status;
    private TextView lensStatus;
    private final EnumMap<CameraControls.Setting, Button> quickButtons = new EnumMap<>(CameraControls.Setting.class);
    private final EnumMap<CameraControls.Setting, Integer> quickValues = new EnumMap<>(CameraControls.Setting.class);
    private AdjustmentPanel adjustment;
    private CameraControls.Setting pendingList;
    private double focalLength;
    private TextView notice;
    private TextView phoneBadge;
    private TextView cameraBadge;
    private View cameraBorder;
    private Button cameraRecord;
    private boolean touchFocus;
    private CameraControls.RecordingState cameraState = CameraControls.RecordingState.UNKNOWN;
    private Boolean requestedRecording;
    private boolean recordingUnverified;
    private long recordingRequestAt;
    private long cameraStartedAt = -1;
    private long phoneStartedAt = -1;
    private String phoneRecordingSize = "";
    private PhoneVideoQuality phoneQuality = PhoneVideoQuality.USB;
    private final Runnable recordingDisplayTick = this::updateRecordingDisplay;
    private Button connect;
    private Switch recordSwitch;
    private LinearLayout tools;
    private LinearLayout quickControls;
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
        int quality = getPreferences(MODE_PRIVATE).getInt("phoneQuality", 0);
        PhoneVideoQuality[] qualities = PhoneVideoQuality.values();
        if (quality >= 0 && quality < qualities.length) phoneQuality = qualities[quality];
        buildUi();
        immerse();
        registerReceiver(permissionReceiver, new IntentFilter(USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        diagnostic.append("Monitor Lumix USB 0.4\n").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
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
        LinearLayout signal = new LinearLayout(this);
        signal.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams statusPosition = new FrameLayout.LayoutParams(-2, dp(46), Gravity.TOP | Gravity.START);
        statusPosition.setMargins(dp(12), dp(5), 0, 0);
        header.addView(signal, statusPosition);
        status = label(12, MINT);
        status.setTypeface(Typeface.MONOSPACE);
        status.setBackground(panel(0xe81c242d, 8));
        status.setPadding(dp(11), dp(7), dp(11), dp(7));
        signal.addView(status);
        lensStatus = label(13, INK);
        lensStatus.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        lensStatus.setBackground(panel(0xea283441, 8));
        lensStatus.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams lensPosition = new LinearLayout.LayoutParams(-2, -2);
        lensPosition.leftMargin = dp(6);
        signal.addView(lensStatus, lensPosition);
        setFocalDisplay(0);
        lensStatus.setOnClickListener(view -> {
            if (preview != null && frameSeen) {
                preview.refreshFocalLength();
                if (focalLength <= 0) toast("Consultando la distancia focal óptica por USB.");
            }
        });

        recordSwitch = new Switch(this);
        recordSwitch.setText(phoneQualityLabel());
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

        cameraBorder = new View(this);
        GradientDrawable redBorder = panel(Color.TRANSPARENT, 0);
        redBorder.setStroke(dp(4), 0xffff354c);
        cameraBorder.setBackground(redBorder);
        cameraBorder.setVisibility(View.GONE);
        cameraBorder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(cameraBorder, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout indicators = new LinearLayout(this);
        indicators.setOrientation(LinearLayout.VERTICAL);
        indicators.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams badgePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        badgePosition.topMargin = dp(52);
        root.addView(indicators, badgePosition);
        cameraBadge = recordingBadge(indicators, 0xffc31933);
        phoneBadge = recordingBadge(indicators, 0xff164957);

        notice = label(15, INK);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(16), dp(11), dp(16), dp(11));
        notice.setMaxWidth(dp(540));
        notice.setBackground(panel(0xe20b1718, 15));
        FrameLayout.LayoutParams noticePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        noticePosition.setMargins(dp(48), 0, dp(48), 0);
        root.addView(notice, noticePosition);

        tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.VERTICAL);
        tools.setPadding(dp(9), 0, dp(9), dp(5));
        FrameLayout.LayoutParams toolsPosition = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(tools, toolsPosition);
        tools.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            image.controlsHeight = bottom - top;
            image.invalidate();
        });

        adjustment = new AdjustmentPanel(this, this::applySetting, () -> adjustment.clear(),
                () -> requestSetting(CameraControls.Setting.WHITE_BALANCE));
        LinearLayout.LayoutParams editorPosition = new LinearLayout.LayoutParams(-1, -2);
        editorPosition.bottomMargin = dp(4);
        tools.addView(adjustment, editorPosition);

        quickControls = new LinearLayout(this);
        quickControls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams quickPosition = new LinearLayout.LayoutParams(-1, dp(51));
        quickPosition.bottomMargin = dp(4);
        tools.addView(quickControls, quickPosition);
        for (CameraControls.Setting setting : CameraControls.QUICK_SETTINGS) {
            Button button = tool(quickControls, quickLabel(setting) + "\n—", () -> requestSetting(setting));
            button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            button.setTextColor(MINT);
            button.setBackground(panel(0xee21303b, 8));
            button.setEnabled(false);
            quickButtons.put(setting, button);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(actions, new LinearLayout.LayoutParams(-1, dp(48)));
        connect = tool(actions, "Conectar", this::connectOrStop);
        connect.setTextColor(MINT);
        cameraRecord = tool(actions, "REC", this::toggleCameraRecording);
        cameraRecord.setTextColor(0xffff8d99);
        cameraRecord.setEnabled(false);
        tool(actions, "Cámara", this::cameraMenu);
        tool(actions, "Vista", this::viewMenu);
        tool(actions, "Foco", this::focusMenu);
        tool(actions, "Exposición", this::exposureMenu);
        tool(actions, "Guías", this::guideMenu);
        tool(actions, "Scopes", this::scopeMenu);
        tool(actions, "Color", this::colorMenu);
        tool(actions, "Más", this::moreMenu);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            header.setPadding(cutout.left, cutout.top, cutout.right, 0);
            if (badgePosition.topMargin != cutout.top + dp(52)) {
                badgePosition.topMargin = cutout.top + dp(52);
                indicators.setLayoutParams(badgePosition);
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) header.getLayoutParams();
            int height = dp(56) + cutout.top;
            if (params.height != height) {
                params.height = height;
                header.setLayoutParams(params);
            }
            tools.setPadding(dp(9) + cutout.left, 0, dp(9) + cutout.right, dp(5) + cutout.bottom);
            return insets;
        });
        setContentView(root);
        setQuickControlsEnabled(false);
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
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (touchFocus && frameSeen && image.isPictureAt(event.getX(), event.getY())) {
                    autofocus();
                    return true;
                }
                return image.performClick();
            }
            @Override public void onLongPress(MotionEvent event) {
                if (touchFocus) image.performLongClick();
            }
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
        image.setOnLongClickListener(view -> {
            if (!touchFocus || !frameSeen) return false;
            controls(!showingControls);
            return true;
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
        button.setTextSize(12);
        button.setMaxLines(2);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setBackground(panel(0xeb283441, 7));
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(0, -1, 1);
        position.setMargins(dp(2), 0, dp(2), 0);
        group.addView(button, position);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView recordingBadge(LinearLayout group, int color) {
        TextView badge = label(13, Color.WHITE);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setPadding(dp(12), dp(6), dp(12), dp(6));
        badge.setBackground(panel(color, 10));
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-2, -2);
        item.bottomMargin = dp(4);
        group.addView(badge, item);
        return badge;
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
        showingControls = visible;
        header.setVisibility(visible ? View.VISIBLE : View.GONE);
        tools.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            adjustment.clear();
            pendingList = null;
        }
        image.controlsVisible = visible;
        image.invalidate();
        if (visible && preview != null && frameSeen) preview.refreshQuickSettings();
        immerse();
    }

    private void setFocalDisplay(double mm) {
        focalLength = mm;
        String value = mm <= 0 ? "—" : new java.text.DecimalFormat("0.#").format(mm) + " mm";
        lensStatus.setText("Focal " + value);
        lensStatus.setContentDescription(mm <= 0 ? "Distancia focal sin dato. Toca para consultar."
                : "Distancia focal óptica " + value);
    }

    private static String quickLabel(CameraControls.Setting setting) {
        switch (setting) {
            case ISO: return "ISO";
            case SHUTTER: return "Obturación";
            case APERTURE: return "Diafragma";
            case EXPOSURE: return "EV";
            case WHITE_BALANCE: return "Balance WB";
            case TEMPERATURE: return "Kelvin";
            default: return setting.name;
        }
    }

    private void setQuickValue(CameraControls.Setting setting, Integer value) {
        Button button = quickButtons.get(setting);
        if (button == null) return;
        if (value == null) quickValues.remove(setting);
        else quickValues.put(setting, value);
        String label = value == null ? "—" : setting.label(value);
        if (setting == CameraControls.Setting.WHITE_BALANCE && CameraControls.isKelvinMode(value)) {
            label = "K" + (value - 0x800f);
        }
        button.setText(quickLabel(setting) + "\n" + label);
        button.setContentDescription(quickLabel(setting) + ", " + label);
    }

    private void setQuickControlsEnabled(boolean enabled) {
        for (CameraControls.Setting setting : CameraControls.QUICK_SETTINGS) {
            quickButtons.get(setting).setEnabled(enabled);
            if (!enabled) setQuickValue(setting, null);
        }
        quickControls.setVisibility(enabled ? View.VISIBLE : View.GONE);
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
                "Grabación en el teléfono", "Copiar diagnóstico"}, index -> {
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
                case 4: phoneQualityMenu(); break;
                case 5: copyDiagnostic(); break;
                default: break;
            }
            image.invalidate();
        });
    }

    private void phoneQualityMenu() {
        if (recorder != null) {
            toast("Termina el clip antes de cambiar su resolución.");
            return;
        }
        PhoneVideoQuality[] choices = PhoneVideoQuality.values();
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            boolean supported = choices[i] == PhoneVideoQuality.USB || choices[i].profile(640, 360) != null;
            labels[i] = marked(choices[i].label, choices[i] == phoneQuality)
                    + (supported ? "" : " · sin codificador");
        }
        menu("Resolución del video en el teléfono", labels, index -> {
            PhoneVideoQuality selected = choices[index];
            if (selected != PhoneVideoQuality.USB && selected.profile(640, 360) == null) {
                toast("El teléfono no anuncia un codificador compatible con esa resolución a 30 fps.");
                return;
            }
            phoneQuality = selected;
            getPreferences(MODE_PRIVATE).edit().putInt("phoneQuality", selected.ordinal()).apply();
            recordSwitch.setText(phoneQualityLabel());
            if (selected != PhoneVideoQuality.USB) {
                toast("El MP4 se ajustará a " + selected.label + ". El detalle depende de la señal USB.");
            }
        });
    }

    private String phoneQualityLabel() {
        return phoneQuality == PhoneVideoQuality.UHD ? "Grabar en 4K"
                : phoneQuality == PhoneVideoQuality.FULL_HD ? "Grabar en Full HD" : "Grabar en teléfono";
    }

    private void cameraMenu() {
        CameraControls.Setting[] settings = CameraControls.Setting.values();
        String[] names = new String[settings.length + 7];
        for (int i = 0; i < settings.length; i++) names[i] = settings[i].name;
        names[settings.length] = marked("AF al tocar · área activa", touchFocus);
        names[settings.length + 1] = "AF ahora · área activa";
        names[settings.length + 2] = "Disparar foto";
        names[settings.length + 3] = "Video · iniciar o detener";
        names[settings.length + 4] = "LUT internas de la cámara";
        names[settings.length + 5] = "Consultar capacidades USB";
        names[settings.length + 6] = "Ajustar foco manual";
        menu("Cámara Lumix", names, index -> {
            if (index == settings.length + 4) {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("LUT internas de la Lumix")
                        .setMessage("El estilo Real Time LUT usa la LUT elegida en la Lumix. La lista de archivos "
                                + "y la selección por nombre se consultan en Biblioteca LUT en la cámara. "
                                + "Las LUT de Color se aplican a la previsualización del teléfono.")
                        .setNeutralButton("Estilos", (dialog, which) -> {
                            if (preview == null || !frameSeen) toast("Conecta la Lumix y espera la imagen.");
                            else requestSetting(CameraControls.Setting.PHOTO_STYLE);
                        })
                        .setPositiveButton("Cerrar", null).show();
                return;
            }
            if (preview == null || !frameSeen) {
                toast("Conecta la Lumix y espera la imagen.");
                return;
            }
            if (index < settings.length) {
                requestSetting(settings[index]);
                return;
            }
            switch (index - settings.length) {
                case 0:
                    touchFocus = !touchFocus;
                    image.setContentDescription(touchFocus
                            ? "Video. Toca para AF en el área activa. Mantén pulsado para mostrar los controles."
                            : "Imagen del monitor, toca para mostrar los controles");
                    toast(touchFocus ? "Toca el video para activar el AF en el área de la Lumix. Mantén pulsado para los controles."
                            : "Toque para mostrar los controles.");
                    break;
                case 1: autofocus(); break;
                case 2:
                    if (cameraState == CameraControls.RecordingState.RECORDING) {
                        toast("Detén el video de la cámara antes de disparar una foto.");
                    } else if (!preview.shutter()) queueBusy();
                    break;
                case 3: cameraRecordingMenu(); break;
                case 5: if (!preview.inventory()) queueBusy(); break;
                case 6: manualFocusMenu(); break;
                default: break;
            }
        });
    }

    private void manualFocusMenu() {
        menu("Enfoque manual · selecciona MF en la Lumix",
                new String[]{"Lejos · paso grande", "Lejos · paso fino", "Cerca · paso fino", "Cerca · paso grande"},
                index -> { if (preview != null && frameSeen && !preview.adjustManualFocus(index + 1)) queueBusy(); });
    }

    private void requestSetting(CameraControls.Setting setting) {
        if (preview == null || !frameSeen) {
            toast("Conecta la Lumix y espera la imagen.");
            return;
        }
        if (adjustment.waiting()) {
            toast("Espera la respuesta del ajuste anterior.");
            return;
        }
        if (!preview.cameraChoices(setting)) {
            queueBusy();
            return;
        }
        boolean inline = setting == CameraControls.Setting.ISO || setting == CameraControls.Setting.SHUTTER
                || setting == CameraControls.Setting.APERTURE || setting == CameraControls.Setting.EXPOSURE
                || setting == CameraControls.Setting.TEMPERATURE;
        if (inline) {
            pendingList = null;
            adjustment.open(setting);
        } else {
            adjustment.clear();
            pendingList = setting;
        }
    }

    private boolean applySetting(CameraControls.Setting setting, int value) {
        if (preview == null || !frameSeen) return false;
        if (preview.changeSetting(setting, value)) return true;
        queueBusy();
        return false;
    }

    private void showCameraChoices(CameraControls.Setting setting, CameraControls.Choices choices) {
        if (choices != null) setQuickValue(setting, choices.current);
        if (adjustment.isEditing(setting)) {
            String empty = "La Lumix no ofreció valores ajustables en el modo actual.";
            if (setting == CameraControls.Setting.TEMPERATURE) {
                empty = CameraControls.isKelvinMode(quickValues.get(CameraControls.Setting.WHITE_BALANCE))
                        ? "La Lumix no devolvió un valor Kelvin que se pueda ajustar por USB."
                        : "Selecciona Temperatura 1 a 4 en Modos WB y vuelve a abrir Kelvin.";
            }
            adjustment.showChoices(choices, empty);
            if (choices == null || choices.values.length == 0) log(setting.name + " · " + empty);
            return;
        }
        if (pendingList != setting) return;
        pendingList = null;
        if (choices == null || choices.values.length == 0) {
            toast("La Lumix no devolvió valores ajustables de " + setting.name + " en este modo.");
            return;
        }
        String[] names = new String[choices.values.length];
        for (int i = 0; i < names.length; i++) {
            int value = choices.values[i];
            names[i] = marked(setting.label(value), value == choices.current);
        }
        menu(setting.name + " · " + setting.label(choices.current), names, index -> applySetting(setting, choices.values[index]));
    }

    private void autofocus() {
        if (preview != null && frameSeen && !preview.autoFocus()) queueBusy();
    }

    private void queueBusy() {
        toast("La cámara tiene órdenes pendientes. Intenta de nuevo en un momento.");
    }

    private void toggleCameraRecording() {
        if (cameraState == CameraControls.RecordingState.RECORDING) sendMovieCommand(false);
        else if (cameraState == CameraControls.RecordingState.READY && !recordingUnverified) sendMovieCommand(true);
        else cameraRecordingMenu();
    }

    private void cameraRecordingMenu() {
        menu("Grabación en la Lumix", new String[]{"Iniciar grabación", "Detener grabación", "Consultar estado REC"}, index -> {
            if (index == 2 && preview != null && frameSeen) preview.refreshRecordingState();
            else if (index < 2) sendMovieCommand(index == 0);
        });
    }

    private void sendMovieCommand(boolean start) {
        if (preview == null || !frameSeen) return;
        if (requestedRecording != null) {
            toast("Esperando la respuesta de la Lumix.");
            return;
        }
        if (!preview.movie(start)) {
            queueBusy();
            return;
        }
        requestedRecording = start;
        recordingUnverified = false;
        recordingRequestAt = SystemClock.elapsedRealtime();
        updateRecordingDisplay();
    }

    private void setCameraState(CameraControls.RecordingState state) {
        cameraState = state;
        if (state == CameraControls.RecordingState.RECORDING) {
            if (cameraStartedAt < 0) cameraStartedAt = SystemClock.elapsedRealtime();
        } else {
            cameraStartedAt = -1;
        }
        if (requestedRecording != null && (state == CameraControls.RecordingState.RECORDING && requestedRecording
                || state == CameraControls.RecordingState.READY && !requestedRecording)) {
            requestedRecording = null;
        }
        if (state == CameraControls.RecordingState.RECORDING || state == CameraControls.RecordingState.READY) {
            recordingUnverified = false;
        }
        updateRecordingDisplay();
    }

    private void handleRecordingReply(boolean start, int response) {
        log("Orden " + (start ? "REC" : "STOP") + " de cámara: " + PtpConnection.hex(response));
        if (response != PtpConnection.OK) {
            requestedRecording = null;
            recordingUnverified = false;
            toast("La Lumix rechazó la orden de video (" + PtpConnection.hex(response) + ").");
        } else {
            recordingRequestAt = SystemClock.elapsedRealtime();
        }
        updateRecordingDisplay();
    }

    private void updateRecordingDisplay() {
        ui.removeCallbacks(recordingDisplayTick);
        long now = SystemClock.elapsedRealtime();
        if (requestedRecording != null && now - recordingRequestAt >= 4500) {
            recordingUnverified = true;
            requestedRecording = null;
            log("REC de cámara sin confirmación por USB.");
        }
        boolean cameraActive = cameraStartedAt >= 0;
        boolean phoneActive = phoneStartedAt >= 0;
        boolean pending = requestedRecording != null;
        cameraBorder.setVisibility(cameraActive ? View.VISIBLE : View.GONE);
        cameraBadge.setVisibility(cameraActive || pending || recordingUnverified ? View.VISIBLE : View.GONE);
        if (cameraActive || pending || recordingUnverified) {
            cameraBadge.setBackground(panel(cameraActive ? 0xffc31933 : 0xff604000, 10));
            cameraBadge.setText(cameraActive ? "● REC CÁMARA  " + clock(now - cameraStartedAt)
                    : pending ? "CÁMARA · esperando confirmación" : "CÁMARA · estado REC sin confirmar");
        }
        phoneBadge.setVisibility(phoneActive ? View.VISIBLE : View.GONE);
        if (phoneActive) phoneBadge.setText("● TELÉFONO  " + clock(now - phoneStartedAt) + "  " + phoneRecordingSize);
        cameraRecord.setText(cameraActive ? "STOP" : "REC");
        cameraRecord.setContentDescription(cameraActive ? "Detener grabación de cámara" : "Grabar en la cámara");
        cameraRecord.setEnabled(frameSeen && !pending);
        if (cameraActive || pending || phoneActive) ui.postDelayed(recordingDisplayTick, 250);
    }

    private static String clock(long elapsed) {
        long seconds = Math.max(0, elapsed) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
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

            @Override public void onRecordingState(CameraControls.RecordingState state, String detail) {
                ui.post(() -> {
                    if (destroyed) return;
                    log(detail);
                    setCameraState(state);
                });
            }

            @Override public void onRecordingCommand(boolean start, int response) {
                ui.post(() -> { if (!destroyed) handleRecordingReply(start, response); });
            }

            @Override public void onCameraChoices(CameraControls.Setting setting, CameraControls.Choices choices) {
                ui.post(() -> { if (!destroyed && frameSeen) showCameraChoices(setting, choices); });
            }

            @Override public void onCameraValue(CameraControls.Setting setting, Integer value) {
                ui.post(() -> { if (!destroyed && frameSeen) setQuickValue(setting, value); });
            }

            @Override public void onCameraSettingResult(CameraControls.Setting setting, Integer confirmed, String detail) {
                ui.post(() -> {
                    if (destroyed || !frameSeen) return;
                    if (confirmed != null) setQuickValue(setting, confirmed);
                    if (adjustment.isEditing(setting)) {
                        adjustment.result(confirmed, detail);
                        log(detail);
                    } else {
                        toast(detail);
                    }
                });
            }

            @Override public void onFocalLength(double mm, String detail) {
                ui.post(() -> {
                    if (destroyed || !frameSeen) return;
                    setFocalDisplay(mm);
                    log(mm > 0 ? "Focal óptica " + mm + " mm (" + detail + ")" : detail);
                });
            }

            @Override public void onCameraDiagnostic(String detail) {
                ui.post(() -> {
                    if (destroyed) return;
                    log(detail);
                    toast("Capacidades consultadas. Abre Más → Copiar diagnóstico para ver las respuestas.");
                });
            }

            @Override public void onFinished(String error) {
                ui.post(() -> {
                    preview = null;
                    if (destroyed) return;
                    frameSeen = false;
                    pendingList = null;
                    adjustment.clear();
                    setQuickControlsEnabled(false);
                    setFocalDisplay(0);
                    requestedRecording = null;
                    recordingUnverified = false;
                    setCameraState(CameraControls.RecordingState.UNKNOWN);
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
                updateRecordingDisplay();
                setQuickControlsEnabled(true);
                if (preview != null) preview.refreshQuickSettings();
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
        PhoneVideoQuality.Profile profile = phoneQuality.profile(sourceWidth, sourceHeight);
        if (profile == null) {
            setRecordingSwitch(false);
            toast("No se encontró un codificador compatible con la resolución seleccionada.");
            return;
        }
        recordSwitch.setEnabled(false);
        phoneRecordingSize = profile.dimensions();
        recorder = new PhoneRecorder(this, profile, new PhoneRecorder.Listener() {
            @Override public void onRecording() {
                ui.post(() -> {
                    if (destroyed || !frameSeen || !recordSwitch.isChecked()) return;
                    phoneStartedAt = SystemClock.elapsedRealtime();
                    log("Grabación en teléfono " + phoneRecordingSize + " · señal " + sourceWidth + " × " + sourceHeight);
                    updateRecordingDisplay();
                    recordSwitch.setEnabled(true);
                });
            }
            @Override public void onClosed(String message) {
                ui.post(() -> {
                    recorder = null;
                    if (destroyed) return;
                    phoneStartedAt = -1;
                    updateRecordingDisplay();
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico Monitor Lumix",
                status.getText() + "\n" + diagnostic));
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
        ui.removeCallbacks(recordingDisplayTick);
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
