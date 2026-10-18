package com.nogada.imageclean;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;

public final class ImageProcessor {
    private static final long MAX_INPUT_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_PIXELS = 80_000_000L;
    private static final int MAX_DIMENSION = 32_768;
    private static final long MAX_WORKING_BYTES = 384L * 1024L * 1024L;
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private ImageProcessor() {
    }

    public static final class Result {
        public final File file;
        public final String mimeType;
        public final int width;
        public final int height;
        public final long originalBytes;
        public final long cleanedBytes;
        public final String details;

        private Result(
                File file,
                String mimeType,
                int width,
                int height,
                long originalBytes,
                long cleanedBytes,
                String details
        ) {
            this.file = file;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.originalBytes = originalBytes;
            this.cleanedBytes = cleanedBytes;
            this.details = details;
        }
    }

    public static Result process(
            Context context, Uri input, File outputDirectory, boolean jpegOutput
    ) throws IOException {
        if (context == null || input == null || outputDirectory == null) {
            throw new IllegalArgumentException("컨텍스트, 입력 URI, 결과 폴더가 필요합니다.");
        }
        ensureOutputDirectory(outputDirectory);

        File cachedInput = null;
        File encodedOutput = null;
        File stagedOutput = null;
        File finalOutput = null;
        Bitmap decoded = null;
        Bitmap cleaned = null;
        boolean completed = false;
        long originalBytes = 0;
        try {
            cachedInput = File.createTempFile("imageclean_input_", ".tmp", context.getCacheDir());
            originalBytes = copyInput(context, input, cachedInput);
            rejectKnownAnimatedContainer(cachedInput);

            decoded = decode(cachedInput);
            int sourceWidth = decoded.getWidth();
            int sourceHeight = decoded.getHeight();
            int outputWidth = sourceWidth;
            int outputHeight = sourceHeight;
            cleaned = createFreshBitmap(decoded, outputWidth, outputHeight, jpegOutput);
            decoded.recycle();
            decoded = null;

            encodedOutput = File.createTempFile("imageclean_encoded_", jpegOutput ? ".jpg" : ".png", context.getCacheDir());
            throwIfInterrupted();
            encode(cleaned, encodedOutput, jpegOutput);
            throwIfInterrupted();
            cleaned.recycle();
            cleaned = null;

            String extension = jpegOutput ? ".jpg" : ".png";
            finalOutput = createUniqueDestination(outputDirectory, extension);
            stagedOutput = new File(outputDirectory, finalOutput.getName() + ".part");
            if (stagedOutput.exists() && !stagedOutput.delete()) {
                throw new IOException("결과 파일을 준비할 수 없습니다.");
            }
            MetadataStripper.strip(encodedOutput, stagedOutput, jpegOutput);
            validateDecodedOutput(stagedOutput, outputWidth, outputHeight);
            throwIfInterrupted();
            if (!stagedOutput.renameTo(finalOutput)) {
                throw new IOException("정리된 이미지를 저장할 수 없습니다.");
            }

            completed = true;
            String details = "메타데이터 제거 / " + (jpegOutput ? "JPG 저장" : "PNG 저장");
            return new Result(
                    finalOutput,
                    jpegOutput ? "image/jpeg" : "image/png",
                    outputWidth,
                    outputHeight,
                    originalBytes,
                    finalOutput.length(),
                    details
            );
        } catch (ImageDecoder.DecodeException exception) {
            throw new IOException("지원하지 않거나 손상된 이미지입니다.", exception);
        } catch (HeaderRejectedException exception) {
            throw new IOException(exception.getMessage(), exception);
        } catch (OutOfMemoryError error) {
            throw new IOException("이 기기에서 안전하게 처리하기에는 이미지가 너무 큽니다.", error);
        } catch (SecurityException exception) {
            throw new IOException("선택한 이미지를 읽을 권한이 없습니다.", exception);
        } finally {
            if (decoded != null && !decoded.isRecycled()) {
                decoded.recycle();
            }
            if (cleaned != null && !cleaned.isRecycled()) {
                cleaned.recycle();
            }
            deleteQuietly(cachedInput);
            deleteQuietly(encodedOutput);
            if (!completed) {
                deleteQuietly(stagedOutput);
                deleteQuietly(finalOutput);
            }
        }
    }

