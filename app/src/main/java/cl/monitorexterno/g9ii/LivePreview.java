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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class LivePreview implements Runnable {
    interface Listener {
        void onPhase(String message);
        void onFrame(Bitmap frame, double fps);
        void onFormats(List<UsbVideoFormat> formats, UsbVideoFormat current);
        void onNotice(String message);
        void onRecordingState(CameraControls.RecordingState state, String diagnostic);
        void onRecordingCommand(boolean start, int response);
        void onCameraChoices(CameraControls.Setting setting, CameraControls.Choices choices);
        void onCameraDiagnostic(String diagnostic);
        void onFinished(String error);
    }

    static final int PANASONIC_USB_ID = 0x04da;
    private static final int OPEN_SESSION = 0x1002;
    private static final int CLOSE_SESSION = 0x1003;
    private static final int LIVE_VIEW = 0x9412;
    private final BitmapFactory.Options jpegOptions = new BitmapFactory.Options();
    private static final int READ_FRAME = 0x9706;
    private static final int START_LIVE_VIEW = 0x0d000010;
    private static final int STOP_LIVE_VIEW = 0x0d000011;
    private static final int GET_FORMATS = 0x9414;
    private static final int SET_FORMAT = 0x9415;
    @FunctionalInterface
    private interface CameraRequest {
        void run(PtpConnection ptp) throws IOException;
    }
    private final ArrayBlockingQueue<CameraRequest> cameraRequests = new ArrayBlockingQueue<>(6);
    private final AtomicBoolean statusRequested = new AtomicBoolean();
    private boolean canPollRecording = true;
    private boolean triedVendorSession;
    private boolean vendorSessionOpen;
    private long nextRecordingPoll;
    private CameraControls.RecordingState reportedRecording;
    private CameraControls.Setting listedSetting;
    private CameraControls.Choices listedChoices;
    private final AtomicBoolean formatsRequested = new AtomicBoolean();
    private final AtomicReference<UsbVideoFormat> formatRequested = new AtomicReference<>();
    private List<UsbVideoFormat> availableFormats = Collections.emptyList();
    private UsbVideoFormat originalFormat;
    private UsbVideoFormat activeFormat;
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

    void queryFormats() {
        formatsRequested.set(true);
    }

    void changeFormat(UsbVideoFormat mode) {
        formatRequested.set(mode);
    }

    boolean movie(boolean start) {
        return cameraRequests.offer(ptp -> {
            int response = CameraControls.record(ptp, start);
            listener.onRecordingCommand(start, response);
            if (response == PtpConnection.OK) {
                canPollRecording = true;
                nextRecordingPoll = 0;
            }
        });
    }

    boolean autoFocus() {
        return cameraRequests.offer(ptp -> cameraResult("AF en el área activa", CameraControls.autoFocus(ptp)));
    }

    boolean adjustManualFocus(int step) {
        if (step < 1 || step > 4) return false;
        return cameraRequests.offer(ptp -> cameraResult("Enfoque manual", CameraControls.adjustManualFocus(ptp, step)));
    }

    boolean shutter() {
        return cameraRequests.offer(ptp -> cameraResult("Disparo fotográfico", CameraControls.shutter(ptp)));
    }

    void refreshRecordingState() {
        statusRequested.set(true);
    }

    boolean cameraChoices(CameraControls.Setting setting) {
        return cameraRequests.offer(ptp -> {
            listedSetting = setting;
            listedChoices = CameraControls.choices(ptp, setting);
            listener.onCameraChoices(setting, listedChoices);
        });
    }

    boolean changeSetting(CameraControls.Setting setting, int value) {
        return cameraRequests.offer(ptp -> {
            if (listedSetting != setting || listedChoices == null || !listedChoices.permits(value)) {
                listener.onNotice("Vuelve a consultar los valores de " + setting.name + ".");
                return;
            }
            listedChoices = null;
            int status = CameraControls.change(ptp, setting, value);
            if (status == PtpConnection.OK) {
                CameraControls.Choices updated = CameraControls.choices(ptp, setting);
                listener.onNotice(updated != null && updated.current == value
                        ? setting.name + " · " + setting.label(value)
                        : setting.name + " · cambio aceptado por la cámara");
            } else {
                cameraResult(setting.name, status);
            }
        });
    }

    boolean inventory() {
        return cameraRequests.offer(ptp -> listener.onCameraDiagnostic(CameraControls.inventory(ptp)));
    }

    private void cameraResult(String name, int status) {
        listener.onNotice(name + (status == PtpConnection.OK ? " · orden aceptada por la cámara"
                : " · la Lumix rechazó la orden (" + PtpConnection.hex(status) + ")"));
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
                        if (originalFormat != null && !originalFormat.equals(activeFormat)) {
                            ptp.commandWithData(SET_FORMAT, originalFormat.toPayload(), UsbVideoFormat.CURRENT);
                        }
                    }
                    if (vendorSessionOpen) ptp.command(0x9103, 0x00010001);
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
            CameraRequest cameraRequest = cameraRequests.poll();
            if (cameraRequest != null) {
                cameraRequest.run(ptp);
                lastFrame = SystemClock.elapsedRealtime();
            }
            if (statusRequested.getAndSet(false)) {
                canPollRecording = true;
                nextRecordingPoll = 0;
            }
            if (frameCount > 0 && canPollRecording && SystemClock.elapsedRealtime() >= nextRecordingPoll) {
                pollRecording(ptp);
            }
            if (formatsRequested.getAndSet(false)) {
                readFormats(ptp);
                lastFrame = SystemClock.elapsedRealtime();
            }
            UsbVideoFormat requested = formatRequested.getAndSet(null);
            if (requested != null && availableFormats.contains(requested)) {
                applyFormat(ptp, requested);
                lastFrame = SystemClock.elapsedRealtime();
                measureStart = lastFrame;
                frameCount = 0;
                fps = 0;
            }
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

    private void pollRecording(PtpConnection ptp) throws IOException {
        CameraControls.State state = CameraControls.readRecording(ptp);
        if (state.response == 0x2003 && !triedVendorSession) {
            triedVendorSession = true;
            vendorSessionOpen = ptp.command(0x9102, 0x00010001).panasonicCode() == PtpConnection.OK;
            if (vendorSessionOpen) state = CameraControls.readRecording(ptp);
        }
        long now = SystemClock.elapsedRealtime();
        nextRecordingPoll = now + 450;
        // A busy reply can be transient when the camera is saving or changing modes.
        if (state.response == PtpConnection.DEVICE_BUSY) return;
        if (!state.recognized) canPollRecording = false;
        if (reportedRecording != state.recording || !state.recognized) {
            reportedRecording = state.recording;
            String diagnostic = state.recognized ? "Estado de video: " + state.recording
                    : "Estado REC no disponible por USB (" + PtpConnection.hex(state.response) + ")";
            listener.onRecordingState(state.recording, diagnostic);
        }
    }

    private void readFormats(PtpConnection ptp) throws IOException {
        PtpConnection.Reply modes = ptp.command(GET_FORMATS, UsbVideoFormat.LIST);
        availableFormats = modes.panasonicCode() == PtpConnection.OK
                ? UsbVideoFormat.parseList(modes.data) : Collections.emptyList();
        if (!availableFormats.isEmpty()) {
            PtpConnection.Reply current = ptp.command(GET_FORMATS, UsbVideoFormat.CURRENT);
            if (current.panasonicCode() == PtpConnection.OK) {
                activeFormat = UsbVideoFormat.parseCurrent(current.data);
                if (originalFormat == null) originalFormat = activeFormat;
            }
        }
        listener.onFormats(availableFormats, activeFormat);
    }

    private void applyFormat(PtpConnection ptp, UsbVideoFormat requested) throws IOException {
        if (requested.equals(activeFormat)) return;
        PtpConnection.Reply reply = ptp.commandWithData(SET_FORMAT,
                requested.toPayload(), UsbVideoFormat.CURRENT);
        if (reply.panasonicCode() == PtpConnection.OK) {
            activeFormat = requested;
            listener.onNotice("La cámara aceptó " + requested.label()
                    + ". La pantalla mostrará el tamaño y la fluidez recibidos.");
        } else {
            listener.onNotice("La cámara rechazó ese tamaño (" + PtpConnection.hex(reply.panasonicCode())
                    + "). Se conserva la configuración anterior.");
        }
    }

    private Bitmap readJpeg(byte[] data) {
        if (data == null) {
            return null;
        }
        for (int start = 0; start + 2 < data.length; start++) {
            if ((data[start] & 255) == 255 && (data[start + 1] & 255) == 216
                    && (data[start + 2] & 255) == 255) {
                Bitmap result = BitmapFactory.decodeByteArray(data, start, data.length - start, jpegOptions);
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
