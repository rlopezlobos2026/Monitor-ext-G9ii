package cl.monitorexterno.g9ii;

import android.view.Surface;

final class PhoneLevel {
    private PhoneLevel() {}

    static float roll(float x, float y, int rotation) {
        // A phone held flat has too little in-plane gravity for a reliable roll.
        if (x * x + y * y < 4f) return Float.NaN;
        double angle = Math.toDegrees(Math.atan2(x, y));
        if (rotation == Surface.ROTATION_90) angle -= 90;
        else if (rotation == Surface.ROTATION_270) angle += 90;
        else if (rotation == Surface.ROTATION_180) angle += 180;
        // Fold to the nearest horizontal, regardless of which way the rig is held.
        return (float) Math.IEEEremainder(angle, 180);
    }
}
