package cl.monitorexterno.g9ii;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Log;
import android.os.SystemClock;
import android.view.View;

final class MonitorImage extends View {
    enum Size { FIT, FILL, PIXEL }
    static final String EFFECTS = String.join("\n",
            "uniform shader live;",
            "uniform shader lut;",
            "uniform float zebraA;",
            "uniform float zebraB;",
            "uniform float peak;",
            "uniform float3 peakColor;",
            "uniform int displayMode;",
            "uniform int lutKind;",
            "uniform float lutSize;",
            "uniform float lutTiles;",
            "uniform float3 domainMin;",
            "uniform float3 domainMax;",
            "float luma(float3 rgb) { return dot(rgb, float3(0.2126, 0.7152, 0.0722)); }",
            "float3 colorBand(float v) {",
            "    if (v < 0.03) return float3(0.45, 0.05, 0.65);",
            "    if (v < 0.10) return float3(0.08, 0.29, 1.0);",
            "    if (v < 0.40) return float3(v);",
            "    if (v < 0.50) return float3(0.07, 0.79, 0.32);",
            "    if (v < 0.60) return float3(1.0, 0.57, 0.66);",
            "    if (v < 0.90) return float3(v);",
            "    if (v < 0.98) return float3(1.0, 0.88, 0.12);",
            "    return float3(1.0, 0.10, 0.14);",
            "}",
            "float2 tile(float z) {",
            "    return float2(mod(z, lutTiles), floor(z / lutTiles)) * lutSize;",
            "}",
            "float3 applyLut(float3 rgb) {",
            "    float3 p = clamp((rgb - domainMin) / (domainMax - domainMin), 0.0, 1.0) * (lutSize - 1.0);",
            "    if (lutKind == 1) {",
            "        return float3(lut.eval(float2(p.r + 0.5, 0.5)).r,",
            "                      lut.eval(float2(p.g + 0.5, 0.5)).g,",
            "                      lut.eval(float2(p.b + 0.5, 0.5)).b);",
            "    }",
            "    float b = floor(p.b);",
            "    return mix(float3(lut.eval(tile(b) + p.rg + 0.5).rgb),",
            "               float3(lut.eval(tile(min(b + 1.0, lutSize - 1.0)) + p.rg + 0.5).rgb), fract(p.b));",
            "}",
            "half4 main(float2 p) {",
            "    float3 raw = float3(live.eval(p).rgb);",
            "    float v = luma(raw);",
            "    float3 outColor = raw;",
            "    if (lutKind > 0) outColor = applyLut(raw);",
            "    if (displayMode == 1) outColor = colorBand(v);",
            "    if (displayMode == 2) outColor = float3(raw.b);",
            "    if (zebraA >= 0.0 && v >= zebraA && mod(p.x + p.y, 13.0) < 4.0)",
            "        outColor = mix(outColor, float3(1.0, 0.92, 0.12), 0.82);",
            "    if (zebraB >= 0.0 && v >= zebraB && mod(p.x - p.y, 13.0) < 4.0)",
            "        outColor = mix(outColor, float3(1.0, 0.20, 0.28), 0.86);",
            "    if (peak > 0.0) {",
            "        float dx = luma(float3(live.eval(p + float2(1, 0)).rgb))",
            "                 - luma(float3(live.eval(p - float2(1, 0)).rgb));",
            "        float dy = luma(float3(live.eval(p + float2(0, 1)).rgb))",
            "                 - luma(float3(live.eval(p - float2(0, 1)).rgb));",
            "        float edge = sqrt(dx * dx + dy * dy);",
            "        outColor = mix(outColor, peakColor, smoothstep(peak, peak + 0.08, edge) * 0.90);",
            "    }",
            "    return half4(half3(outColor), 1.0);",
            "}");

    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final RectF guideArea = new RectF();
    private final RectF scopeArea = new RectF();
    private final float density;
    private Bitmap image;
    private Bitmap scopeImage;
    private BitmapShader imageInput;
    private BitmapShader lutInput;
    private RuntimeShader effects;
    private CubeLut lut;
    private boolean shaderFailed;
    Size size = Size.FIT;
    float zoom = 1;
    float panX;
    float panY;
    float desqueeze = 1;
    float guideRatio;
    boolean grid;
    boolean crosshair;
    int safeArea;
    boolean mask;
    boolean flipHorizontal;
    boolean flipVertical;
    int displayMode;
    int peaking;
    int peakingColor = Color.GREEN;
    int zebraA = -1;
    int zebraB = -1;
    volatile VideoScopes.Mode scope = VideoScopes.Mode.OFF;
    boolean scopeLarge;
    boolean controlsVisible;
    int controlsHeight;
    private float level = Float.NaN;
    private float focusX;
    private float focusY;
    private int focusColor;
    private String focusLabel;
    private long focusUntil;

