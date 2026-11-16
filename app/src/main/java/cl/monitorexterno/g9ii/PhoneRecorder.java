package cl.monitorexterno.g9ii;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class PhoneRecorder {
    interface Listener {
        void onRecording();
        void onClosed(String outcome);
    }

    private static final long MIN_FRAME_GAP_NANOS = 24_000_000;
    private final Context context;
    private final ContentResolver resolver;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("Lumix-Rec");
    private final Handler handler;
    private final AtomicReference<Frame> latest = new AtomicReference<>();
    private final AtomicBoolean drawing = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private volatile boolean accepting = true;
    private MediaRecorder recorder;
    private RecorderSurface renderer;
    private Surface input;
    private ParcelFileDescriptor file;
    private Uri uri;
    private String filename;
    private long lastFrame;
    private int frameCount;
    private boolean started;

    PhoneRecorder(Context context, int width, int height, Listener listener) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
        float ratio = Math.min(1f, Math.min(1920f / width, 1080f / height));
        int recordWidth = Math.max(2, Math.round(width * ratio / 2) * 2);
        int recordHeight = Math.max(2, Math.round(height * ratio / 2) * 2);
        handler.post(() -> begin(recordWidth, recordHeight));
    }

    void submit(Bitmap image, long timestamp) {
        if (!accepting) return;
        latest.set(new Frame(image, timestamp));
        enqueueDrawing();
    }

    void stop() {
        accepting = false;
        if (closing.compareAndSet(false, true)) handler.post(() -> finish(null));
    }

    private void enqueueDrawing() {
        if (accepting && drawing.compareAndSet(false, true)) handler.post(this::encodeNewest);
    }

    private void begin(int width, int height) {
        if (!accepting) return;
        try {
            filename = "Lumix_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + ".mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Monitor Lumix");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Android no pudo crear el archivo de video.");
            file = resolver.openFileDescriptor(uri, "rw");
            if (file == null) throw new IOException("Android no pudo abrir el archivo de video.");

            recorder = new MediaRecorder(context);
            recorder.setOnErrorListener((source, error, detail) -> fail("Grabación interrumpida (" + error + ")."));
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(Math.max(1_500_000, width * height * 6));
            recorder.setOutputFile(file.getFileDescriptor());
            recorder.prepare();
            input = recorder.getSurface();
            renderer = new RecorderSurface(input, width, height);
            recorder.start();
            started = true;
            if (accepting) listener.onRecording();
        } catch (IOException | RuntimeException error) {
            fail("No se pudo iniciar la grabación en el teléfono. " + detail(error));
        }
    }

    private void encodeNewest() {
        Frame frame = latest.getAndSet(null);
        try {
            if (renderer != null && frame != null && frame.timestamp - lastFrame >= MIN_FRAME_GAP_NANOS) {
                renderer.frame(frame.image, frame.timestamp);
                frameCount++;
                lastFrame = frame.timestamp;
            }
        } catch (RuntimeException error) {
            fail("No se pudo grabar un fotograma. " + detail(error));
        } finally {
            drawing.set(false);
            if (latest.get() != null) enqueueDrawing();
        }
    }

    private void fail(String failure) {
        accepting = false;
        if (closing.compareAndSet(false, true)) finish(failure);
    }

    private void finish(String failure) {
        latest.set(null);
        boolean saved = false;
        if (started) {
            try {
                recorder.stop();
                saved = frameCount > 0;
            } catch (RuntimeException error) {
                if (failure == null) failure = "La grabación no se pudo cerrar. Prueba con un clip de varios segundos.";
            }
        }
        if (renderer != null) renderer.close();
        if (recorder != null) recorder.release();
        if (input != null) input.release();
        if (file != null) {
            try {
                file.close();
            } catch (IOException ignored) {
                // MediaRecorder already finalized the file.
            }
        }
        if (uri != null) {
            try {
                if (saved) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                    saved = resolver.update(uri, ready, null, null) == 1;
                }
                if (!saved) resolver.delete(uri, null, null);
            } catch (RuntimeException error) {
                saved = false;
                if (failure == null) failure = "Android no pudo publicar el clip. " + detail(error);
            }
        }
        final String message = saved
                ? (failure == null ? "" : failure + " ") + "Video guardado en Películas/Monitor Lumix: " + filename
                : failure != null ? failure : "Grabación cancelada antes de recibir video.";
        listener.onClosed(message);
        thread.quitSafely();
    }

    private static String detail(Throwable error) {
        return error.getMessage() == null ? "" : error.getMessage();
    }

    private static final class Frame {
        final Bitmap image;
        final long timestamp;

        Frame(Bitmap image, long timestamp) {
            this.image = image;
            this.timestamp = timestamp;
        }
    }
}
