package cl.monitorexterno.g9ii;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import java.io.IOException;

final class LivePreview implements Runnable {
    interface Listener {
        void onPhase(String message);
        void onFrame(Bitmap frame, double fps);
        void onFinished(String error);
    }

    static final int PANASONIC_USB_ID = 0x04da;
    private static final int OPEN_SESSION = 0x1002;
    private static final int CLOSE_SESSION = 0x1003;
    private static final int LIVE_VIEW = 0x9412;
    private static final int READ_FRAME = 0x9706;
    private static final int START_LIVE_VIEW = 0x0d000010;
    private static final int STOP_LIVE_VIEW = 0x0d000011;
    private final UsbManager manager;
    private final UsbDevice device;
    private final Listener listener;
    private volatile boolean stopped;

    LivePreview(UsbManager manager, UsbDevice device, Listener listener) {
        this.manager = manager;
        this.device = device;
        this.listener = listener;
    }

    void stop() {
        stopped = true;
    }

    @Override
    public void run() {
        String error = null;
        try {
            preview();
        } catch (IOException | RuntimeException exception) {
            if (!stopped) {
                error = exception.getMessage();
                if (error == null) {
                    error = exception.getClass().getSimpleName();
                }
            }
        } finally {
            listener.onFinished(error);
        }
    }

    private void preview() throws IOException {
        UsbInterface ptpInterface = findPtpInterface();
        UsbEndpoint input = findEndpoint(ptpInterface, UsbConstants.USB_DIR_IN);
        UsbEndpoint output = findEndpoint(ptpInterface, UsbConstants.USB_DIR_OUT);
        UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("Android no pudo abrir la cámara por USB.");
        }

        boolean claimed = false;
        boolean sessionOpen = false;
        boolean previewOn = false;
        boolean transportOk = true;
        PtpConnection ptp = new PtpConnection(connection, input, output);
        try {
            claimed = connection.claimInterface(ptpInterface, true);
            if (!claimed) {
                throw new IOException("Android no pudo tomar la interfaz PTP.");
            }
            listener.onPhase("Abriendo sesión PTP por USB.");
            requireOk("Abrir sesión PTP", ptp.command(OPEN_SESSION, 1).code);
            sessionOpen = true;
            listener.onPhase("Solicitando vista en vivo a la Lumix.");
            requireOk("Activar vista en vivo",
                    ptp.command(LIVE_VIEW, START_LIVE_VIEW).panasonicCode());
            previewOn = true;
            SystemClock.sleep(120);
            receiveFrames(ptp);
        } catch (IOException exception) {
            transportOk = exception instanceof PreviewException;
            throw exception;
        } finally {
            if (transportOk && sessionOpen) {
                try {
                    if (previewOn) {
                        ptp.command(LIVE_VIEW, STOP_LIVE_VIEW);
                    }
                    ptp.command(CLOSE_SESSION);
                } catch (IOException ignored) {
                    // The cable may already have been disconnected.
                }
            }
            if (claimed) {
                connection.releaseInterface(ptpInterface);
            }
            connection.close();
        }
    }

    private UsbInterface findPtpInterface() throws IOException {
        UsbInterface candidate = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface current = device.getInterface(i);
            if (findEndpoint(current, UsbConstants.USB_DIR_IN) == null
                    || findEndpoint(current, UsbConstants.USB_DIR_OUT) == null) {
                continue;
            }
            if (current.getInterfaceClass() == UsbConstants.USB_CLASS_STILL_IMAGE) {
                return current;
            }
            if (current.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                candidate = current;
            }
        }
        if (candidate != null) {
            return candidate;
        }
        throw new IOException("La cámara no expuso una interfaz PTP. Selecciona PC(Tether) en la Lumix.");
    }

    private static UsbEndpoint findEndpoint(UsbInterface usbInterface, int direction) {
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getDirection() == direction
                    && endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                return endpoint;
            }
        }
        return null;
    }

    private void receiveFrames(PtpConnection ptp) throws IOException {
        long lastFrame = SystemClock.elapsedRealtime();
        long measureStart = lastFrame;
        int frameCount = 0;
        double fps = 0;
        while (!stopped) {
            PtpConnection.Reply reply = ptp.command(READ_FRAME);
            int status = reply.panasonicCode();
            if (status != PtpConnection.DEVICE_BUSY) {
                requireOk("Leer imagen", status);
                Bitmap frame = readJpeg(reply.data);
                if (frame != null) {
                    lastFrame = SystemClock.elapsedRealtime();
                    frameCount++;
                    if (lastFrame - measureStart >= 1000) {
                        fps = frameCount * 1000.0 / (lastFrame - measureStart);
                        measureStart = lastFrame;
                        frameCount = 0;
                    }
                    listener.onFrame(frame, fps);
                    continue;
                }
            }
            if (SystemClock.elapsedRealtime() - lastFrame >= 5000) {
                throw new PreviewException("La Lumix respondió, pero no llegó una imagen JPEG en cinco segundos.");
            }
            SystemClock.sleep(40);
        }
    }

    static Bitmap readJpeg(byte[] data) {
        if (data == null) {
            return null;
        }
        for (int start = 0; start + 2 < data.length; start++) {
            if ((data[start] & 255) == 255 && (data[start + 1] & 255) == 216
                    && (data[start + 2] & 255) == 255) {
                Bitmap result = BitmapFactory.decodeByteArray(data, start, data.length - start);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static void requireOk(String action, int code) throws IOException {
        if (code != PtpConnection.OK) {
            throw new PreviewException(action + ". Respuesta de la Lumix " + PtpConnection.hex(code) + ".");
        }
    }

    private static final class PreviewException extends IOException {
        PreviewException(String message) {
            super(message);
        }
    }
}