    MonitorImage(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        setContentDescription("Imagen del monitor, toca para mostrar los controles");
        setClickable(true);
    }

    void show(Bitmap frame, Bitmap chart, VideoScopes.Mode measuredMode) {
        image = frame;
        imageInput = null;
        if (chart != null && measuredMode == scope) scopeImage = chart;
        invalidate();
    }

    void setScope(VideoScopes.Mode mode) {
        if (scope != mode) scopeImage = null;
        scope = mode;
        invalidate();
    }

    void setLut(CubeLut lut) {
        this.lut = lut;
        lutInput = lut != null ? input(lut.bitmap) : null;
        invalidate();
    }

    boolean hasLut() { return lut != null; }

    boolean isPictureAt(float x, float y) {
        return image != null && destination.contains(x, y);
    }

    void setLevel(float angle) {
        if (Float.compare(level, angle) == 0) return;
        level = angle;
        invalidate();
    }

    void showFocusTap(float x, float y) {
        focusX = x;
        focusY = y;
        focusColor = 0xffffca59;
        focusLabel = "AF · solicitando";
        focusUntil = SystemClock.uptimeMillis() + 1600;
        invalidate();
        postInvalidateDelayed(1600);
    }

    void showFocusResult(boolean accepted) {
        if (SystemClock.uptimeMillis() >= focusUntil) return;
        focusColor = accepted ? 0xff79f9b3 : 0xffff6976;
        focusLabel = accepted ? "AF · orden aceptada" : "AF · orden rechazada";
        focusUntil = SystemClock.uptimeMillis() + 1300;
        invalidate();
        postInvalidateDelayed(1300);
    }

    void clearFocusTap() {
        focusUntil = 0;
        invalidate();
    }

    void resetZoom() {
        zoom = 1;
        panX = 0;
        panY = 0;
        invalidate();
    }

    void zoomAt(float value, float x, float y) {
        float next = Math.max(1, Math.min(4, value));
        if (next == 1) {
            resetZoom();
            return;
        }
        float factor = next / zoom;
        panX = x - getWidth() / 2f - (x - getWidth() / 2f - panX) * factor;
        panY = y - getHeight() / 2f - (y - getHeight() / 2f - panY) * factor;
        zoom = next;
        invalidate();
    }

    void pan(float x, float y) {
        if (zoom <= 1 && size != Size.FILL && size != Size.PIXEL) return;
        panX += x;
        panY += y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (image == null) return;
        float sourceWidth = image.getWidth() * desqueeze;
        float sourceHeight = image.getHeight();
        float horizontal = getWidth() / sourceWidth;
        float vertical = getHeight() / sourceHeight;
        float scale = (size == Size.PIXEL ? 1 : size == Size.FILL
                ? Math.max(horizontal, vertical) : Math.min(horizontal, vertical)) * zoom;
        float width = sourceWidth * scale;
        float height = sourceHeight * scale;
        panX = limitPan(panX, width, getWidth());
        panY = limitPan(panY, height, getHeight());
        float left = (getWidth() - width) / 2 + panX;
        float top = (getHeight() - height) / 2 + panY;
        destination.set(left, top, left + width, top + height);
        if (canvas.isHardwareAccelerated() && hasEffects() && drawEffects(canvas)) {
            imagePaint.setShader(null);
        } else {
            int previous = canvas.save();
            canvas.scale(flipHorizontal ? -1 : 1, flipVertical ? -1 : 1,
                    destination.centerX(), destination.centerY());
            canvas.drawBitmap(image, null, destination, imagePaint);
            canvas.restoreToCount(previous);
        }
        drawGuides(canvas);
        drawLevel(canvas);
        drawFocusTap(canvas);
        if (displayMode == 1 && !shaderFailed) drawColorScale(canvas);
        if (scope != VideoScopes.Mode.OFF && scopeImage != null) drawScope(canvas);
    }

