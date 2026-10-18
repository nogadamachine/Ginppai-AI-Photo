package com.nogada.imageclean;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.media.ExifInterface;
import android.net.Uri;
import android.test.AndroidTestCase;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

public final class ImageProcessorDeviceTest extends AndroidTestCase {
    public void testDefaultPngKeepsDecodedPixelsAlphaAndSrgb() throws Exception {
        File directory = createTestDirectory("default_png");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = createSrgbBitmap(4, 3);
            int[] pixels = new int[]{
                    Color.argb(0, 0, 0, 0),
                    Color.argb(64, 240, 20, 30),
                    Color.argb(128, 30, 220, 40),
                    Color.argb(255, 40, 50, 230),
                    Color.argb(255, 15, 25, 35),
                    Color.argb(192, 200, 150, 100),
                    Color.argb(96, 90, 130, 170),
                    Color.argb(255, 250, 245, 240),
                    Color.argb(32, 70, 80, 90),
                    Color.argb(224, 110, 120, 130),
                    Color.argb(255, 1, 2, 3),
                    Color.argb(160, 180, 190, 200)
            };
            fixture.setPixels(pixels, 0, 4, 0, 0, 4, 3);
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();
            Bitmap expected = BitmapFactory.decodeFile(source.getAbsolutePath());

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    false
            );

