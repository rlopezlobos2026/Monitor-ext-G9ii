package cl.monitorexterno.g9ii;

import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class LensTelemetry {
    private LensTelemetry() {}

    static double ptpFocalLength(byte[] payload) {
        if (payload == null || payload.length != 4) return 0;
        long value = Integer.toUnsignedLong(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt());
        return validated(value / 100.0);
    }

    static double jpegFocalLength(byte[] bytes, int offset) {
        if (!hasExif(bytes, offset)) return 0;
        try (ByteArrayInputStream frame = new ByteArrayInputStream(bytes, offset, bytes.length - offset)) {
            ExifInterface exif = new ExifInterface(frame);
            return validated(exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0));
        } catch (IOException | RuntimeException unsupported) {
            return 0;
        }
    }

    private static boolean hasExif(byte[] bytes, int offset) {
        int cursor = offset + 2;
        while (cursor + 4 <= bytes.length && (bytes[cursor] & 255) == 255) {
            int marker = bytes[cursor + 1] & 255;
            if (marker == 255) {
                cursor++;
                continue;
            }
            if (marker == 0xda || marker == 0xd9) return false;
            int length = ((bytes[cursor + 2] & 255) << 8) | (bytes[cursor + 3] & 255);
            if (length < 2 || length > bytes.length - cursor - 2) return false;
            if (marker == 0xe1 && length >= 8
                    && bytes[cursor + 4] == 'E' && bytes[cursor + 5] == 'x'
                    && bytes[cursor + 6] == 'i' && bytes[cursor + 7] == 'f'
                    && bytes[cursor + 8] == 0 && bytes[cursor + 9] == 0) return true;
            cursor += 2 + length;
        }
        return false;
    }

    private static double validated(double mm) {
        return Double.isFinite(mm) && mm >= 1 && mm <= 2000 ? mm : 0;
    }
}
