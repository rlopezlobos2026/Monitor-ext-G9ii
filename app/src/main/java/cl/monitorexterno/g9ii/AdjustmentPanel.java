package cl.monitorexterno.g9ii;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

final class AdjustmentPanel extends LinearLayout {
    interface Apply { boolean change(CameraControls.Setting setting, int value); }

    private static final int TEXT = 0xfff2f7fa;
    private static final int MUTED = 0xffb8c5cf;
    private static final int ACCENT = 0xff76e5cc;
    private final TextView heading;
    private final TextView detail;
    private final SeekBar slider;
    private final Button smaller;
    private final Button larger;
    private final Button apply;
    private final Button modes;
    private final Apply writer;
    private CameraControls.Setting setting;
    private CameraControls.Choices choices;
    private Integer current;
    private boolean waiting;

    AdjustmentPanel(Context context, Apply writer, Runnable close, Runnable whiteBalance) {
        super(context);
        this.writer = writer;
        setOrientation(VERTICAL);
        setPadding(dp(12), 0, dp(12), dp(5));
        setBackground(block(0xf5282d35, 12));

        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(-1, dp(50)));
        heading = text(14, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setMaxLines(2);
        row.addView(heading, new LayoutParams(dp(124), -2));

        smaller = button(row, "−", 46, () -> shift(-1));
        smaller.setContentDescription("Valor anterior");
        slider = new SeekBar(context);
        slider.setContentDescription("Valor del ajuste");
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        row.addView(slider, new LayoutParams(0, dp(48), 1));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int index, boolean fromUser) { render(); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        larger = button(row, "+", 46, () -> shift(1));
        larger.setContentDescription("Valor siguiente");
        apply = button(row, "Aplicar", 86, this::applySelected);
        apply.setTextColor(ACCENT);
        modes = button(row, "Modos WB", 104, whiteBalance);
        button(row, "Cerrar", 70, close);

        detail = text(12, MUTED);
        detail.setPadding(dp(2), 0, dp(2), 0);
        addView(detail, new LayoutParams(-1, -2));
        clear();
    }

    void open(CameraControls.Setting setting) {
        this.setting = setting;
        choices = null;
        current = null;
        waiting = false;
        detail.setText("Consultando la Lumix…");
        setVisibility(VISIBLE);
        render();
    }

    boolean isEditing(CameraControls.Setting candidate) { return setting == candidate && getVisibility() == VISIBLE; }
    boolean waiting() { return waiting; }

    void showChoices(CameraControls.Choices choices, String emptyMessage) {
        this.choices = choices;
        current = choices == null ? null : choices.current;
        if (choices == null || choices.values.length == 0) {
            detail.setText(emptyMessage);
        } else {
            slider.setMax(choices.values.length - 1);
            slider.setProgress(indexOf(choices.current));
            detail.setText("Desliza o usa − y +. Toca Aplicar para cambiar el valor.");
        }
        render();
    }

    void result(Integer confirmed, String message) {
        waiting = false;
        if (confirmed != null && choices != null) {
            current = confirmed;
            slider.setProgress(indexOf(confirmed));
        }
        detail.setText(message);
        render();
    }

    void clear() {
        setting = null;
        choices = null;
        current = null;
        waiting = false;
        setVisibility(GONE);
    }

    private int indexOf(int value) {
        for (int index = 0; index < choices.values.length; index++) {
            if (choices.values[index] == value) return index;
        }
        return 0;
    }

    private void shift(int amount) {
        slider.setProgress(Math.max(0, Math.min(slider.getMax(), slider.getProgress() + amount)));
    }

    private void render() {
        if (setting == null) return;
        boolean available = choices != null && choices.values.length > 0;
        boolean canEdit = available && !waiting;
        int index = slider.getProgress();
        int value = available ? choices.values[Math.min(index, choices.values.length - 1)] : 0;
        heading.setText(setting.name + "\n" + (available ? setting.label(value) : "—"));
        slider.setVisibility(available ? VISIBLE : INVISIBLE);
        smaller.setVisibility(available ? VISIBLE : GONE);
        larger.setVisibility(available ? VISIBLE : GONE);
        apply.setVisibility(available ? VISIBLE : GONE);
        modes.setVisibility(!available && setting == CameraControls.Setting.TEMPERATURE ? VISIBLE : GONE);
        slider.setEnabled(canEdit);
        smaller.setEnabled(canEdit && index > 0);
        larger.setEnabled(canEdit && index < slider.getMax());
        apply.setEnabled(canEdit && (current == null || current != value));
    }

    private void applySelected() {
        int value = choices.values[slider.getProgress()];
        if (writer.change(setting, value)) {
            waiting = true;
            detail.setText("Aplicando en la Lumix…");
            render();
        }
    }

    private Button button(LinearLayout row, String name, int width, Runnable action) {
        Button button = new Button(getContext());
        button.setText(name);
        button.setTextColor(TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setBackground(block(0xff3b4553, 7));
        LayoutParams params = new LayoutParams(dp(width), dp(44));
        params.leftMargin = dp(4);
        row.addView(button, params);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView text(int size, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable block(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
