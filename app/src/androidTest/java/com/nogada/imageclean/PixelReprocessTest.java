package com.nogada.imageclean;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.net.Uri;
import android.test.AndroidTestCase;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;

public final class PixelReprocessTest extends AndroidTestCase {
    public void testDisabledReprocessKeepsExistingPngPixels() throws Exception {
        File directory = createTestDirectory("disabled");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = createPatternBitmap(18, 14, false);
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();
            Bitmap expected = BitmapFactory.decodeFile(source.getAbsolutePath());

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    false,
                    false
            );

            Bitmap actual = BitmapFactory.decodeFile(result.file.getAbsolutePath());
            assertNotNull(expected);
            assertNotNull(actual);
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            assertBitmapPixelsEqual(expected, actual);
            assertFalse(result.details.contains("실험적 픽셀 재처리"));
            expected.recycle();
            actual.recycle();
        } finally {
            deleteTree(directory);
        }
    }

    public void testEnabledPngKeepsSizeAndAlphaChangesPixelsAndPreservesSource() throws Exception {
        File directory = createTestDirectory("enabled_png");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = createPatternBitmap(96, 64, true);
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();
            byte[] originalBytes = Files.readAllBytes(source.toPath());
            Bitmap expected = BitmapFactory.decodeFile(source.getAbsolutePath());

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    false,
                    true
            );

            Bitmap actual = BitmapFactory.decodeFile(result.file.getAbsolutePath());
            assertNotNull(expected);
            assertNotNull(actual);
            assertEquals(expected.getWidth(), result.width);
            assertEquals(expected.getHeight(), result.height);
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            assertTrue(Arrays.equals(originalBytes, Files.readAllBytes(source.toPath())));
            assertTrue(countDifferentPixels(expected, actual) > 0);
            for (int rowIndex = 0; rowIndex < actual.getHeight(); rowIndex++) {
                for (int columnIndex = 0; columnIndex < actual.getWidth(); columnIndex++) {
                    assertEquals(128, Color.alpha(actual.getPixel(columnIndex, rowIndex)));
                }
            }
            assertTrue(result.details.contains("실험적 픽셀 재처리"));
            assertTrue(result.details.contains("검출 미검증"));
            expected.recycle();
            actual.recycle();
        } finally {
            deleteTree(directory);
        }
    }

    public void testEnabledJpegSucceedsAndProducesOpaquePixels() throws Exception {
        File directory = createTestDirectory("enabled_jpeg");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = createPatternBitmap(72, 48, true);
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    true,
                    true
            );

            Bitmap actual = BitmapFactory.decodeFile(result.file.getAbsolutePath());
            assertNotNull(actual);
            assertEquals("image/jpeg", result.mimeType);
            assertEquals(72, actual.getWidth());
            assertEquals(48, actual.getHeight());
            for (int rowIndex = 0; rowIndex < actual.getHeight(); rowIndex++) {
                for (int columnIndex = 0; columnIndex < actual.getWidth(); columnIndex++) {
                    assertEquals(255, Color.alpha(actual.getPixel(columnIndex, rowIndex)));
                }
            }
            assertTrue(result.details.contains("실험적 픽셀 재처리"));
            assertTrue(result.details.contains("검출 미검증"));
            actual.recycle();
        } finally {
            deleteTree(directory);
        }
    }

    public void testEnabledReprocessHandlesOnePixelImage() throws Exception {
        File directory = createTestDirectory("one_pixel");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = Bitmap.createBitmap(
                    1,
                    1,
                    Bitmap.Config.ARGB_8888,
                    true,
                    ColorSpace.get(ColorSpace.Named.SRGB)
            );
            fixture.eraseColor(Color.argb(128, 91, 137, 183));
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    false,
                    true
            );

            Bitmap actual = BitmapFactory.decodeFile(result.file.getAbsolutePath());
            assertNotNull(actual);
            assertEquals(1, result.width);
            assertEquals(1, result.height);
            assertEquals(1, actual.getWidth());
            assertEquals(1, actual.getHeight());
            assertEquals(128, Color.alpha(actual.getPixel(0, 0)));
            assertTrue(result.details.contains("실험적 픽셀 재처리"));
            assertTrue(result.details.contains("검출 미검증"));
            actual.recycle();
        } finally {
            deleteTree(directory);
        }
    }

    private File createTestDirectory(String name) {
        File directory = new File(getContext().getCacheDir(), "pixel_reprocess_" + name + "_" + System.nanoTime());
        assertTrue(directory.mkdirs());
        return directory;
    }

    private static Bitmap createPatternBitmap(int width, int height, boolean translucent) {
        Bitmap bitmap = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888,
                true,
                ColorSpace.get(ColorSpace.Named.SRGB)
        );
        int alpha = translucent ? 128 : 255;
        int[] row = new int[width];
        for (int rowIndex = 0; rowIndex < height; rowIndex++) {
            for (int columnIndex = 0; columnIndex < width; columnIndex++) {
                int red = 43 + columnIndex * 5 + rowIndex * 3;
                int green = 79 + columnIndex * 2 + rowIndex * 7;
                int blue = 121 + columnIndex * 7 + rowIndex * 2;
                row[columnIndex] = Color.argb(alpha, red & 0xff, green & 0xff, blue & 0xff);
            }
            bitmap.setPixels(row, 0, width, 0, rowIndex, width, 1);
        }
        return bitmap;
    }

    private static void writeBitmap(
            File output,
            Bitmap bitmap,
            Bitmap.CompressFormat format,
            int quality
    ) throws IOException {
        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(output))) {
            assertTrue(bitmap.compress(format, quality, stream));
        }
    }

    private static void assertBitmapPixelsEqual(Bitmap expected, Bitmap actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (int rowIndex = 0; rowIndex < expected.getHeight(); rowIndex++) {
            for (int columnIndex = 0; columnIndex < expected.getWidth(); columnIndex++) {
                assertEquals(
                        expected.getPixel(columnIndex, rowIndex),
                        actual.getPixel(columnIndex, rowIndex)
                );
            }
        }
    }

    private static int countDifferentPixels(Bitmap expected, Bitmap actual) {
        int different = 0;
        for (int rowIndex = 0; rowIndex < expected.getHeight(); rowIndex++) {
            for (int columnIndex = 0; columnIndex < expected.getWidth(); columnIndex++) {
                if (expected.getPixel(columnIndex, rowIndex) != actual.getPixel(columnIndex, rowIndex)) {
                    different++;
                }
            }
        }
        return different;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
