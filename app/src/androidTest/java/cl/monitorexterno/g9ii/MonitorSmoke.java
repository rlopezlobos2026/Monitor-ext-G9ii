package cl.monitorexterno.g9ii;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RuntimeShader;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.provider.MediaStore;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MonitorSmoke extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle output = new Bundle();
        try {
            new RuntimeShader(MonitorImage.EFFECTS);
            testLutAndPtpFormats();
            testRecording();
            output.putString("stream", "\nMONITOR_SMOKE_OK\n");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable error) {
            output.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, output);
        }
    }

    private void testLutAndPtpFormats() throws Exception {
        String cube = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n";
        CubeLut lut = CubeLut.read(new ByteArrayInputStream(cube.getBytes(StandardCharsets.US_ASCII)));
        require(lut.bitmap.getPixel(1, 0) == Color.RED && lut.bitmap.getPixel(2, 0) == Color.BLUE, "LUT RGB");
        UsbVideoFormat original = new UsbVideoFormat(1280, 720, 700, 30);
        require(original.equals(UsbVideoFormat.parseCurrent(original.toPayload())), "Formato PTP actual");
        ByteBuffer choices = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(UsbVideoFormat.LIST).putInt(12).putShort((short) 1).putShort((short) 8)
                .putShort((short) 720).putShort((short) 1280).putShort((short) 700).putShort((short) 30);
        require(UsbVideoFormat.parseList(choices.array()).contains(original), "Formatos PTP anunciados");
    }

    private void testRecording() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>();
        PhoneRecorder recorder = new PhoneRecorder(getTargetContext(), 640, 360, new PhoneRecorder.Listener() {
            @Override public void onRecording() { started.countDown(); }
            @Override public void onClosed(String result) {
                outcome.set(result);
                started.countDown();
                closed.countDown();
            }
        });
        try {
            require(started.await(12, TimeUnit.SECONDS) && outcome.get() == null,
                    "Abrir grabación: " + outcome.get());
            Bitmap frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
            frame.eraseColor(0xff237783);
            for (int i = 0; i < 45; i++) {
                recorder.submit(frame, System.nanoTime());
                Thread.sleep(35);
            }
        } finally {
            recorder.stop();
        }
        require(closed.await(12, TimeUnit.SECONDS), "Cerrar grabación");
        String result = outcome.get();
        require(result != null && result.contains("Video guardado"), "Publicar MP4: " + result);
        String filename = result.substring(result.lastIndexOf("Lumix_"));
        ContentResolver resolver = getTargetContext().getContentResolver();
        try (Cursor cursor = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID}, MediaStore.Video.Media.DISPLAY_NAME + "=?",
                new String[]{filename}, null)) {
            require(cursor != null && cursor.moveToFirst(), "Clip visible en el almacenamiento multimedia");
            android.net.Uri clip = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
            try (MediaMetadataRetriever video = new MediaMetadataRetriever()) {
                video.setDataSource(getTargetContext(), clip);
                require("640".equals(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),
                        "Ancho del video");
                require("360".equals(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)),
                        "Alto del video");
                require(Long.parseLong(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) > 500,
                        "Duración real del video");
            } finally {
                resolver.delete(clip, null, null);
            }
        }
    }

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
}