    private static float limitPan(float value, float picture, float screen) {
        float maximum = Math.max(0, (picture - screen) / 2);
        return Math.max(-maximum, Math.min(maximum, value));
    }

    private boolean hasEffects() {
        return !shaderFailed && (displayMode != 0 || lut != null || peaking > 0 || zebraA >= 0 || zebraB >= 0);
    }

    private static BitmapShader input(Bitmap bitmap) {
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return shader;
    }

    private boolean drawEffects(Canvas canvas) {
        try {
            if (effects == null) effects = new RuntimeShader(EFFECTS);
            if (imageInput == null) imageInput = input(image);
            effects.setInputShader("live", imageInput);
            effects.setInputShader("lut", lutInput != null ? lutInput : imageInput);
            effects.setIntUniform("displayMode", displayMode);
            effects.setIntUniform("lutKind", lut == null ? 0 : lut.oneDimensional ? 1 : 2);
            effects.setFloatUniform("lutSize", lut == null ? 1 : lut.size);
            effects.setFloatUniform("lutTiles", lut == null ? 1 : lut.tiles);
            effects.setFloatUniform("domainMin", lut == null ? 0 : lut.low[0],
                    lut == null ? 0 : lut.low[1], lut == null ? 0 : lut.low[2]);
            effects.setFloatUniform("domainMax", lut == null ? 1 : lut.high[0],
                    lut == null ? 1 : lut.high[1], lut == null ? 1 : lut.high[2]);
            effects.setFloatUniform("zebraA", zebraA / 100f);
            effects.setFloatUniform("zebraB", zebraB / 100f);
            effects.setFloatUniform("peak", peaking == 0 ? 0 : (4 - peaking) * 0.075f);
            effects.setFloatUniform("peakColor", Color.red(peakingColor) / 255f,
                    Color.green(peakingColor) / 255f, Color.blue(peakingColor) / 255f);
            imagePaint.setShader(effects);
        } catch (IllegalArgumentException error) {
            shaderFailed = true;
            imagePaint.setShader(null);
            Log.e("MonitorLumix", "Los efectos no están disponibles para este renderizador", error);
            return false;
        }
        int previous = canvas.save();
        canvas.scale(flipHorizontal ? -1 : 1, flipVertical ? -1 : 1,
                destination.centerX(), destination.centerY());
        canvas.translate(destination.left, destination.top);
        canvas.scale(destination.width() / image.getWidth(), destination.height() / image.getHeight());
        canvas.drawRect(0, 0, image.getWidth(), image.getHeight(), imagePaint);
        canvas.restoreToCount(previous);
        return true;
    }

    private void drawFocusTap(Canvas canvas) {
        if (SystemClock.uptimeMillis() >= focusUntil) return;
        float half = 19 * density;
        drawPaint.setColor(focusColor);
        drawPaint.setStrokeWidth(2 * density);
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setShadowLayer(2 * density, 0, 0, Color.BLACK);
        canvas.drawRoundRect(focusX - half, focusY - half, focusX + half, focusY + half,
                3 * density, 3 * density, drawPaint);
        drawPaint.setStyle(Paint.Style.FILL);
        drawPaint.setTextSize(11 * density);
        float width = drawPaint.measureText(focusLabel);
        float x = Math.max(4 * density, Math.min(focusX - width / 2, getWidth() - width - 4 * density));
        float y = focusY - half - 7 * density;
        if (y < 16 * density) y = focusY + half + 16 * density;
        canvas.drawText(focusLabel, x, y, drawPaint);
        drawPaint.clearShadowLayer();
    }

    private void drawLevel(Canvas canvas) {
        if (Float.isNaN(level)) return;
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        float span = Math.min(destination.width(), getWidth()) * 0.18f;
        float gap = 14 * density;
        drawPaint.setStrokeWidth(2 * density);
        drawPaint.setShadowLayer(2 * density, 0, 0, Color.BLACK);
        drawPaint.setColor(0xccffffff);
        canvas.drawLine(x - span, y, x - span + 8 * density, y, drawPaint);
        canvas.drawLine(x + span - 8 * density, y, x + span, y, drawPaint);
        int saved = canvas.save();
        canvas.rotate(level, x, y);
        drawPaint.setColor(Math.abs(level) <= 1f ? 0xff64f299 : 0xffffca59);
        canvas.drawLine(x - span, y, x - gap, y, drawPaint);
        canvas.drawLine(x + gap, y, x + span, y, drawPaint);
        canvas.restoreToCount(saved);
        drawPaint.clearShadowLayer();
    }

