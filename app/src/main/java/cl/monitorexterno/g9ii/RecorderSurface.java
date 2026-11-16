package cl.monitorexterno.g9ii;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class RecorderSurface implements AutoCloseable {
    private static final int ANDROID_RECORDABLE = 0x3142;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private final int outputWidth;
    private final int outputHeight;
    private int textureWidth;
    private int textureHeight;
    private final FloatBuffer vertices = floats(-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0);

    RecorderSurface(Surface input, int width, int height) {
        outputWidth = width;
        outputHeight = height;
        try {
            initialize(input);
        } catch (RuntimeException problem) {
            close();
            throw problem;
        }
    }

    private void initialize(Surface input) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        require(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, new int[2], 0, new int[2], 0));
        EGLConfig[] configurations = new EGLConfig[1];
        int[] found = new int[1];
        int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, ANDROID_RECORDABLE, 1,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE};
        require(EGL14.eglChooseConfig(display, attributes, 0, configurations, 0, 1, found, 0) && found[0] != 0);
        context = EGL14.eglCreateContext(display, configurations[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        require(context != EGL14.EGL_NO_CONTEXT);
        surface = EGL14.eglCreateWindowSurface(display, configurations[0], input, new int[]{EGL14.EGL_NONE}, 0);
        require(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context));

        int program = GLES20.glCreateProgram();
        int vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 xy; attribute vec2 uv; varying vec2 point; "
                + "void main() {gl_Position = vec4(xy, 0.0, 1.0); point = uv;}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; varying vec2 point; uniform sampler2D picture; "
                + "void main() {gl_FragColor = texture2D(picture, point);}");
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        GLES20.glUseProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "picture"), 0);
        attribute(program, "xy", 0);
        attribute(program, "uv", 2);
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glClearColor(0, 0, 0, 1);
    }

    private void attribute(int program, String name, int offset) {
        int location = GLES20.glGetAttribLocation(program, name);
        vertices.position(offset);
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 16, vertices);
        GLES20.glEnableVertexAttribArray(location);
    }

    private static int shader(int type, String text) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, text);
        GLES20.glCompileShader(id);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(id));
        return id;
    }

    void frame(Bitmap bitmap, long timestamp) {
        int target = GLES20.GL_TEXTURE_2D;
        if (textureWidth != bitmap.getWidth() || textureHeight != bitmap.getHeight()) {
            textureWidth = bitmap.getWidth();
            textureHeight = bitmap.getHeight();
            GLUtils.texImage2D(target, 0, bitmap, 0);
        } else {
            GLUtils.texSubImage2D(target, 0, 0, 0, bitmap);
        }
        float scale = Math.min(outputWidth / (float) textureWidth, outputHeight / (float) textureHeight);
        int width = Math.round(textureWidth * scale);
        int height = Math.round(textureHeight * scale);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport((outputWidth - width) / 2, (outputHeight - height) / 2, width, height);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        require(EGLExt.eglPresentationTimeANDROID(display, surface, timestamp)
                && EGL14.eglSwapBuffers(display, surface));
    }

    @Override
    public void close() {
        if (display == EGL14.EGL_NO_DISPLAY) return;
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
        display = EGL14.EGL_NO_DISPLAY;
    }

    private static void require(boolean success) {
        if (!success) throw new IllegalStateException("Error del renderizador de grabación (EGL "
                + Integer.toHexString(EGL14.eglGetError()) + ").");
    }

    private static FloatBuffer floats(float... values) {
        FloatBuffer result = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        result.put(values).position(0);
        return result;
    }
}
