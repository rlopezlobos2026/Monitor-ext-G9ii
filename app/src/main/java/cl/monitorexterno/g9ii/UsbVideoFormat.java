package cl.monitorexterno.g9ii;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class UsbVideoFormat {
    static final int LIST = 0x0d800012;
    static final int CURRENT = 0x0d800011;
    final int width;
    final int height;
    final int cameraParameter;
    final int fps;

    UsbVideoFormat(int width, int height, int cameraParameter, int fps) {
        this.width = width;
        this.height = height;
        this.cameraParameter = cameraParameter;
        this.fps = fps;
    }

    static List<UsbVideoFormat> parseList(byte[] payload) {
        List<UsbVideoFormat> modes = new ArrayList<>();
        ByteBuffer buffer = payload(payload, LIST);
        if (buffer == null || buffer.remaining() < 4) return modes;
        int count = unsigned(buffer);
        int entrySize = unsigned(buffer);
        if (entrySize != 8 || count > buffer.remaining() / entrySize) return modes;
        for (int i = 0; i < count; i++) {
            UsbVideoFormat mode = read(buffer);
            if (mode != null && !modes.contains(mode)) modes.add(mode);
        }
        modes.sort((first, second) -> {
            int quality = Integer.compare(second.width * second.height, first.width * first.height);
            return quality != 0 ? quality : Integer.compare(second.fps, first.fps);
        });
        return modes;
    }

    static UsbVideoFormat parseCurrent(byte[] payload) {
        ByteBuffer buffer = payload(payload, CURRENT);
        return buffer != null && buffer.remaining() >= 8 ? read(buffer) : null;
    }

    private static ByteBuffer payload(byte[] bytes, int expected) {
        if (bytes == null || bytes.length < 8) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != expected) return null;
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) return null;
        buffer.limit(8 + length);
        return buffer;
    }

    private static UsbVideoFormat read(ByteBuffer buffer) {
        int height = unsigned(buffer);
        int width = unsigned(buffer);
        int parameter = unsigned(buffer);
        int fps = unsigned(buffer);
        if (width < 16 || height < 16 || width > 8192 || height > 8192) return null;
        return new UsbVideoFormat(width, height, parameter, fps);
    }

    private static int unsigned(ByteBuffer buffer) {
        return Short.toUnsignedInt(buffer.getShort());
    }

    byte[] toPayload() {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(CURRENT).putInt(8).putShort((short) height).putShort((short) width)
                .putShort((short) cameraParameter).putShort((short) fps).array();
    }

    String label() {
        return width + " × " + height + (fps > 0 ? "  ·  " + fps + " fps nominales" : "");
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UsbVideoFormat)) return false;
        UsbVideoFormat that = (UsbVideoFormat) other;
        return width == that.width && height == that.height
                && cameraParameter == that.cameraParameter && fps == that.fps;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, cameraParameter, fps);
    }
}
