package com.nogada.imageclean;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import java.io.InterruptedIOException;

final class PixelReprocessor {
    private PixelReprocessor() { }

    static void apply(Bitmap bitmap) throws InterruptedIOException {
        checkInterrupted();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Bitmap reduced = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(width * 0.75f)), Math.max(1, Math.round(height * 0.75f)), true);
        if (reduced != bitmap) {
            try {
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
                new Canvas(bitmap).drawBitmap(reduced, null, new Rect(0, 0, width, height), paint);
            } finally {
                reduced.recycle();
            }
        }
        int[] row = new int[width];
        for (int rowIndex = 0; rowIndex < height; rowIndex++) {
            checkInterrupted();
            bitmap.getPixels(row, 0, width, 0, rowIndex, width, 1);
            for (int column = 0; column < width; column++) {
                int pixel = row[column];
                int alpha = pixel >>> 24;
                if (alpha == 0) continue;
                int red = pixel >> 16 & 255;
                int green = pixel >> 8 & 255;
                int blue = pixel & 255;
                int luminance = channel(0.299f * red + 0.587f * green + 0.114f * blue);
                int chromaBlue = quantize(channel((blue - luminance) * 0.492f + 128));
                int chromaRed = quantize(channel((red - luminance) * 0.877f + 128));
                int cleanedRed = channel(luminance + 1.140f * (chromaRed - 128));
                int cleanedGreen = channel(luminance - 0.395f * (chromaBlue - 128) - 0.581f * (chromaRed - 128));
                int cleanedBlue = channel(luminance + 2.032f * (chromaBlue - 128));
                row[column] = alpha << 24 | cleanedRed << 16 | cleanedGreen << 8 | cleanedBlue;
            }
            bitmap.setPixels(row, 0, width, 0, rowIndex, width, 1);
        }
    }

    private static int quantize(int value) { return Math.min(255, Math.round(value / 32f) * 32); }
    private static int channel(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("픽셀 재처리가 중단되었습니다.");
    }
}
