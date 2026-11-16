package cl.monitorexterno.g9ii;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Locale;

final class CameraControls {
    private static final int LIST_PROPERTY = 0x9108;
    private static final int GET_PROPERTY = 0x9402;
    private static final int SET_PROPERTY = 0x9403;
    private static final int STILL = 0x9404;
    private static final int FOCUS = 0x9405;
    private static final int MOVIE = 0x940c;
    private static final int QUERY_STATE = 0x9414;
    private static final int RECORDING_STATUS = 0x12000013;
    private static final int AF_AREA = 0x02000072;
    private static final int PHOTO_STYLE = 0x02000010;

    enum RecordingState { UNKNOWN, READY, RECORDING, BUSY }

    enum Setting {
        ISO("ISO", 0x02000020, 0x02000021, 4),
        APERTURE("Diafragma", 0x02000040, 0x02000041, 2),
        SHUTTER("Velocidad de obturación", 0x02000030, 0x02000031, 4),
        EXPOSURE("Compensación EV", 0x02000060, 0x02000061, 2),
        WHITE_BALANCE("Balance de blancos", 0x02000051, 0x02000051, 2),
        FOCUS_MODE("Modo de enfoque", 0x02000071, 0x02000071, 2);

        final String name;
        final int descriptor;
        final int property;
        final int bytes;

        Setting(String name, int descriptor, int property, int bytes) {
            this.name = name;
            this.descriptor = descriptor;
            this.property = property;
            this.bytes = bytes;
        }

        String label(int value) {
            DecimalFormat number = new DecimalFormat("0.###");
            switch (this) {
                case ISO:
                    return value == 0 ? "Auto" : Integer.toUnsignedString(value);
                case APERTURE:
                    return value == 0 || value == 0xffff ? "Sin valor" : "f/" + number.format(value / 10.0);
                case SHUTTER:
                    if (value == -1) return "Bulb";
                    if (value == 0) return "Sin valor";
                    return value < 0 ? number.format((value & Integer.MAX_VALUE) / 1000.0) + " s"
                            : "1/" + number.format(value / 1000.0) + " s";
                case EXPOSURE:
                    return number.format(((value & 0x8000) != 0 ? -1 : 1) * (value & 0x7fff) / 3.0) + " EV";
                case WHITE_BALANCE:
                    return wb(value);
                case FOCUS_MODE:
                    String[] modes = {"AF", "Macro AF", "Macro AF (D)", "MF", "AF-S", "AF-C", "AF-F"};
                    return value >= 0 && value < modes.length ? modes[value] : code(value);
                default:
                    return code(value);
            }
        }

        private static String wb(int value) {
            switch (value) {
                case 0x0002: return "Automático";
                case 0x0004: return "Luz día";
                case 0x0005: return "Fluorescente";
                case 0x0006: return "Tungsteno";
                case 0x0007: return "Flash";
                case 0x8008: return "Nublado";
                case 0x800f: return "Sombra";
                case 0x8014: return "Automático frío";
                case 0x8015: return "Automático cálido";
                default:
                    if (value >= 0x800b && value <= 0x800e) return "Personalizado " + (value - 0x800a);
                    if (value >= 0x8010 && value <= 0x8013) return "Temperatura " + (value - 0x800f);
                    return code(value);
            }
        }
    }

    static final class Choices {
        final int current;
        final int[] values;

        Choices(int current, int[] values) {
            this.current = current;
            this.values = values;
        }

        boolean permits(int value) {
            for (int allowed : values) if (allowed == value) return true;
            return false;
        }
    }

    static final class State {
        final RecordingState recording;
        final boolean recognized;
        final int response;

        State(RecordingState recording, boolean recognized, int response) {
            this.recording = recording;
            this.recognized = recognized;
            this.response = response;
        }
    }

    static State readRecording(PtpConnection ptp) throws IOException {
        PtpConnection.Reply reply = ptp.command(QUERY_STATE, RECORDING_STATUS);
        int response = reply.panasonicCode();
        Integer raw = response == PtpConnection.OK ? scalar(reply.data) : null;
        return new State(decodeRecording(raw), raw != null && raw >= 0 && raw <= 7, response);
    }