    private static Bitmap decode(File input) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(input);
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignoredSource) -> {
            validateHeader(info);
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_DEFAULT);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
    }

    private static void validateHeader(ImageDecoder.ImageInfo info) {
        if (info.isAnimated()) {
            throw new HeaderRejectedException("움직이는 이미지는 지원하지 않습니다.");
        }
        int width = info.getSize().getWidth();
        int height = info.getSize().getHeight();
        if (width <= 0 || height <= 0) {
            throw new HeaderRejectedException("이미지 크기 정보가 올바르지 않습니다.");
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new HeaderRejectedException("한 변이 32768 픽셀을 넘는 이미지는 처리할 수 없습니다.");
        }
        long pixels = (long) width * (long) height;
        if (pixels > MAX_PIXELS) {
            throw new HeaderRejectedException("8000만 픽셀을 넘는 이미지는 처리할 수 없습니다.");
        }
        long estimatedWorkingBytes = pixels * 10L + (long) width * 4L;
        long heapBudget = Runtime.getRuntime().maxMemory() / 2L;
        long allowedWorkingBytes = Math.min(MAX_WORKING_BYTES, heapBudget);
        if (estimatedWorkingBytes > allowedWorkingBytes) {
            throw new HeaderRejectedException("이 기기의 안전한 메모리 한도를 넘는 이미지입니다.");
        }
    }

    private static Bitmap createFreshBitmap(
            Bitmap decoded,
            int outputWidth,
            int outputHeight,
            boolean jpegOutput
    ) {
        Bitmap output = Bitmap.createBitmap(
                outputWidth,
                outputHeight,
                Bitmap.Config.ARGB_8888,
                true,
                ColorSpace.get(ColorSpace.Named.SRGB)
        );
        Canvas canvas = new Canvas(output);
        if (jpegOutput) {
            canvas.drawColor(Color.WHITE);
        } else {
            canvas.drawColor(Color.TRANSPARENT);
        }
        Rect source = new Rect(0, 0, decoded.getWidth(), decoded.getHeight());
        Rect destination = new Rect(0, 0, outputWidth, outputHeight);
        canvas.drawBitmap(decoded, source, destination, null);
        output.setHasAlpha(!jpegOutput && decoded.hasAlpha());
        return output;
    }

    private static void encode(Bitmap bitmap, File output, boolean jpeg) throws IOException {
        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(output))) {
            Bitmap.CompressFormat format = jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
            int quality = jpeg ? 95 : 100;
            if (!bitmap.compress(format, quality, stream)) {
                throw new IOException("이미지를 다시 저장하지 못했습니다.");
            }
        }
        if (output.length() == 0) {
            throw new IOException("저장된 이미지가 비어 있습니다.");
        }
    }

    private static void validateDecodedOutput(File output, int expectedWidth, int expectedHeight) throws IOException {
        Bitmap verified = null;
        try {
            verified = ImageDecoder.decodeBitmap(ImageDecoder.createSource(output), (decoder, info, source) -> {
                if (info.isAnimated()) {
                    throw new HeaderRejectedException("정리된 결과에 예상하지 못한 애니메이션이 있습니다.");
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            });
            if (verified.getWidth() != expectedWidth || verified.getHeight() != expectedHeight) {
                throw new IOException("정리된 결과의 이미지 크기가 일치하지 않습니다.");
            }
        } finally {
            if (verified != null && !verified.isRecycled()) {
                verified.recycle();
            }
        }
    }

    private static long copyInput(Context context, Uri input, File destination) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream rawInput = context.getContentResolver().openInputStream(input)) {
            if (rawInput == null) {
                throw new IOException("선택한 이미지를 열 수 없습니다.");
            }
            try (InputStream source = new BufferedInputStream(rawInput);
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                int count;
                while ((count = source.read(buffer)) >= 0) {
                    throwIfInterrupted();
                    if (count == 0) {
                        continue;
                    }
                    total += count;
                    if (total > MAX_INPUT_BYTES) {
                        throw new IOException("256 MiB를 넘는 파일은 처리할 수 없습니다.");
                    }
                    output.write(buffer, 0, count);
                }
            }
        }
        if (total == 0) {
            throw new IOException("선택한 파일이 비어 있습니다.");
        }
        return total;
    }

    private static void rejectKnownAnimatedContainer(File input) throws IOException {
        try (InputStream stream = new BufferedInputStream(new FileInputStream(input))) {
            byte[] header = new byte[12];
            int count = readAtMost(stream, header);
            if (count >= 8 && Arrays.equals(Arrays.copyOf(header, 8), PNG_SIGNATURE)) {
                if (pngContainsAnimation(input)) {
                    throw new IOException("움직이는 PNG 이미지는 지원하지 않습니다.");
                }
            } else if (count >= 12
                    && matchesAscii(header, 0, "RIFF")
                    && matchesAscii(header, 8, "WEBP")
                    && webpContainsAnimation(input)) {
                throw new IOException("움직이는 WebP 이미지는 지원하지 않습니다.");
            }
        }
    }

    private static boolean pngContainsAnimation(File input) throws IOException {
        try (InputStream stream = new BufferedInputStream(new FileInputStream(input))) {
            byte[] signature = new byte[8];
            readFully(stream, signature);
            while (true) {
                long length = readUnsignedInt(stream);
                byte[] type = new byte[4];
                readFully(stream, type);
                if (matchesAscii(type, 0, "acTL")) {
                    return true;
                }
                if (matchesAscii(type, 0, "IDAT") || matchesAscii(type, 0, "IEND")) {
                    return false;
                }
                skipFully(stream, length + 4L);
            }
        } catch (EOFException exception) {
            return false;
        }
    }

    private static boolean webpContainsAnimation(File input) throws IOException {
        try (InputStream stream = new BufferedInputStream(new FileInputStream(input))) {
            byte[] header = new byte[12];
            readFully(stream, header);
            while (true) {
                byte[] type = new byte[4];
                int first = stream.read();
                if (first < 0) {
                    return false;
                }
                type[0] = (byte) first;
                readFully(stream, type, 1, 3);
                long length = readLittleEndianUnsignedInt(stream);
                if (matchesAscii(type, 0, "ANIM") || matchesAscii(type, 0, "ANMF")) {
                    return true;
                }
                if (matchesAscii(type, 0, "VP8X")) {
                    int flags = stream.read();
                    if (flags < 0) {
                        return false;
                    }
                    if ((flags & 0x02) != 0) {
                        return true;
                    }
                    skipFully(stream, Math.max(0L, length - 1L) + (length & 1L));
                } else {
                    skipFully(stream, length + (length & 1L));
                }
            }
        } catch (EOFException exception) {
            return false;
        }
    }

    private static void ensureOutputDirectory(File outputDirectory) throws IOException {
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("결과 폴더를 만들 수 없습니다.");
        }
        if (!outputDirectory.isDirectory() || !outputDirectory.canWrite()) {
            throw new IOException("결과 폴더에 파일을 쓸 수 없습니다.");
        }
    }

    private static File createUniqueDestination(File outputDirectory, String extension) throws IOException {
        for (int attempt = 0; attempt < 8; attempt++) {
            File candidate = new File(outputDirectory, "clean_" + UUID.randomUUID() + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        throw new IOException("고유한 결과 파일 이름을 만들 수 없습니다.");
    }

    private static int readAtMost(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                break;
            }
            offset += count;
        }
        return offset;
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        readFully(input, buffer, 0, buffer.length);
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = input.read(buffer, offset + read, length - read);
            if (count < 0) {
                throw new EOFException("이미지 내부 데이터가 중간에 끝났습니다.");
            }
            read += count;
        }
    }

    private static long readUnsignedInt(InputStream input) throws IOException {
        return ((long) readRequiredByte(input) << 24)
                | ((long) readRequiredByte(input) << 16)
                | ((long) readRequiredByte(input) << 8)
                | readRequiredByte(input);
    }

    private static long readLittleEndianUnsignedInt(InputStream input) throws IOException {
        return readRequiredByte(input)
                | ((long) readRequiredByte(input) << 8)
                | ((long) readRequiredByte(input) << 16)
                | ((long) readRequiredByte(input) << 24);
    }

    private static int readRequiredByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("이미지 내부 데이터가 중간에 끝났습니다.");
        }
        return value;
    }

    private static void skipFully(InputStream input, long byteCount) throws IOException {
        if (byteCount < 0 || byteCount > MAX_INPUT_BYTES) {
            throw new IOException("이미지 내부 데이터 길이가 올바르지 않습니다.");
        }
        long remaining = byteCount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() >= 0) {
                remaining--;
            } else {
                throw new EOFException("이미지 내부 데이터가 중간에 끝났습니다.");
            }
        }
    }

    private static boolean matchesAscii(byte[] value, int offset, String expected) {
        if (offset < 0 || value.length - offset < expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if ((value[offset + index] & 0xff) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static void throwIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("이미지 정리 작업이 중단되었습니다.");
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private static final class HeaderRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HeaderRejectedException(String message) {
            super(message);
        }
    }
}
