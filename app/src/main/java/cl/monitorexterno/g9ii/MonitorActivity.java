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
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MonitorActivity extends Activity {
    private static final int IMPORT_LUT = 1;
    private static final String USB_PERMISSION = "cl.monitorexterno.g9ii.monitor.v05.USB_PERMISSION";
    private static final int INK = 0xfff1f8f6;
    private static final int MINT = 0xffacf0dc;
    private static final int ACTIVE_DOT = 0xff79ef9c;
    private static final int SIDE_WIDTH = 70;
    private static final CameraControls.Setting[] SIDE_SETTINGS = {
            CameraControls.Setting.APERTURE, CameraControls.Setting.SHUTTER,
            CameraControls.Setting.WHITE_BALANCE, CameraControls.Setting.ISO
    };
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
    private final EnumMap<CameraControls.Setting, Button> quickButtons = new EnumMap<>(CameraControls.Setting.class);
    private final EnumMap<CameraControls.Setting, Integer> quickValues = new EnumMap<>(CameraControls.Setting.class);
    private AdjustmentPanel adjustment;
    private CameraControls.Setting pendingList;
    private TextView notice;
    private TextView phoneBadge;
    private TextView cameraBadge;
    private View cameraBorder;
    private Button cameraPhoto;
    private Button cameraRecord;
    private Button touchFocusButton;
    private TextView previewPause;
    private boolean touchFocus;
    private long touchFocusRequest;
    private boolean previewPaused;
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
    private LinearLayout sideControls;
    private LinearLayout actions;
    private FrameLayout header;
    private LinearLayout headerRow;
    private LinearLayout monitorActions;
    private LinearLayout indicators;
    private Button configurationButton;
    private Button controlsButton;
    private Button focusTool;
    private Button exposureTool;
    private Button scopesTool;
    private Button moreTool;
    private final List<Button> actionButtons = new ArrayList<>();
    private Insets cutout = Insets.NONE;
    private ControlSize controlSize = ControlSize.NORMAL;
    private boolean controlsOnLeft;
    private boolean quickControlsEnabled;
    private CameraControls.Setting listedQuickSetting;
    private UsbVideoFormat requestedUsbFormat;
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
        controlSize = ControlSize.fromPreference(getPreferences(MODE_PRIVATE).getString("controlSize", "NORMAL"));
        controlsOnLeft = getPreferences(MODE_PRIVATE).getBoolean("controlsOnLeft", false);
        touchFocus = getPreferences(MODE_PRIVATE).getBoolean("touchFocus", true);
        buildUi();
        immerse();
        registerReceiver(permissionReceiver, new IntentFilter(USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        diagnostic.append(getString(R.string.app_name)).append(' ').append(BuildConfig.VERSION_NAME).append('\n').append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
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
        headerRow = new LinearLayout(this);
        headerRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        FrameLayout.LayoutParams headerRowPosition = new FrameLayout.LayoutParams(-1, dp(46), Gravity.TOP);
        headerRowPosition.setMargins(dp(12), dp(5), dp(12), 0);
        header.addView(headerRow, headerRowPosition);
        LinearLayout signal = new LinearLayout(this);
        signal.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(signal, new LinearLayout.LayoutParams(0, -1, 1));
        status = label(11, MINT);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setTypeface(Typeface.MONOSPACE);
        status.setBackground(panel(0xe81c242d, 8));
        status.setPadding(dp(11), dp(7), dp(11), dp(7));
        signal.addView(status);
        monitorActions = new LinearLayout(this);
        monitorActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams recordPosition = new LinearLayout.LayoutParams(-2, -1);
        recordPosition.leftMargin = dp(5);
        headerRow.addView(monitorActions, recordPosition);
        touchFocusButton = headerTool(monitorActions, "AF área", this::toggleTouchFocus);
        touchFocusButton.setEnabled(false);
        updateTouchFocus();
        recordSwitch = new Switch(this);
        recordSwitch.setText("REC teléfono");
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
        recordSwitch.setContentDescription("Grabar en el teléfono");
        monitorActions.addView(recordSwitch, new LinearLayout.LayoutParams(-2, -1));
        configurationButton = headerTool(monitorActions, "⚙", this::configurationMenu);
        configurationButton.setContentDescription("Configuración");
        controlsButton = headerTool(headerRow, "Vista limpia", () -> controls(!showingControls));

        cameraBorder = new View(this);
        GradientDrawable redBorder = panel(Color.TRANSPARENT, 0);
        redBorder.setStroke(dp(4), 0xffff354c);
        cameraBorder.setBackground(redBorder);
        cameraBorder.setVisibility(View.GONE);
        cameraBorder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(cameraBorder, new FrameLayout.LayoutParams(-1, -1));

        indicators = new LinearLayout(this);
        indicators.setOrientation(LinearLayout.VERTICAL);
        indicators.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams badgePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        badgePosition.topMargin = dp(52);
        root.addView(indicators, badgePosition);
        LinearLayout recordingIndicators = new LinearLayout(this);
        recordingIndicators.setGravity(Gravity.CENTER);
        indicators.addView(recordingIndicators);
        cameraBadge = recordingBadge(recordingIndicators, 0xffc31933);
        phoneBadge = recordingBadge(recordingIndicators, 0xffc31933);
        cameraBadge.setSingleLine(true);
        phoneBadge.setSingleLine(true);
        cameraBadge.setEllipsize(TextUtils.TruncateAt.END);
        phoneBadge.setEllipsize(TextUtils.TruncateAt.END);
        previewPause = recordingBadge(indicators, 0xff604000);
        previewPause.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

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
            redrawTools();
        });

        adjustment = new AdjustmentPanel(this, this::applySetting, this::closeAdjustment,
                () -> requestSetting(CameraControls.Setting.WHITE_BALANCE));
        LinearLayout.LayoutParams editorPosition = new LinearLayout.LayoutParams(-1, -2);
        editorPosition.bottomMargin = dp(4);
        tools.addView(adjustment, editorPosition);

        quickControls = new LinearLayout(this);
        quickControls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams quickPosition = new LinearLayout.LayoutParams(-1, dp(51));
        quickPosition.bottomMargin = dp(4);
        tools.addView(quickControls, quickPosition);
        sideControls = new LinearLayout(this);
        sideControls.setOrientation(LinearLayout.VERTICAL);
        root.addView(sideControls, new FrameLayout.LayoutParams(dp(SIDE_WIDTH), -2, Gravity.START | Gravity.CENTER_VERTICAL));
        for (CameraControls.Setting setting : CameraControls.QUICK_SETTINGS) {
            Button button = tool(quickControls, quickLabel(setting) + "\n—", () -> requestSetting(setting));
            button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            button.setTextColor(MINT);
            button.setBackground(panel(0xee21303b, 8));
            button.setEnabled(false);
            quickButtons.put(setting, button);
        }

        actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(actions, new LinearLayout.LayoutParams(-1, dp(48)));
        connect = tool(actions, "Conectar", this::connectOrStop);
        connect.setTextColor(MINT);
        cameraPhoto = tool(actions, "Foto", this::shootPhoto);
        cameraPhoto.setContentDescription("Disparar foto en la Lumix");
        cameraPhoto.setTextColor(MINT);
        cameraPhoto.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        cameraPhoto.setEnabled(false);
        cameraRecord = tool(actions, "REC\ncámara", this::toggleCameraRecording);
        cameraRecord.setContentDescription("Grabar en la cámara");
        cameraRecord.setTextColor(0xffff8d99);
        cameraRecord.setEnabled(false);
        tool(actions, "Cámara", this::cameraMenu);
        tool(actions, "Vista", this::viewMenu);
        focusTool = tool(actions, "F. Peaking", this::focusMenu);
        exposureTool = tool(actions, "Exposición", this::exposureMenu);
        scopesTool = tool(actions, "Scopes", this::scopeMenu);
        moreTool = tool(actions, "Más", this::moreMenu);
        for (int i = 0; i < actions.getChildCount(); i++) actionButtons.add((Button) actions.getChildAt(i));
        indicators.bringToFront();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            cutout = insets.getInsets(WindowInsets.Type.displayCutout());
            applyControlGeometry();
            return insets;
        });
        applyControlLayout();
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
                if (touchFocus && frameSeen && !previewPaused && image.isPictureAt(event.getX(), event.getY())) {
                    touchAutofocus(event.getX(), event.getY());
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
        button.setEllipsize(TextUtils.TruncateAt.END);
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

    private Button headerTool(LinearLayout group, String title, Runnable action) {
        Button button = tool(group, title, action);
        LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-2, dp(44));
        position.rightMargin = dp(5);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setLayoutParams(position);
        return button;
    }

    private enum ControlSize {
        SMALL("Pequeño · 50 %", 0.5f), NORMAL("Normal · 100 %", 1f), LARGE("Grande · 120 %", 1.2f);

        final String label;
        final float scale;

        ControlSize(String label, float scale) {
            this.label = label;
            this.scale = scale;
        }

        static ControlSize fromPreference(String value) {
            for (ControlSize size : values()) if (size.name().equals(value)) return size;
            return NORMAL;
        }
    }

    private void configurationMenu() {
        menu("Configuración", new String[]{"Tamaño de botones · " + controlSize.label,
                marked("Controles a la izquierda", controlsOnLeft)}, index -> {
            if (index == 0) {
                ControlSize[] sizes = ControlSize.values();
                String[] names = new String[sizes.length];
                for (int i = 0; i < sizes.length; i++) names[i] = marked(sizes[i].label, sizes[i] == controlSize);
                menu("Tamaño de botones", names, selected -> setControlSize(sizes[selected]));
            } else {
                setControlsOnLeft(!controlsOnLeft);
            }
        });
    }

    private void setControlSize(ControlSize size) {
        controlSize = size;
        getPreferences(MODE_PRIVATE).edit().putString("controlSize", size.name()).apply();
        applyControlGeometry();
    }

    private void setControlsOnLeft(boolean enabled) {
        controlsOnLeft = enabled;
        getPreferences(MODE_PRIVATE).edit().putBoolean("controlsOnLeft", enabled).apply();
        applyControlLayout();
    }

    private void applyControlLayout() {
        quickControls.removeAllViews();
        sideControls.removeAllViews();
        if (controlsOnLeft) {
            for (CameraControls.Setting setting : SIDE_SETTINGS) {
                LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(-1, dp(30));
                position.bottomMargin = dp(3);
                sideControls.addView(quickButtons.get(setting), position);
            }
        }
        for (CameraControls.Setting setting : CameraControls.QUICK_SETTINGS) {
            Button button = quickButtons.get(setting);
            if (button.getParent() != null) continue;
            LinearLayout.LayoutParams position = new LinearLayout.LayoutParams(0, -1, 1);
            position.setMargins(dp(2), 0, dp(2), 0);
            quickControls.addView(button, position);
        }
        updateQuickVisibility();
        applyControlGeometry();
    }

    private void updateQuickVisibility() {
        boolean visible = showingControls && quickControlsEnabled;
        quickControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        sideControls.setVisibility(visible && controlsOnLeft ? View.VISIBLE : View.GONE);
        updateControlInsets();
    }

    private void updateControlInsets() {
        int sideSpace = sideControls.getVisibility() == View.VISIBLE ? dp(SIDE_WIDTH + 5) : 0;
        int left = dp(9) + cutout.left + sideSpace;
        int right = dp(9) + cutout.right;
        int bottom = dp(5) + cutout.bottom;
        if (tools.getPaddingLeft() != left || tools.getPaddingRight() != right || tools.getPaddingBottom() != bottom) {
            tools.setPadding(left, 0, right, bottom);
        }
    }

    private void applyControlGeometry() {
        float scale = controlSize.scale;
        header.setPadding(cutout.left, cutout.top, cutout.right, 0);
        layoutHeight(header, dp(10 + 46 * scale) + cutout.top);
        layoutHeight(headerRow, dp(46 * scale));
        layoutHeight(quickControls, dp(51 * scale));
        layoutHeight(actions, dp(48 * scale));
        FrameLayout.LayoutParams badgePosition = (FrameLayout.LayoutParams) indicators.getLayoutParams();
        int top = cutout.top + dp(6 + 46 * scale);
        if (badgePosition.topMargin != top) {
            badgePosition.topMargin = top;
            indicators.setLayoutParams(badgePosition);
        }
        FrameLayout.LayoutParams sidePosition = (FrameLayout.LayoutParams) sideControls.getLayoutParams();
        int sideLeft = dp(6) + cutout.left;
        if (sidePosition.leftMargin != sideLeft) {
            sidePosition.leftMargin = sideLeft;
            sideControls.setLayoutParams(sidePosition);
        }
        for (Button button : actionButtons) sizeButton(button, scale);
        for (Button button : quickButtons.values()) sizeButton(button, button.getParent() == sideControls ? 0.75f : scale);
        for (Button button : new Button[]{touchFocusButton, configurationButton, controlsButton}) {
            sizeButton(button, scale);
            layoutHeight(button, dp(44 * scale));
        }
        recordSwitch.setTextSize(Math.max(10, 13 * scale));
        recordSwitch.setMinHeight(dp(44 * scale));
        updateControlInsets();
    }

    private static void layoutHeight(View view, int height) {
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.height == height) return;
        params.height = height;
        view.setLayoutParams(params);
    }

    private static void sizeButton(Button button, float scale) {
        button.setTextSize(Math.max(10, 12 * scale));
        button.setIncludeFontPadding(scale >= 1);
    }

    private void closeAdjustment() {
        adjustment.clear();
        updateActiveDots();
    }

    private void redrawTools() {
        image.invalidate();
        updateActiveDots();
    }

    private void updateActiveDots() {
        for (CameraControls.Setting setting : CameraControls.QUICK_SETTINGS) {
            setActiveDot(quickButtons.get(setting), adjustment.isEditing(setting) || listedQuickSetting == setting);
        }
        setActiveDot(focusTool, image.peaking > 0);
        setActiveDot(exposureTool, image.zebraA >= 0 || image.zebraB >= 0 || image.displayMode == 1);
        setActiveDot(scopesTool, image.scope != VideoScopes.Mode.OFF);
        setActiveDot(moreTool, image.grid || image.crosshair || image.guideRatio > 0 || image.safeArea > 0
                || image.hasLut() || image.displayMode == 2 || image.flipHorizontal || image.flipVertical
                || image.desqueeze != 1);
    }

    private void setActiveDot(Button button, boolean active) {
        if (button.isSelected() == active) return;
        button.setSelected(active);
        button.setStateDescription(active ? "Activo" : null);
        if (!active) {
            button.setForeground(null);
            return;
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACTIVE_DOT);
        LayerDrawable foreground = new LayerDrawable(new Drawable[]{dot});
        foreground.setLayerSize(0, dp(6), dp(6));
        foreground.setLayerGravity(0, Gravity.TOP | Gravity.END);
        foreground.setLayerInsetEnd(0, dp(4));
        foreground.setLayerInsetTop(0, dp(4));
        button.setForeground(foreground);
    }

    private TextView recordingBadge(LinearLayout group, int color) {
        TextView badge = label(13, Color.WHITE);
        badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        badge.setPadding(dp(12), dp(6), dp(12), dp(6));
        badge.setBackground(panel(color, 10));
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-2, -2);
        item.setMargins(dp(3), 0, dp(3), dp(4));
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
        monitorActions.setVisibility(visible ? View.VISIBLE : View.GONE);
        status.setVisibility(visible ? View.VISIBLE : View.GONE);
        tools.setVisibility(visible ? View.VISIBLE : View.GONE);
        controlsButton.setText(visible ? "Vista limpia" : "Controles");
        controlsButton.setContentDescription(visible ? "Ocultar controles" : "Mostrar controles");
        updateQuickVisibility();
        if (!visible) {
            closeAdjustment();
            pendingList = null;
        }
        image.controlsVisible = visible;
        redrawTools();
        if (visible && preview != null && frameSeen) preview.refreshQuickSettings();
        immerse();
    }

    static String quickLabel(CameraControls.Setting setting) {
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
        quickControlsEnabled = enabled;
        updateQuickVisibility();
        updateActiveDots();
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
        menu(title, options, selection, null);
    }

    private void menu(String title, String[] options, Selection selection, Runnable onDismiss) {
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setItems(options, (box, index) -> selection.choose(index))
                .setNegativeButton("Cerrar", null).create();
        if (onDismiss != null) dialog.setOnDismissListener(ignored -> onDismiss.run());
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
                    redrawTools();
                });
            } else if (index == 1) {
                menu("Color de bordes", new String[]{"Verde", "Rojo", "Amarillo", "Cian", "Blanco"}, value -> {
                    int[] colors = {Color.GREEN, Color.RED, Color.YELLOW, Color.CYAN, Color.WHITE};
                    image.peakingColor = colors[value];
                    redrawTools();
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
            redrawTools();
        });
    }

    private void zebra(boolean yellow) {
        menu(yellow ? "Umbral de zebra amarilla" : "Umbral de zebra roja",
                new String[]{"Apagada", "50%", "60%", "65%", "70%", "75%", "80%", "85%", "90%", "95%", "98%", "100%"}, index -> {
            int[] levels = {-1, 50, 60, 65, 70, 75, 80, 85, 90, 95, 98, 100};
            if (yellow) image.zebraA = levels[index];
            else image.zebraB = levels[index];
            redrawTools();
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
                        redrawTools();
                    });
                    break;
                case 1: image.grid = !image.grid; break;
                case 2: image.crosshair = !image.crosshair; break;
                case 3: image.mask = !image.mask; break;
                case 4:
                    menu("Área segura", new String[]{"Oculta", "80%", "90%", "93%", "95%"}, value -> {
                        int[] sizes = {0, 80, 90, 93, 95};
                        image.safeArea = sizes[value];
                        redrawTools();
                    });
                    break;
                default: break;
            }
            redrawTools();
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
            redrawTools();
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
                redrawTools();
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
        menu("Herramientas del monitor", new String[]{"Vista previa USB", "Corrección anamórfica",
                marked("Voltear horizontal", image.flipHorizontal), marked("Voltear vertical", image.flipVertical),
                "Grabación en el teléfono", "Copiar diagnóstico", "Guías de encuadre", "Color de monitor"}, index -> {
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
                case 6: guideMenu(); break;
                case 7: colorMenu(); break;
                default: break;
            }
            redrawTools();
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
            if (selected != PhoneVideoQuality.USB) {
                toast("El MP4 se ajustará a " + selected.label + ". El detalle depende de la señal USB.");
            }
        });
    }

    private void cameraMenu() {
        CameraControls.Setting[] settings = CameraControls.Setting.values();
        String[] names = new String[settings.length + 8];
        for (int i = 0; i < settings.length; i++) names[i] = settings[i].name;
        names[settings.length] = marked("AF al tocar · área activa de la Lumix", touchFocus);
        names[settings.length + 1] = "AF ahora · área activa";
        names[settings.length + 2] = "Disparar foto";
        names[settings.length + 3] = "Video · iniciar o detener";
        names[settings.length + 4] = "LUT internas de la cámara";
        names[settings.length + 5] = "Consultar capacidades USB";
        names[settings.length + 6] = "Ajustar foco manual";
        names[settings.length + 7] = "Calidad de video en la Lumix";
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
                    toggleTouchFocus();
                    break;
                case 1: autofocus(); break;
                case 2: shootPhoto(); break;
                case 3: cameraRecordingMenu(); break;
                case 5: if (!preview.inventory()) queueBusy(); break;
                case 6: manualFocusMenu(); break;
                case 7:
                    if (!preview.queryMovieQuality()) queueBusy();
                    break;
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
            closeAdjustment();
            pendingList = setting;
        }
        updateActiveDots();
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
        listedQuickSetting = setting;
        updateActiveDots();
        menu(setting.name + " · " + setting.label(choices.current), names,
                index -> applySetting(setting, choices.values[index]), () -> {
                    listedQuickSetting = null;
                    updateActiveDots();
                });
    }

    private void autofocus() {
        if (preview != null && frameSeen && !preview.autoFocus(0)) queueBusy();
    }

    private void touchAutofocus(float x, float y) {
        if (preview == null) return;
        long request = ++touchFocusRequest;
        image.showFocusTap(x, y);
        if (!preview.autoFocus(request)) {
            image.showFocusResult(false);
            queueBusy();
        }
    }

    private void toggleTouchFocus() {
        touchFocus = !touchFocus;
        getPreferences(MODE_PRIVATE).edit().putBoolean("touchFocus", touchFocus).apply();
        updateTouchFocus();
        if (touchFocus) toast("Toca el video para activar el AF en el área que usa la Lumix. Mantén pulsado para los controles.");
        else image.clearFocusTap();
    }

    private void updateTouchFocus() {
        setActiveDot(touchFocusButton, touchFocus);
        touchFocusButton.setContentDescription(touchFocus ? "Desactivar AF en el área activa de la Lumix" : "Activar AF en el área activa de la Lumix");
        image.setContentDescription(touchFocus
                ? "Video. Toca para activar el AF en el área de la Lumix. Mantén pulsado para mostrar u ocultar controles."
                : "Video. Toca para mostrar u ocultar controles.");
    }

    private void queueBusy() {
        toast("La cámara tiene órdenes pendientes. Intenta de nuevo en un momento.");
    }

    private void shootPhoto() {
        if (preview == null || !frameSeen || !connect.isEnabled()) return;
        if (cameraState == CameraControls.RecordingState.RECORDING) {
            toast("Detén el video de la cámara antes de disparar una foto.");
        } else if (requestedRecording != null) {
            toast("Esperando la respuesta de la Lumix.");
        } else if (!preview.shutter()) {
            queueBusy();
        }
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
        if (phoneActive) phoneBadge.setText("● REC TELÉFONO  " + clock(now - phoneStartedAt));
        recordSwitch.setTextColor(phoneActive ? 0xffff8d99 : INK);
        cameraRecord.setText(cameraActive ? "STOP\ncámara" : "REC\ncámara");
        cameraRecord.setContentDescription(cameraActive ? "Detener grabación de cámara" : "Grabar en la cámara");
        boolean cameraActionsReady = frameSeen && connect.isEnabled() && !pending;
        cameraPhoto.setEnabled(cameraActionsReady && !cameraActive);
        cameraRecord.setEnabled(cameraActionsReady);
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
        menu(status.getText().toString(), names, index -> {
            if (preview == null || recorder != null) return;
            requestedUsbFormat = formats.get(index);
            toast("Solicitado: " + requestedUsbFormat.width + " × " + requestedUsbFormat.height + " · midiendo señal USB");
            preview.changeFormat(requestedUsbFormat);
        });
    }

    private void connectOrStop() {
        if (preview != null) {
            stopRecorder();
            connect.setEnabled(false);
            updateRecordingDisplay();
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
        requestedUsbFormat = null;
        connect.setText("Salir USB");
        connect.setContentDescription("Desconectar la cámara USB");
        connect.setEnabled(true);
        recordSwitch.setEnabled(false);
        setPreviewPaused(null);
        preview = new LivePreview(usbManager, camera, new LivePreview.Listener() {
            @Override public void onPhase(String message) {
                ui.post(() -> { if (!destroyed) showNotice(message); });
            }

            @Override public void onFrame(Bitmap frame, double fps) {
                if (destroyed) return;
                PhoneRecorder active = recorder;
                if (active != null) active.submitCleanFrame(frame, System.nanoTime());
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

            @Override public void onAutofocusResult(long request, int response) {
                ui.post(() -> {
                    if (destroyed || !frameSeen) return;
                    boolean accepted = response == PtpConnection.OK;
                    String result = accepted ? "AF en el área activa · orden aceptada"
                            : "AF en el área activa · respuesta " + PtpConnection.hex(response);
                    if (request == 0) toast(result);
                    else {
                        log(result);
                        if (touchFocus && request == touchFocusRequest) image.showFocusResult(accepted);
                    }
                });
            }

            @Override public void onPreviewPause(String reason) {
                ui.post(() -> { if (!destroyed) setPreviewPaused(reason); });
            }

            @Override public void onMovieQuality(String detail) {
                ui.post(() -> {
                    if (destroyed || !frameSeen) return;
                    log(detail);
                    new AlertDialog.Builder(MonitorActivity.this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Calidad de video en la Lumix")
                            .setMessage("Por ahora, elige FHD, UHD y los fps en la Lumix, en Calidad grab. "
                                    + "La consulta USB quedó en Más → Copiar diagnóstico.")
                            .setPositiveButton("Cerrar", null)
                            .show();
                });
            }

            @Override public void onFormats(List<UsbVideoFormat> formats, UsbVideoFormat current) {
                ui.post(() -> {
                    if (destroyed || !frameSeen || recorder != null) return;
                    showFormats(formats, current);
                });
            }

            @Override public void onFormatMeasurement(UsbVideoFormat requested, int width, int height, double fps) {
                ui.post(() -> {
                    if (!destroyed && frameSeen && requested.equals(requestedUsbFormat)) {
                        toast(String.format(Locale.getDefault(),
                                "Solicitado: %d × %d\nUSB recibido: %d × %d · %.1f fps",
                                requested.width, requested.height, width, height, fps));
                    }
                });
            }

            @Override public void onUsbDiagnostic(String detail) {
                ui.post(() -> { if (!destroyed) log(detail); });
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
                    setPreviewPaused(null);
                    image.clearFocusTap();
                    pendingList = null;
                    closeAdjustment();
                    setQuickControlsEnabled(false);
                    requestedUsbFormat = null;
                    requestedRecording = null;
                    recordingUnverified = false;
                    setCameraState(CameraControls.RecordingState.UNKNOWN);
                    newest.set(null);
                    image.show(null, null, VideoScopes.Mode.OFF);
                    stopRecorder();
                    controls(true);
                    connect.setText("Conectar");
                    connect.setContentDescription("Conectar la cámara USB");
                    connect.setEnabled(true);
                    recordSwitch.setEnabled(false);
                    status.setText("USB · Sin señal");
                    showNotice(error == null ? "Sesión USB cerrada." : error);
                });
            }
        });
        new Thread(preview, "Lumix-USB").start();
    }

    private void setPreviewPaused(String reason) {
        previewPaused = reason != null;
        if (previewPaused) {
            previewPause.setText(reason);
            log(reason);
            image.clearFocusTap();
        }
        previewPause.setVisibility(previewPaused ? View.VISIBLE : View.GONE);
        touchFocusButton.setEnabled(frameSeen && !previewPaused);
        recordSwitch.setEnabled(frameSeen && (!previewPaused || recorder != null));
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
                touchFocusButton.setEnabled(true);
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
        if (!frameSeen || previewPaused || sourceWidth < 2 || sourceHeight < 2 || recorder != null) {
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
                    recordSwitch.setEnabled(frameSeen && !previewPaused);
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
