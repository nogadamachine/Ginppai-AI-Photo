package com.nogada.imageclean;

import android.test.AndroidTestCase;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;

public final class MetadataStripperEngineTest extends AndroidTestCase {
    public void testPngRemovesPrivacyChunksAndKeepsRenderingChunks() throws Exception {
        File directory = new File(getContext().getCacheDir(), "stripper_png_" + System.nanoTime());
        assertTrue(directory.mkdirs());
        File input = new File(directory, "input.png");
        File output = new File(directory, "output.png");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            writePngChunk(bytes, "IHDR", new byte[]{0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0});
            writePngChunk(bytes, "sRGB", new byte[]{0});
            writePngChunk(bytes, "tEXt", "Author\0Private".getBytes(StandardCharsets.ISO_8859_1));
            writePngChunk(bytes, "eXIf", new byte[]{1, 2, 3, 4});
            writePngChunk(bytes, "iCCP", new byte[]{5, 6, 7, 8});
            writePngChunk(bytes, "IDAT", new byte[]{1, 2, 3});
            writePngChunk(bytes, "IEND", new byte[0]);
            writeFile(input, bytes.toByteArray());

            MetadataStripper.strip(input, output, false);

            String binary = new String(Files.readAllBytes(output.toPath()), StandardCharsets.ISO_8859_1);
            assertTrue(binary.contains("sRGB"));
            assertFalse(binary.contains("tEXt"));
            assertFalse(binary.contains("eXIf"));
            assertFalse(binary.contains("iCCP"));
        } finally {
            deleteTree(directory);
        }
    }

    public void testPngRejectsInvalidChunkChecksum() throws Exception {
        File directory = new File(getContext().getCacheDir(), "stripper_crc_" + System.nanoTime());
        assertTrue(directory.mkdirs());
        File input = new File(directory, "input.png");
        File output = new File(directory, "output.png");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            writePngChunk(bytes, "IHDR", new byte[]{0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0});
            writePngChunk(bytes, "IDAT", new byte[]{1});
            writePngChunk(bytes, "IEND", new byte[0]);
            byte[] png = bytes.toByteArray();
            png[png.length - 1] ^= 1;
            writeFile(input, png);

            try {
                MetadataStripper.strip(input, output, false);
                fail("Expected invalid checksum to be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("체크섬"));
            }
        } finally {
            deleteTree(directory);
        }
    }

    public void testJpegRemovesAppMetadataAndKeepsJfif() throws Exception {
        File directory = new File(getContext().getCacheDir(), "stripper_jpeg_" + System.nanoTime());
        assertTrue(directory.mkdirs());
        File input = new File(directory, "input.jpg");
        File output = new File(directory, "output.jpg");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(0xff);
            bytes.write(0xd8);
            writeJpegSegment(bytes, 0xe0, new byte[]{'J', 'F', 'I', 'F', 0, 1, 2});
            writeJpegSegment(bytes, 0xe1, "Exif\0\0secret".getBytes(StandardCharsets.ISO_8859_1));
            writeJpegSegment(bytes, 0xe2, "ICC_PROFILE\0secret".getBytes(StandardCharsets.ISO_8859_1));
            writeJpegSegment(bytes, 0xda, new byte[]{1, 1, 0, 0, 63, 0});
            bytes.write(new byte[]{10, 20, 30, (byte) 0xff, 0, 40});
            bytes.write(0xff);
            bytes.write(0xd9);
            writeFile(input, bytes.toByteArray());

            MetadataStripper.strip(input, output, true);

            byte[] cleaned = Files.readAllBytes(output.toPath());
            String binary = new String(cleaned, StandardCharsets.ISO_8859_1);
            assertTrue(binary.contains("JFIF"));
            assertFalse(binary.contains("Exif"));
            assertFalse(binary.contains("ICC_PROFILE"));
            assertEquals(0xff, cleaned[cleaned.length - 2] & 0xff);
            assertEquals(0xd9, cleaned[cleaned.length - 1] & 0xff);
        } finally {
            deleteTree(directory);
        }
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

    private static void writeJpegSegment(ByteArrayOutputStream output, int marker, byte[] payload) throws IOException {
        output.write(0xff);
        output.write(marker);
        int length = payload.length + 2;
        output.write((length >>> 8) & 0xff);
        output.write(length & 0xff);
        output.write(payload);
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
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