            Bitmap actual = BitmapFactory.decodeFile(result.file.getAbsolutePath());
            assertNotNull(expected);
            assertNotNull(actual);
            assertEquals(expected.getWidth(), result.width);
            assertEquals(expected.getHeight(), result.height);
            assertEquals("image/png", result.mimeType);
            assertEquals("메타데이터 제거 / PNG 저장", result.details);
            assertTrue(result.file.getName().matches("clean_[0-9a-f-]+\\.png"));
            assertNotNull(actual.getColorSpace());
            assertTrue(actual.getColorSpace().isSrgb());
            assertBitmapPixelsEqual(expected, actual);
            expected.recycle();
            actual.recycle();
        } finally {
            deleteTree(directory);
        }
    }

    public void testPngMetadataIsRemovedAndOriginalBytesStayUnchanged() throws Exception {
        File directory = createTestDirectory("png_metadata");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = createSolidBitmap(8, 6, Color.argb(180, 60, 120, 200));
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();
            byte[] encoded = Files.readAllBytes(source.toPath());
            byte[] withMetadata = injectPngMetadata(encoded);
            writeBytes(source, withMetadata);
            byte[] originalBefore = Files.readAllBytes(source.toPath());

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    false
            );

            assertTrue(Arrays.equals(originalBefore, Files.readAllBytes(source.toPath())));
            List<String> chunkTypes = readPngChunkTypes(result.file);
            assertFalse(chunkTypes.contains("tEXt"));
            assertFalse(chunkTypes.contains("iTXt"));
            assertFalse(chunkTypes.contains("eXIf"));
            assertFalse(chunkTypes.contains("iCCP"));
            assertTrue(chunkTypes.contains("IHDR"));
            assertTrue(chunkTypes.contains("IDAT"));
            assertTrue(chunkTypes.contains("IEND"));
        } finally {
            deleteTree(directory);
        }
    }

    public void testJpegOrientationIsAppliedAndExifTagsAreAbsent() throws Exception {
        File directory = createTestDirectory("jpeg_orientation");
        try {
            File source = new File(directory, "source.jpg");
            Bitmap fixture = createSolidBitmap(30, 20, Color.rgb(50, 140, 220));
            writeBitmap(source, fixture, Bitmap.CompressFormat.JPEG, 95);
            fixture.recycle();
            ExifInterface sourceExif = new ExifInterface(source);
            sourceExif.setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    Integer.toString(ExifInterface.ORIENTATION_ROTATE_90)
            );
            sourceExif.setAttribute(ExifInterface.TAG_MAKE, "Private Camera");
            sourceExif.setAttribute(ExifInterface.TAG_USER_COMMENT, "Private note");
            sourceExif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            sourceExif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,30/1,0/1");
            sourceExif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
            sourceExif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "127/1,0/1,0/1");
            sourceExif.saveAttributes();

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    true
            );

            assertEquals(20, result.width);
            assertEquals(30, result.height);
            assertEquals("image/jpeg", result.mimeType);
            ExifInterface cleanedExif = new ExifInterface(result.file);
            assertNull(cleanedExif.getAttribute(ExifInterface.TAG_MAKE));
            assertNull(cleanedExif.getAttribute(ExifInterface.TAG_USER_COMMENT));
            assertNull(cleanedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
            assertNull(cleanedExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE));
        } finally {
            deleteTree(directory);
        }
    }

    public void testJpegOutputCompositesTransparencyOnWhite() throws Exception {
        File directory = createTestDirectory("jpeg_white");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = createSolidBitmap(64, 64, Color.TRANSPARENT);
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    true
            );

            Bitmap actual = BitmapFactory.decodeFile(result.file.getAbsolutePath());
            assertNotNull(actual);
            int corner = actual.getPixel(0, 0);
            assertEquals(255, Color.alpha(corner));
            assertTrue(Color.red(corner) >= 248);
            assertTrue(Color.green(corner) >= 248);
            assertTrue(Color.blue(corner) >= 248);
            assertEquals("메타데이터 제거 / JPG 저장", result.details);
            actual.recycle();
        } finally {
            deleteTree(directory);
        }
    }

    public void testWebpInputProducesPng() throws Exception {
        File directory = createTestDirectory("webp_png");
        try {
            File source = new File(directory, "source.webp");
            Bitmap fixture = createSolidBitmap(19, 13, Color.rgb(35, 145, 215));
            writeBitmap(source, fixture, Bitmap.CompressFormat.WEBP_LOSSLESS, 100);
            fixture.recycle();

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    false
            );

            assertEquals(19, result.width);
            assertEquals(13, result.height);
            assertEquals("image/png", result.mimeType);
            assertTrue(result.file.getName().endsWith(".png"));
            assertNotNull(BitmapFactory.decodeFile(result.file.getAbsolutePath()));
        } finally {
            deleteTree(directory);
        }
    }

    public void testFingerprintKeepsDimensionsAndChangesOpaquePixels() throws Exception {
        File directory = createTestDirectory("fingerprint");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = createSolidBitmap(256, 192, Color.rgb(128, 128, 128));
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();
            Bitmap expected = BitmapFactory.decodeFile(source.getAbsolutePath());

            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    true,
                    false
            );

            Bitmap actual = BitmapFactory.decodeFile(result.file.getAbsolutePath());
            assertNotNull(actual);
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            assertEquals("메타데이터 제거 / 픽셀 변화 적용", result.details);
            int differentPixels = 0;
            for (int rowIndex = 0; rowIndex < actual.getHeight(); rowIndex++) {
                for (int columnIndex = 0; columnIndex < actual.getWidth(); columnIndex++) {
                    int pixel = actual.getPixel(columnIndex, rowIndex);
                    assertEquals(255, Color.alpha(pixel));
                    if (pixel != expected.getPixel(columnIndex, rowIndex)) {
                        differentPixels++;
                    }
                }
            }
            assertTrue(differentPixels > 0);
            expected.recycle();
            actual.recycle();
        } finally {
            deleteTree(directory);
        }
    }

    public void testAnimatedGifAndInvalidBytesAreRejected() throws Exception {
        File directory = createTestDirectory("reject_inputs");
        try {
            File animated = new File(directory, "animated.gif");
            writeBytes(animated, createAnimatedGif());
            try {
                ImageProcessor.process(getContext(), Uri.fromFile(animated), directory, false, false);
                fail("움직이는 GIF가 거부되어야 합니다.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("움직이는"));
            }

            File invalid = new File(directory, "invalid.bin");
            writeBytes(invalid, new byte[]{1, 2, 3, 4, 5, 6, 7});
            try {
                ImageProcessor.process(getContext(), Uri.fromFile(invalid), directory, false, false);
                fail("손상된 입력이 거부되어야 합니다.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("지원하지 않거나 손상된"));
            }
        } finally {
            deleteTree(directory);
        }
    }

    public void testInterruptedProcessingLeavesNoPublishedOutput() throws Exception {
        File directory = createTestDirectory("interrupted");
        try {
            File source = new File(directory, "source.png");
            Bitmap fixture = createSolidBitmap(32, 24, Color.rgb(80, 120, 160));
            writeBitmap(source, fixture, Bitmap.CompressFormat.PNG, 100);
            fixture.recycle();
            Thread.currentThread().interrupt();
            try {
                ImageProcessor.process(getContext(), Uri.fromFile(source), directory, false, false);
                fail("중단된 작업이 결과를 만들면 안 됩니다.");
            } catch (InterruptedIOException expected) {
                assertTrue(expected.getMessage().contains("중단"));
            } finally {
                Thread.interrupted();
            }
            File[] files = directory.listFiles();
            assertNotNull(files);
            for (File file : files) {
                assertFalse(file.getName().startsWith("clean_"));
                assertFalse(file.getName().endsWith(".part"));
            }
        } finally {
            Thread.interrupted();
            deleteTree(directory);
        }
    }

    public void testHeicAssetProducesPng() throws Exception {
        assertAssetProducesPng("test-image.heic");
    }

    public void testAvifAssetProducesPng() throws Exception {
        assertAssetProducesPng("test-image.avif");
    }

    private void assertAssetProducesPng(String assetName) throws Exception {
        File directory = createTestDirectory("asset_image");
        try {
            File source = new File(directory, assetName);
            try (InputStream input = getContext().getAssets().open(assetName);
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(source))) {
                copy(input, output);
            }
            ImageProcessor.Result result = ImageProcessor.process(
                    getContext(),
                    Uri.fromFile(source),
                    directory,
                    false,
                    false
            );
            assertEquals(64, result.width);
            assertEquals(48, result.height);
            assertEquals("image/png", result.mimeType);
            assertNotNull(BitmapFactory.decodeFile(result.file.getAbsolutePath()));
        } finally {
            deleteTree(directory);
        }
    }

    private File createTestDirectory(String name) {
        File directory = new File(getContext().getCacheDir(), name + "_" + System.nanoTime());
        assertTrue(directory.mkdirs());
        return directory;
    }

    private static Bitmap createSrgbBitmap(int width, int height) {
        return Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888,
                true,
                ColorSpace.get(ColorSpace.Named.SRGB)
        );
    }

    private static Bitmap createSolidBitmap(int width, int height, int color) {
        Bitmap bitmap = createSrgbBitmap(width, height);
        bitmap.eraseColor(color);
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

    private static byte[] injectPngMetadata(byte[] source) throws IOException {
        int firstChunkEnd = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(source, 0, firstChunkEnd);
        writePngChunk(output, "tEXt", "Author\0Private".getBytes(StandardCharsets.ISO_8859_1));
        writePngChunk(
                output,
                "iTXt",
                "Comment\0\0\0\0\0Private location".getBytes(StandardCharsets.UTF_8)
        );
        writePngChunk(
                output,
                "eXIf",
                new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0}
        );
        output.write(source, firstChunkEnd, source.length - firstChunkEnd);
        return output.toByteArray();
    }

    private static void writePngChunk(ByteArrayOutputStream output, String type, byte[] data) throws IOException {
        DataOutputStream stream = new DataOutputStream(output);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        stream.writeInt(data.length);
        stream.write(typeBytes);
        stream.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        stream.writeInt((int) crc.getValue());
    }

    private static List<String> readPngChunkTypes(File source) throws IOException {
        List<String> types = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(source)))) {
            byte[] signature = new byte[8];
            input.readFully(signature);
            while (true) {
                long length = Integer.toUnsignedLong(input.readInt());
                byte[] type = new byte[4];
                input.readFully(type);
                String typeName = new String(type, StandardCharsets.US_ASCII);
                types.add(typeName);
                skipFully(input, length + 4L);
                if ("IEND".equals(typeName)) {
                    return types;
                }
            }
        }
    }

    private static byte[] createAnimatedGif() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
        output.write(new byte[]{1, 0, 1, 0, (byte) 0x80, 0, 0});
        output.write(new byte[]{0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        writeGifFrame(output, (byte) 0x44, (byte) 0x01);
        writeGifFrame(output, (byte) 0x4c, (byte) 0x01);
        output.write(0x3b);
        return output.toByteArray();
    }

    private static void writeGifFrame(ByteArrayOutputStream output, byte firstCode, byte secondCode) throws IOException {
        output.write(new byte[]{0x21, (byte) 0xf9, 0x04, 0, 0, 0, 0, 0});
        output.write(new byte[]{0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0});
        output.write(new byte[]{2, 2, firstCode, secondCode, 0});
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

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static void skipFully(InputStream input, long byteCount) throws IOException {
        long remaining = byteCount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() >= 0) {
                remaining--;
            } else {
                throw new IOException("PNG 내부 데이터가 중간에 끝났습니다.");
            }
        }
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
            output.write(bytes);
        }
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
