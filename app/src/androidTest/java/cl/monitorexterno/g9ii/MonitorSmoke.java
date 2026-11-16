package cl.monitorexterno.g9ii;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.RuntimeShader;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
            testCameraControls();
            testMonitorLayout();
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

    private void testCameraControls() {
        byte[] recorded = CameraControls.scalarPayload(0x12000013, 2, 1);
        require(CameraControls.decodeRecording(CameraControls.scalar(recorded))
                == CameraControls.RecordingState.RECORDING, "Estado REC de la cámara");
        require(CameraControls.decodeRecording(CameraControls.scalar(new byte[6]))
                == CameraControls.RecordingState.UNKNOWN, "Respuesta REC incompleta");
        ByteBuffer limits = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        limits.putInt(4, 7).position(36);
        limits.putInt(400).putInt(2).putInt(400).putInt(800);
        CameraControls.Choices choices = CameraControls.parseChoices(limits.array(), 4);
        require(choices != null && choices.current == 400 && choices.permits(800) && !choices.permits(200),
                "Opciones ISO anunciadas por la cámara");
        ByteBuffer payload = ByteBuffer.wrap(CameraControls.scalarPayload(0x02000041, 2, 28))
                .order(ByteOrder.LITTLE_ENDIAN);
        require(payload.getInt() == 0x02000041 && payload.getInt() == 2 && payload.getShort() == 28,
                "Cambio a diafragma f/2.8");
        require(PhoneVideoQuality.UHD.width == 3840 && PhoneVideoQuality.UHD.height == 2160,
                "Tamaño de salida UHD");
        require(CameraControls.documentedKelvinChoices(0x0002, 5500) == null,
                "El deslizador Kelvin requiere un modo K");
        require(CameraControls.documentedKelvinChoices(0x8010, null) == null,
                "El deslizador Kelvin requiere una lectura válida");
        CameraControls.Choices kelvin = CameraControls.documentedKelvinChoices(0x8010, 5575);
        require(kelvin != null && kelvin.current == 5575 && kelvin.permits(2500) && kelvin.permits(10000)
                && kelvin.permits(5600) && kelvin.permits(5575) && !kelvin.permits(10100), "Rango Kelvin G9 II");
        byte[] focal = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(17500).array();
        require(LensTelemetry.ptpFocalLength(focal) == 175.0, "Lectura focal óptica PTP");
        require(LensTelemetry.ptpFocalLength(new byte[3]) == 0, "Paquete focal incompleto");
    }

    private void testMonitorLayout() throws Exception {
        Intent launch = new Intent(getTargetContext(), MonitorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity monitor = startActivitySync(launch);
        try {
            Method quick = MonitorActivity.class.getDeclaredMethod("setQuickControlsEnabled", boolean.class);
            Method value = MonitorActivity.class.getDeclaredMethod("setQuickValue", CameraControls.Setting.class, Integer.class);
            Method focal = MonitorActivity.class.getDeclaredMethod("setFocalDisplay", double.class);
            quick.setAccessible(true);
            value.setAccessible(true);
            focal.setAccessible(true);
            MonitorImage image = (MonitorImage) field(monitor, "image");
            View notice = (View) field(monitor, "notice");
            Button connect = (Button) field(monitor, "connect");
            View tools = (View) field(monitor, "tools");
            AdjustmentPanel panel = (AdjustmentPanel) field(monitor, "adjustment");
            Bitmap frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(frame);
            Paint paint = new Paint();
            paint.setShader(new LinearGradient(0, 0, 640, 360,
                    0xff2c525d, 0xff806354, Shader.TileMode.CLAMP));
            canvas.drawPaint(paint);
            runOnMainSync(() -> {
                try {
                    quick.invoke(monitor, true);
                    value.invoke(monitor, CameraControls.Setting.ISO, 400);
                    value.invoke(monitor, CameraControls.Setting.SHUTTER, 50000);
                    value.invoke(monitor, CameraControls.Setting.APERTURE, 40);
                    value.invoke(monitor, CameraControls.Setting.EXPOSURE, 0);
                    value.invoke(monitor, CameraControls.Setting.WHITE_BALANCE, 0x8010);
                    value.invoke(monitor, CameraControls.Setting.TEMPERATURE, 5600);
                    focal.invoke(monitor, 125.0);
                    connect.setText("Salir USB");
                    notice.setVisibility(View.GONE);
                    image.show(frame, null, VideoScopes.Mode.OFF);
                    panel.open(CameraControls.Setting.TEMPERATURE);
                    panel.showChoices(CameraControls.documentedKelvinChoices(0x8010, 5600), "");
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            });
            waitForIdleSync();
            SeekBar slider = (SeekBar) field(panel, "slider");
            require(slider.isShown() && slider.getWidth() >= 70 && slider.getMax() == 75,
                    "Deslizador visible y ajustable");
            android.widget.TextView heading = (android.widget.TextView) field(panel, "heading");
            runOnMainSync(() -> slider.setProgress(35));
            waitForIdleSync();
            require("Kelvin\n6000 K".contentEquals(heading.getText()) && heading.getLineCount() == 2,
                    "El valor elegido se lee junto al deslizador");
            require(tools.getWidth() == image.getWidth() && connect.getWidth() > 0 && connect.isShown(),
                    "Barra ajustada a la pantalla");
            Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try (FileOutputStream file = getTargetContext().openFileOutput("monitor-ui.png", 0)) {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, file);
                }
            }
        } finally {
            runOnMainSync(monitor::finish);
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private void testRecording() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<String> outcome = new AtomicReference<>();
        PhoneVideoQuality.Profile profile = PhoneVideoQuality.USB.profile(640, 360);
        PhoneRecorder recorder = new PhoneRecorder(getTargetContext(), profile, new PhoneRecorder.Listener() {
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
