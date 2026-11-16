package cl.monitorexterno.g9ii;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;

enum PhoneVideoQuality {
    USB("Según la señal USB", 0, 0, 0),
    FULL_HD("Full HD · 1920 × 1080", 1920, 1080, 12_000_000),
    UHD("4K UHD · 3840 × 2160", 3840, 2160, 35_000_000);

    static final int FRAME_RATE = 30;
    final String label;
    final int width;
    final int height;
    final int bitrate;

    PhoneVideoQuality(String label, int width, int height, int bitrate) {
        this.label = label;
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
    }

    static final class Profile {
        final int width;
        final int height;
        final int codec;
        final int bitrate;

        Profile(int width, int height, int codec, int bitrate) {
            this.width = width;
            this.height = height;
            this.codec = codec;
            this.bitrate = bitrate;
        }

        String dimensions() { return width + " × " + height; }
    }

    Profile profile(int sourceWidth, int sourceHeight) {
        if (this != USB) return hardwareProfile(width, height, bitrate);
        float factor = Math.min(1, Math.min(3840f / sourceWidth, 2160f / sourceHeight));
        int w = even(sourceWidth * factor);
        int h = even(sourceHeight * factor);
        if (w <= 1920 && h <= 1080) {
            return new Profile(w, h, MediaRecorder.VideoEncoder.H264, Math.max(1_500_000, w * h * 6));
        }
        Profile large = hardwareProfile(w, h, Math.min(35_000_000, w * h * 6));
        if (large != null) return large;
        float fallback = Math.min(1920f / sourceWidth, 1080f / sourceHeight);
        w = even(sourceWidth * fallback);
        h = even(sourceHeight * fallback);
        return new Profile(w, h, MediaRecorder.VideoEncoder.H264, Math.max(1_500_000, w * h * 6));
    }

    private static int even(float value) { return Math.max(2, Math.round(value / 2) * 2); }

    private static Profile hardwareProfile(int width, int height, int bitrate) {
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        String[] formats = {MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC};
        for (String format : formats) {
            for (MediaCodecInfo codec : codecs) {
                if (!codec.isEncoder() || !codec.isHardwareAccelerated()) continue;
                Profile profile = supported(codec, format, width, height, bitrate);
                if (profile != null) return profile;
            }
        }
        return null;
    }

    private static Profile supported(MediaCodecInfo codec, String format, int width, int height, int bitrate) {
        MediaCodecInfo.CodecCapabilities capabilities;
        try {
            capabilities = codec.getCapabilitiesForType(format);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
        MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
        if (video == null || !video.areSizeAndRateSupported(width, height, FRAME_RATE)) return null;
        for (int color : capabilities.colorFormats) {
            if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                int encoder = format.equals(MediaFormat.MIMETYPE_VIDEO_AVC)
                        ? MediaRecorder.VideoEncoder.H264 : MediaRecorder.VideoEncoder.HEVC;
                return new Profile(width, height, encoder, video.getBitrateRange().clamp(bitrate));
            }
        }
        return null;
    }
}
