package cl.monitorexterno.g9ii;

import android.graphics.Bitmap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class CubeLut {
    final int size;
    final int tiles;
    final boolean oneDimensional;
    final float[] low;
    final float[] high;
    final Bitmap bitmap;

    private CubeLut(int size, int tiles, boolean oneDimensional, float[] low, float[] high, Bitmap bitmap) {
        this.size = size;
        this.tiles = tiles;
        this.oneDimensional = oneDimensional;
        this.low = low;
        this.high = high;
        this.bitmap = bitmap;
    }

    static CubeLut read(InputStream source) throws IOException {
        int size = 0;
        boolean oneDimensional = false;
        int tiles = 0;
        int width = 0;
        int height = 0;
        int entries = 0;
        int expected = 0;
        int[] pixels = null;
        float[] low = {0, 0, 0};
        float[] high = {1, 1, 1};
        long characters = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                characters += line.length() + 1L;
                if (characters > 24 * 1024 * 1024) throw new IOException("La LUT supera 24 MB.");
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.replace("\uFEFF", "").trim();
                if (line.isEmpty() || line.startsWith("TITLE ")) continue;
                String[] fields = line.split("\\s+");
                switch (fields[0]) {
                    case "LUT_1D_SIZE":
                    case "LUT_3D_SIZE":
                        if (size != 0 || fields.length != 2) throw new IOException("Se admite una sola LUT por archivo.");
                        oneDimensional = fields[0].equals("LUT_1D_SIZE");
                        size = Integer.parseInt(fields[1]);
                        if (size < 2 || size > (oneDimensional ? 4096 : 65)) {
                            throw new IOException("Tamaño de LUT fuera del rango admitido.");
                        }
                        tiles = oneDimensional ? 1 : (int) Math.ceil(Math.sqrt(size));
                        width = size * tiles;
                        height = oneDimensional ? 1 : size * ((size + tiles - 1) / tiles);
                        expected = oneDimensional ? size : size * size * size;
                        pixels = new int[width * height];
                        java.util.Arrays.fill(pixels, 0xff000000);
                        break;
                    case "DOMAIN_MIN":
                        low = triplet(fields, 1);
                        break;
                    case "DOMAIN_MAX":
                        high = triplet(fields, 1);
                        break;
                    default:
                        if (pixels == null || entries >= expected) throw new IOException("Cantidad de muestras LUT incorrecta.");
                        float[] rgb = triplet(fields, 0);
                        int red = entries % size;
                        int green = oneDimensional ? 0 : entries / size % size;
                        int blue = oneDimensional ? 0 : entries / (size * size);
                        int x = blue % tiles * size + red;
                        int y = blue / tiles * size + green;
                        pixels[y * width + x] = 0xff000000 | channel(rgb[0]) << 16
                                | channel(rgb[1]) << 8 | channel(rgb[2]);
                        entries++;
                }
            }
        } catch (NumberFormatException error) {
            throw new IOException("El archivo .cube contiene valores inválidos.", error);
        }
        if (size == 0 || entries != expected) throw new IOException("El archivo .cube está incompleto.");
        for (int i = 0; i < 3; i++) {
            if (low[i] >= high[i]) throw new IOException("El dominio de la LUT es inválido.");
        }
        return new CubeLut(size, tiles, oneDimensional, low, high,
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888));
    }

    private static float[] triplet(String[] fields, int offset) throws IOException {
        if (fields.length != offset + 3) throw new IOException("Una fila de la LUT no tiene tres canales.");
        float[] values = new float[3];
        for (int i = 0; i < 3; i++) {
            values[i] = Float.parseFloat(fields[offset + i]);
            if (!Float.isFinite(values[i])) throw new IOException("La LUT contiene un valor no finito.");
        }
        return values;
    }

    private static int channel(float value) {
        return Math.round(Math.max(0, Math.min(1, value)) * 255);
    }
}
