package cl.monitorexterno.g9ii;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MonitorActivity extends Activity {
    private static final String USB_PERMISSION = "cl.monitorexterno.g9ii.USB_PERMISSION";
    private final StringBuilder diagnostic = new StringBuilder();
    private final AtomicBoolean renderPending = new AtomicBoolean();
    private UsbManager usbManager;
    private UsbDevice requesting;
    private LivePreview preview;
    private ImageView image;
    private TextView message;
    private Button toggle;
    private boolean frameSeen;
    private boolean destroyed;
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = requesting;
            requesting = null;
            if (device == null || !USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            if (usbManager.hasPermission(device)) {
                startPreview(device);
            } else {
                showStatus("Android no concedió el acceso USB. Toca Conectar para reintentar.");
                toggle.setEnabled(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(0xff101618);
        int padding = (int) (14 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding / 2, padding, padding / 2);

        TextView title = new TextView(this);
        title.setText("Monitor Lumix USB  ·  Prueba 0.1");
        title.setTextSize(22);
        title.setTextColor(0xffeef7f3);
        content.addView(title);

        image = new ImageView(this);
        image.setBackgroundColor(0xff050809);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("Vista en vivo de la cámara");
        content.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));

        message = new TextView(this);
        message.setTextColor(0xffccded8);
        message.setTextSize(15);
        message.setPadding(0, padding / 2, 0, 0);
        content.addView(message);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        toggle = new Button(this);
        toggle.setText("Conectar");
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (preview == null) {
                    findCamera();
                } else {
                    toggle.setEnabled(false);
                    preview.stop();
                    showStatus("Cerrando la sesión USB.");
                }
            }
        });
        Button copy = new Button(this);
        copy.setText("Copiar diagnóstico");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyDiagnostic();
            }
        });
        buttons.addView(toggle);
        buttons.addView(copy);
        content.addView(buttons);
        setContentView(content);

        diagnostic.append("Monitor Lumix USB 0.1\n")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE).append('\n');
        showStatus("Conecta la Lumix, selecciona PC(Tether) en la cámara y toca Conectar.");
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
    }

    private void findCamera() {
        UsbDevice camera = null;
        log("Buscando dispositivos USB.");
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            log("USB " + PtpConnection.hex(device.getVendorId()) + ":"
                    + PtpConnection.hex(device.getProductId()));
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface endpointGroup = device.getInterface(i);
                log("Interfaz " + i + ", clase " + endpointGroup.getInterfaceClass()
                        + ", endpoints " + endpointGroup.getEndpointCount());
            }
            if (device.getVendorId() == LivePreview.PANASONIC_USB_ID) {
                camera = device;
            }
        }
        if (camera == null) {
            showStatus("Android todavía no detecta la Lumix. Revisa el cable de datos y el modo PC(Tether).");
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
        showStatus("Acepta la solicitud de acceso USB en Android.");
        toggle.setEnabled(false);
        try {
            usbManager.requestPermission(camera, callback);
        } catch (RuntimeException error) {
            requesting = null;
            toggle.setEnabled(true);
            showStatus("Android no pudo solicitar permiso USB. Vuelve a conectar la cámara.");
            log(error.toString());
        }
    }

    private void startPreview(UsbDevice device) {
        frameSeen = false;
        image.setImageDrawable(null);
        toggle.setText("Detener");
        toggle.setEnabled(true);
        preview = new LivePreview(usbManager, device, new LivePreview.Listener() {
            @Override
            public void onPhase(final String status) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!destroyed) {
                            showStatus(status);
                        }
                    }
                });
            }

            @Override
            public void onFrame(final Bitmap frame, final double fps) {
                if (!renderPending.compareAndSet(false, true)) {
                    frame.recycle();
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (destroyed) {
                                frame.recycle();
                                return;
                            }
                            image.setImageBitmap(frame);
                            String size = frame.getWidth() + " × " + frame.getHeight();
                            if (!frameSeen) {
                                frameSeen = true;
                                log("Primera imagen recibida y mostrada: " + size);
                            }
                            String rate = fps > 0
                                    ? String.format(Locale.getDefault(), " · %.1f fps recibidos", fps) : "";
                            message.setText("Vista en vivo · " + size + rate);
                        } finally {
                            renderPending.set(false);
                        }
                    }
                });
            }

            @Override
            public void onFinished(final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        preview = null;
                        if (!destroyed) {
                            image.setImageDrawable(null);
                            toggle.setText("Conectar");
                            toggle.setEnabled(true);
                            showStatus(error == null ? "Sesión USB cerrada." : error);
                        }
                    }
                });
            }
        });
        new Thread(preview, "Lumix-USB").start();
    }

    private void showStatus(String status) {
        message.setText(status);
        log(status);
    }

    private void log(String entry) {
        if (diagnostic.length() > 12000) {
            diagnostic.delete(0, diagnostic.length() - 8000);
        }
        diagnostic.append(entry).append('\n');
    }

    private void copyDiagnostic() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico Monitor Lumix", diagnostic.toString()));
        Toast.makeText(this, "Diagnóstico copiado", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (preview != null) {
            preview.stop();
        }
        unregisterReceiver(permissionReceiver);
        super.onDestroy();
    }
}