    static RecordingState decodeRecording(Integer raw) {
        if (raw == null || raw < 0 || raw > 7) return RecordingState.UNKNOWN;
        if (raw == 0) return RecordingState.READY;
        if (raw == 1) return RecordingState.RECORDING;
        return RecordingState.BUSY;
    }

    static int record(PtpConnection ptp, boolean start) throws IOException {
        return ptp.command(MOVIE, start ? 0x07000011 : 0x07000012).panasonicCode();
    }

    static int autoFocus(PtpConnection ptp) throws IOException {
        return ptp.command(FOCUS, 0x03000024).panasonicCode();
    }

    static int shutter(PtpConnection ptp) throws IOException {
        return ptp.command(STILL, 0x03000011).panasonicCode();
    }

    static Choices choices(PtpConnection ptp, Setting setting) throws IOException {
        PtpConnection.Reply reply = ptp.command(LIST_PROPERTY, setting.descriptor, 0, 0);
        if (reply.panasonicCode() != PtpConnection.OK) return null;
        Choices available = parseChoices(reply.data, setting.bytes);
        if (available == null || setting != Setting.ISO) return available;
        PtpConnection.Reply current = ptp.command(GET_PROPERTY, setting.property);
        Integer value = current.panasonicCode() == PtpConnection.OK ? scalar(current.data) : null;
        return value != null ? new Choices(value, available.values) : available;
    }

    static int change(PtpConnection ptp, Setting setting, int value) throws IOException {
        byte[] data = scalarPayload(setting.property, setting.bytes, value);
        return ptp.commandWithData(SET_PROPERTY, data, setting.property).panasonicCode();
    }

    static Choices parseChoices(byte[] data, int width) {
        if (data == null || data.length < 28 || (width != 2 && width != 4)) return null;
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long offset = Integer.toUnsignedLong(buffer.getInt(4)) * 4 + 8;
        if (offset < 28 || offset + width + 4 > data.length) return null;
        buffer.position((int) offset);
        int current = number(buffer, width);
        long count = Integer.toUnsignedLong(buffer.getInt());
        if (count > 512 || count > buffer.remaining() / width) return null;
        int[] values = new int[(int) count];
        for (int i = 0; i < values.length; i++) values[i] = number(buffer, width);
        return new Choices(current, Arrays.stream(values).distinct().toArray());
    }

    static Integer scalar(byte[] data) {
        if (data == null || data.length < 8) return null;
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.getInt();
        int width = buffer.getInt();
        if ((width != 2 && width != 4) || width > buffer.remaining()) return null;
        return number(buffer, width);
    }

    static byte[] scalarPayload(int property, int width, int value) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + width).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(property).putInt(width);
        if (width == 2) buffer.putShort((short) value);
        else buffer.putInt(value);
        return buffer.array();
    }

    private static int number(ByteBuffer buffer, int width) {
        return width == 2 ? Short.toUnsignedInt(buffer.getShort()) : buffer.getInt();
    }

    static String inventory(PtpConnection ptp) throws IOException {
        PtpConnection.Reply info = ptp.command(0x1001);
        PtpConnection.Reply af = ptp.command(GET_PROPERTY, AF_AREA);
        PtpConnection.Reply style = ptp.command(GET_PROPERTY, PHOTO_STYLE);
        return "DeviceInfo " + response(info) + "\nÁrea AF " + response(af) + dump(af.data)
                + "\nEstilo de foto " + response(style) + dump(style.data);
    }

    private static String response(PtpConnection.Reply reply) {
        byte[] bytes = reply.data;
        if (bytes == null) return PtpConnection.hex(reply.panasonicCode()) + " / sin datos";
        // Only list the length of DeviceInfo, which can contain the body's serial number.
        return PtpConnection.hex(reply.panasonicCode()) + " / " + bytes.length + " bytes";
    }

    private static String dump(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder hex = new StringBuilder(" ·");
        for (int i = 0; i < Math.min(48, data.length); i++) {
            hex.append(String.format(Locale.ROOT, " %02X", data[i] & 255));
        }
        return hex.toString();
    }

    private static String code(int value) {
        return String.format(Locale.ROOT, "Código 0x%04X", value);
    }
}
