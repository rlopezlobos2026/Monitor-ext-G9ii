package cl.monitorexterno.g9ii;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

final class VideoScopes {
    enum Mode {
        OFF("Oculto"), HISTOGRAM("Histograma RGB"), WAVEFORM("Forma de onda"),
        PARADE("Parada RGB"), VECTOR("Vectorscopio");

        final String label;
        Mode(String label) { this.label = label; }
    }

    private static final int WIDTH = 144;
    private static final int HEIGHT = 88;
    private static final int[] COLORS = {0xffff7272, 0xff76ef9c, 0xff79aaff};

    static Bitmap measure(Bitmap image, Mode mode) {
        Bitmap sampled = Bitmap.createScaledBitmap(image, WIDTH, HEIGHT, false);
        int[] samples = new int[WIDTH * HEIGHT];
        sampled.getPixels(samples, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        if (sampled != image) sampled.recycle();
        int[] density = new int[WIDTH * HEIGHT];
        if (mode == Mode.HISTOGRAM) return histogram(samples);
        for (int i = 0; i < samples.length; i++) {
            int color = samples[i];
            int red = color >> 16 & 255;
            int green = color >> 8 & 255;
            int blue = color & 255;
            int luma = (54 * red + 183 * green + 19 * blue) >> 8;
            int x = i % WIDTH;
            if (mode == Mode.PARADE) {
                add(density, x / 3, level(red));
                add(density, x / 3 + WIDTH / 3, level(green));
                add(density, x / 3 + WIDTH * 2 / 3, level(blue));
            } else if (mode == Mode.VECTOR) {
                float cb = (blue - luma) / 1.8556f;
                float cr = (red - luma) / 1.5748f;
                add(density, Math.round(WIDTH / 2f + cb * (HEIGHT - 2) / 255f),
                        Math.round(HEIGHT / 2f - cr * (HEIGHT - 2) / 255f));
            } else {
                add(density, x, level(luma));
            }
        }
        int[] dots = new int[density.length];
        for (int i = 0; i < dots.length; i++) {
            if (density[i] == 0) continue;
            int alpha = Math.min(255, 42 + (int) (45 * Math.sqrt(density[i])));
            int tint = mode == Mode.PARADE ? COLORS[(i % WIDTH) / (WIDTH / 3)] : 0xff83f9cc;
            dots[i] = alpha << 24 | tint & 0xffffff;
        }
        return Bitmap.createBitmap(dots, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    }

    private static int level(int value) {
        return HEIGHT - 1 - value * (HEIGHT - 1) / 255;
    }

    private static void add(int[] density, int x, int y) {
        if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) density[y * WIDTH + x]++;
    }

    private static Bitmap histogram(int[] samples) {
        int[][] buckets = new int[3][WIDTH];
        int peak = 1;
        for (int color : samples) {
            for (int channel = 0; channel < 3; channel++) {
                int value = color >> ((2 - channel) * 8) & 255;
                int bucket = value * (WIDTH - 1) / 255;
                peak = Math.max(peak, ++buckets[channel][bucket]);
            }
        }
        Bitmap result = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int channel = 0; channel < 3; channel++) {
            paint.setColor(COLORS[channel]);
            for (int bin = 1; bin < WIDTH; bin++) {
                canvas.drawLine(bin - 1, HEIGHT - 1 - buckets[channel][bin - 1] * (HEIGHT - 2f) / peak,
                        bin, HEIGHT - 1 - buckets[channel][bin] * (HEIGHT - 2f) / peak, paint);
            }
        }
        return result;
    }

    static void draw(Canvas canvas, RectF area, Bitmap chart, Mode mode, float density, Paint paint) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xda071014);
        canvas.drawRoundRect(area, 7 * density, 7 * density, paint);
        paint.setColor(0xffdae9e7);
        paint.setTextSize(12 * density);
        canvas.drawText(mode.label, area.left + 8 * density, area.top + 16 * density, paint);
        float pad = 7 * density;
        RectF plot = new RectF(area.left + pad, area.top + 21 * density, area.right - pad, area.bottom - pad);
        canvas.drawBitmap(chart, null, plot, paint);
        paint.setColor(0x60ffffff);
        paint.setStrokeWidth(density);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(plot, paint);
        if (mode == Mode.VECTOR) {
            float radius = Math.min(plot.width(), plot.height()) / 2;
            canvas.drawCircle(plot.centerX(), plot.centerY(), radius, paint);
            canvas.drawLine(plot.centerX() - radius, plot.centerY(), plot.centerX() + radius, plot.centerY(), paint);
            canvas.drawLine(plot.centerX(), plot.centerY() - radius, plot.centerX(), plot.centerY() + radius, paint);
        } else {
            for (int i = 1; i <= 3; i++) {
                float y = plot.top + plot.height() * i / 4;
                canvas.drawLine(plot.left, y, plot.right, y, paint);
            }
            if (mode == Mode.PARADE) {
                for (int i = 1; i <= 2; i++) {
                    float x = plot.left + plot.width() * i / 3;
                    canvas.drawLine(x, plot.top, x, plot.bottom, paint);
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