    private void drawGuides(Canvas canvas) {
        if (guideRatio <= 0 && !grid && !crosshair && safeArea == 0) return;
        guideArea.set(destination);
        if (guideRatio > 0) {
            float width = Math.min(destination.width(), destination.height() * guideRatio);
            float height = Math.min(destination.height(), destination.width() / guideRatio);
            guideArea.inset((destination.width() - width) / 2, (destination.height() - height) / 2);
            if (mask) {
                drawPaint.setColor(0x78000000);
                canvas.drawRect(destination.left, destination.top, destination.right, guideArea.top, drawPaint);
                canvas.drawRect(destination.left, guideArea.bottom, destination.right, destination.bottom, drawPaint);
                canvas.drawRect(destination.left, guideArea.top, guideArea.left, guideArea.bottom, drawPaint);
                canvas.drawRect(guideArea.right, guideArea.top, destination.right, guideArea.bottom, drawPaint);
            }
        }
        drawPaint.setColor(0xd5ffffff);
        drawPaint.setStrokeWidth(Math.max(1, density));
        drawPaint.setStyle(Paint.Style.STROKE);
        drawPaint.setShadowLayer(2 * density, 0, 0, Color.BLACK);
        if (guideRatio > 0) canvas.drawRect(guideArea, drawPaint);
        if (grid) {
            for (int i = 1; i <= 2; i++) {
                float x = guideArea.left + guideArea.width() * i / 3;
                float y = guideArea.top + guideArea.height() * i / 3;
                canvas.drawLine(x, guideArea.top, x, guideArea.bottom, drawPaint);
                canvas.drawLine(guideArea.left, y, guideArea.right, y, drawPaint);
            }
        }
        if (crosshair) {
            float x = guideArea.centerX();
            float y = guideArea.centerY();
            float span = 12 * density;
            canvas.drawLine(x - span, y, x + span, y, drawPaint);
            canvas.drawLine(x, y - span, x, y + span, drawPaint);
        }
        if (safeArea > 0) {
            float margin = (100 - safeArea) / 200f;
            guideArea.inset(guideArea.width() * margin, guideArea.height() * margin);
            canvas.drawRect(guideArea, drawPaint);
        }
        drawPaint.clearShadowLayer();
        drawPaint.setStyle(Paint.Style.FILL);
    }

    private void drawColorScale(Canvas canvas) {
        int[] colors = {0xff7310a7, 0xff144aff, 0xff505050, 0xff12c952,
                0xffff91a8, 0xffaaaaaa, 0xffffe01f, 0xffff1a23};
        String[] levels = {"0", "3", "10", "40", "50", "60", "90", "98%"};
        float x = 18 * density;
        float y = 66 * density;
        float width = 27 * density;
        float height = 15 * density;
        drawPaint.setTextSize(10 * density);
        for (int i = 0; i < colors.length; i++) {
            drawPaint.setColor(colors[i]);
            canvas.drawRect(x + i * width, y, x + (i + 1) * width, y + height, drawPaint);
            drawPaint.setColor(i == 1 || i == 2 || i == 0 ? Color.WHITE : Color.BLACK);
            canvas.drawText(levels[i], x + i * width + 2 * density, y + height - 4 * density, drawPaint);
        }
    }

    private void drawScope(Canvas canvas) {
        float width = scopeLarge ? Math.min(getWidth() * 0.58f, 430 * density) : 170 * density;
        float height = scopeLarge ? Math.min(getHeight() * 0.65f, width * 0.62f) : 100 * density;
        float right = getWidth() - 18 * density;
        float bottom = getHeight() - 14 * density - (controlsVisible ? controlsHeight : 0);
        if (bottom - height < 8 * density) return;
        scopeArea.set(right - width, bottom - height, right, bottom);
        VideoScopes.draw(canvas, scopeArea, scopeImage, scope, density, drawPaint);
    }
}
