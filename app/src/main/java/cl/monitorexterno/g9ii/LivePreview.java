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
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class LivePreview implements Runnable {
    interface Listener {
        void onPhase(String message);
        void onFrame(Bitmap frame, double fps);
        void onAutofocusResult(long request, int response);
        void onPreviewPause(String reason);
        void onMovieQuality(String detail);
        void onFormats(List<UsbVideoFormat> formats, UsbVideoFormat current);
        void onFormatMeasurement(UsbVideoFormat requested, int width, int height, double fps);
        void onUsbDiagnostic(String detail);
        void onNotice(String message);
        void onRecordingState(CameraControls.RecordingState state, String diagnostic);
        void onRecordingCommand(boolean start, int response);
        void onCameraChoices(CameraControls.Setting setting, CameraControls.Choices choices);
        void onCameraValue(CameraControls.Setting setting, Integer value);
        void onCameraSettingResult(CameraControls.Setting setting, Integer confirmed, String message);
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
    private final AtomicBoolean quickRequested = new AtomicBoolean();
    private final boolean g9ii;
    private int quickIndex = CameraControls.QUICK_SETTINGS.length;
    private long nextQuickPoll;
    private boolean canPollRecording = true;
    private boolean triedVendorSession;
    private boolean vendorSessionOpen;
    private long nextRecordingPoll;
    private long recordingPolledAt;
    private CameraControls.RecordingState reportedRecording;
    private CameraControls.Setting listedSetting;
    private CameraControls.Choices listedChoices;
    private final AtomicBoolean formatsRequested = new AtomicBoolean();
    private final AtomicReference<UsbVideoFormat> formatRequested = new AtomicReference<>();
    private List<UsbVideoFormat> availableFormats = Collections.emptyList();
    private UsbVideoFormat originalFormat;
    private UsbVideoFormat activeFormat;
    private boolean formatChanged;
    private final UsbManager manager;
    private final UsbDevice device;
    private final Listener listener;
    private volatile boolean stopped;

    LivePreview(UsbManager manager, UsbDevice device, Listener listener) {
        this.manager = manager;
        this.device = device;
        this.listener = listener;
        g9ii = device.getProductId() == 0x2382;
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

    boolean autoFocus(long request) {
        return cameraRequests.offer(ptp -> listener.onAutofocusResult(request, CameraControls.autoFocus(ptp)));
    }

    boolean queryMovieQuality() {
        return cameraRequests.offer(ptp -> listener.onMovieQuality(CameraControls.movieQualityDiagnostic(ptp)));
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

    void refreshQuickSettings() {
        quickRequested.set(true);
    }

    boolean cameraChoices(CameraControls.Setting setting) {
        return cameraRequests.offer(ptp -> {
            listedSetting = setting;
            listedChoices = CameraControls.choices(ptp, setting, g9ii);
            listener.onCameraChoices(setting, listedChoices);
        });
    }

    boolean changeSetting(CameraControls.Setting setting, int value) {
        return cameraRequests.offer(ptp -> {
            if (listedSetting != setting || listedChoices == null || !listedChoices.permits(value)) {
                listener.onCameraSettingResult(setting, null, "Vuelve a consultar los valores de " + setting.name + ".");
                return;
            }
            int status = CameraControls.change(ptp, setting, value);
            if (status != PtpConnection.OK) {
                listener.onCameraSettingResult(setting, null,
                        setting.name + " · la Lumix rechazó el valor (" + PtpConnection.hex(status) + ").");
                return;
            }
            Integer current = CameraControls.current(ptp, setting);
            if (current == null) {
                CameraControls.Choices updated = CameraControls.choices(ptp, setting, g9ii);
                if (updated != null) current = updated.current;
            }
            if (current != null) {
                listedChoices = new CameraControls.Choices(current, listedChoices.values);
            }
            listener.onCameraSettingResult(setting, current, current == null
                    ? setting.name + " · orden aceptada, valor sin confirmar"
                    : setting.name + " · " + setting.label(current));
            if (setting == CameraControls.Setting.WHITE_BALANCE) {
                listener.onCameraValue(CameraControls.Setting.TEMPERATURE, null);
                refreshQuickSettings();
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
            listener.onUsbDiagnostic("USB bulk · paquete máximo IN " + input.getMaxPacketSize()
                    + " bytes · OUT " + output.getMaxPacketSize() + " bytes");
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
                        if (originalFormat != null && (formatChanged || !originalFormat.equals(activeFormat))) {
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
        boolean hasReceivedFrame = false;
        boolean paused = false;
        long nextRecovery = lastFrame + 5000;
        int recoveries = 0;
        UsbVideoFormat measuringFormat = null;
        while (!stopped) {
            CameraRequest cameraRequest = cameraRequests.poll();
            if (cameraRequest != null) cameraRequest.run(ptp);
            if (statusRequested.getAndSet(false)) {
                canPollRecording = true;
                nextRecordingPoll = 0;
            }
            long now = SystemClock.elapsedRealtime();
            if ((hasReceivedFrame || now - lastFrame > 2000) && canPollRecording && now >= nextRecordingPoll) {
                pollRecording(ptp);
            } else if (hasReceivedFrame && !paused && cameraRequest == null) {
                pollTelemetry(ptp, now);
            }
            if (formatsRequested.getAndSet(false)) readFormats(ptp);
            UsbVideoFormat requested = formatRequested.getAndSet(null);
            if (requested != null && availableFormats.contains(requested)) {
                applyFormat(ptp, requested);
                measuringFormat = requested;
                lastFrame = SystemClock.elapsedRealtime();
                measureStart = lastFrame;
                nextRecovery = lastFrame + 5000;
                frameCount = 0;
                fps = 0;
            }
            PtpConnection.Reply reply = ptp.command(READ_FRAME);
            int status = reply.panasonicCode();
            Bitmap frame = status == PtpConnection.OK ? readJpeg(reply.data) : null;
            now = SystemClock.elapsedRealtime();
            if (frame != null) {
                if (paused) {
                    paused = false;
                    listener.onPreviewPause(null);
                    measureStart = now;
                    frameCount = 0;
                    fps = 0;
                }
                hasReceivedFrame = true;
                lastFrame = now;
                nextRecovery = now + 5000;
                recoveries = 0;
                frameCount++;
                if (now - measureStart >= 1000) {
                    fps = frameCount * 1000.0 / (now - measureStart);
                    if (measuringFormat != null) {
                        listener.onUsbDiagnostic(String.format(Locale.ROOT,
                                "Vista previa USB recibida tras la selección · %d × %d · %.1f fps",
                                frame.getWidth(), frame.getHeight(), fps));
                        listener.onFormatMeasurement(measuringFormat, frame.getWidth(), frame.getHeight(), fps);
                        measuringFormat = null;
                        UsbVideoFormat confirmed = readCurrentFormat(ptp);
                        if (confirmed != null) activeFormat = confirmed;
                    }
                    measureStart = now;
                    frameCount = 0;
                }
                listener.onFrame(frame, fps);
                continue;
            }
            boolean freshState = recordingPolledAt != 0 && now - recordingPolledAt < 2500;
            boolean cameraOccupied = freshState && (reportedRecording == CameraControls.RecordingState.BUSY
                    || reportedRecording == CameraControls.RecordingState.RECORDING);
            if (!cameraOccupied && status != PtpConnection.OK && status != PtpConnection.DEVICE_BUSY) {
                requireOk("Leer imagen", status);
            }
            if (now - lastFrame >= 2000 && !paused) {
                paused = true;
                listener.onPreviewPause("VISTA USB · esperando imagen");
            }
            if (now >= nextRecovery && freshState && reportedRecording == CameraControls.RecordingState.READY
                    && recoveries < 2 && status != PtpConnection.DEVICE_BUSY) {
                listener.onPreviewPause("VISTA USB · recuperando señal");
                restartView(ptp);
                recoveries++;
                nextRecovery = SystemClock.elapsedRealtime() + 5000;
            }
            if (now - lastFrame >= 15000 && !cameraOccupied && status != PtpConnection.DEVICE_BUSY
                    && now >= nextRecovery) {
                throw new PreviewException("Se interrumpió la vista USB. Vuelve a conectar desde el monitor.");
            }
            SystemClock.sleep(40);
        }
    }

    private void restartView(PtpConnection ptp) throws IOException {
        ptp.command(LIVE_VIEW, STOP_LIVE_VIEW);
        if (stopped) return;
        int status = ptp.command(LIVE_VIEW, START_LIVE_VIEW).panasonicCode();
        if (status != PtpConnection.OK && status != PtpConnection.DEVICE_BUSY) {
            listener.onNotice("La Lumix respondió " + PtpConnection.hex(status) + " al reiniciar la vista USB.");
        }
    }

    private void pollTelemetry(PtpConnection ptp, long now) throws IOException {
        if (quickRequested.getAndSet(false)) quickIndex = 0;
        if (quickIndex < CameraControls.QUICK_SETTINGS.length && now >= nextQuickPoll) {
            nextQuickPoll = now + 150;
            CameraControls.Setting setting = CameraControls.QUICK_SETTINGS[quickIndex++];
            listener.onCameraValue(setting, CameraControls.current(ptp, setting));
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
        if (state.recognized) recordingPolledAt = now;
        else canPollRecording = false;
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
        listener.onUsbDiagnostic("Vista previa USB · lista " + PtpConnection.hex(modes.panasonicCode())
                + " · " + (modes.data == null ? 0 : modes.data.length) + " bytes · "
                + availableFormats.size() + " modos anunciados");
        for (UsbVideoFormat mode : availableFormats) {
            listener.onUsbDiagnostic("  " + mode.label() + " · parámetro " + mode.cameraParameter);
        }
        if (!availableFormats.isEmpty()) {
            UsbVideoFormat current = readCurrentFormat(ptp);
            if (current != null) {
                activeFormat = current;
                if (originalFormat == null) originalFormat = current;
            }
        }
        listener.onFormats(availableFormats, activeFormat);
    }

    private UsbVideoFormat readCurrentFormat(PtpConnection ptp) throws IOException {
        PtpConnection.Reply reply = ptp.command(GET_FORMATS, UsbVideoFormat.CURRENT);
        int response = reply.panasonicCode();
        UsbVideoFormat current = response == PtpConnection.OK ? UsbVideoFormat.parseCurrent(reply.data) : null;
        listener.onUsbDiagnostic("Vista previa USB · lectura del modo activo " + PtpConnection.hex(response)
                + " · " + (current == null ? "sin modo válido" : current.label() + " · parámetro " + current.cameraParameter));
        return current;
    }

    private void applyFormat(PtpConnection ptp, UsbVideoFormat requested) throws IOException {
        listener.onUsbDiagnostic("Vista previa USB solicitada · " + requested.label()
                + " · parámetro " + requested.cameraParameter);
        if (!requested.equals(activeFormat)) {
            int response = ptp.commandWithData(SET_FORMAT, requested.toPayload(), UsbVideoFormat.CURRENT).panasonicCode();
            if (response == PtpConnection.OK) {
                activeFormat = requested;
                formatChanged = true;
                listener.onNotice("La cámara aceptó la orden de vista previa USB (" + PtpConnection.hex(response) + ").");
            } else {
                listener.onNotice("La cámara rechazó la orden de vista previa USB (" + PtpConnection.hex(response) + ").");
            }
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
                if (result != null) return result;
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
