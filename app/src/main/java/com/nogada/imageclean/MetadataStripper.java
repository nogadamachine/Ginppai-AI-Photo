package com.nogada.imageclean;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

final class MetadataStripper {
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final long MAX_PNG_CHUNK_BYTES = 256L * 1024L * 1024L;

    private MetadataStripper() {
    }

    static void strip(File encodedInput, File cleanedOutput, boolean jpeg) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(encodedInput));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(cleanedOutput))) {
            if (jpeg) {
                stripJpeg(input, output);
            } else {
                stripPng(input, output);
            }
        }
    }

    private static void stripPng(InputStream rawInput, OutputStream rawOutput) throws IOException {
        DataInputStream input = new DataInputStream(rawInput);
        DataOutputStream output = new DataOutputStream(rawOutput);
        byte[] signature = new byte[PNG_SIGNATURE.length];
        input.readFully(signature);
        if (!Arrays.equals(signature, PNG_SIGNATURE)) {
            throw new IOException("저장 결과가 올바른 PNG 파일이 아닙니다.");
        }
        output.write(signature);

        boolean sawHeader = false;
        boolean sawImageData = false;
        boolean sawEnd = false;
        byte[] buffer = new byte[32 * 1024];
        while (!sawEnd) {
            long length = Integer.toUnsignedLong(input.readInt());
            if (length > MAX_PNG_CHUNK_BYTES) {
                throw new IOException("PNG 내부 데이터가 허용 크기를 넘습니다.");
            }
            byte[] typeBytes = new byte[4];
            input.readFully(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            if (!isPngChunkType(typeBytes)) {
                throw new IOException("PNG 내부 데이터 종류가 올바르지 않습니다.");
            }
            if (!sawHeader && !"IHDR".equals(type)) {
                throw new IOException("PNG 헤더가 없거나 순서가 올바르지 않습니다.");
            }
            if ("IHDR".equals(type)) {
                if (sawHeader || length != 13) {
                    throw new IOException("PNG 헤더가 올바르지 않습니다.");
                }
                sawHeader = true;
            } else if ("IDAT".equals(type)) {
                sawImageData = true;
            } else if ("IEND".equals(type)) {
                if (!sawImageData || length != 0) {
                    throw new IOException("PNG 끝 표시가 올바르지 않습니다.");
                }
                sawEnd = true;
            }

            if (isCriticalPngChunk(typeBytes) && !isAllowedPngCriticalChunk(type)) {
                throw new IOException("지원하지 않는 PNG 필수 데이터가 있습니다.");
            }
            boolean keep = isAllowedPngCriticalChunk(type) || isAllowedPngAncillaryChunk(type);
            if (keep) {
                output.writeInt((int) length);
                output.write(typeBytes);
            }

            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    throw new EOFException("PNG 내부 데이터가 중간에 끝났습니다.");
                }
                crc.update(buffer, 0, count);
                if (keep) {
                    output.write(buffer, 0, count);
                }
                remaining -= count;
            }
            long expectedCrc = Integer.toUnsignedLong(input.readInt());
            if (crc.getValue() != expectedCrc) {
                throw new IOException("PNG 내부 데이터의 체크섬이 올바르지 않습니다.");
            }
            if (keep) {
                output.writeInt((int) expectedCrc);
            }
        }
        if (input.read() != -1) {
            throw new IOException("PNG 끝에 예상하지 못한 데이터가 있습니다.");
        }
        output.flush();
    }

    private static void stripJpeg(InputStream input, OutputStream output) throws IOException {
        if (readRequiredByte(input) != 0xff || readRequiredByte(input) != 0xd8) {
            throw new IOException("저장 결과가 올바른 JPEG 파일이 아닙니다.");
        }
        output.write(0xff);
        output.write(0xd8);

        boolean inScan = false;
        boolean sawScan = false;
        boolean sawEnd = false;
        int pendingMarker = -1;
        while (!sawEnd) {
            if (inScan) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException("JPEG 이미지 데이터가 중간에 끝났습니다.");
                }
                if (value != 0xff) {
                    output.write(value);
                    continue;
                }
                int marker = readRequiredByte(input);
                while (marker == 0xff) {
                    marker = readRequiredByte(input);
                }
                if (marker == 0x00 || isRestartMarker(marker) || marker == 0x01) {
                    output.write(0xff);
                    output.write(marker);
                    continue;
                }
                inScan = false;
                pendingMarker = marker;
            }

            int marker = pendingMarker >= 0 ? pendingMarker : readJpegMarker(input);
            pendingMarker = -1;
            if (marker == 0xd9) {
                output.write(0xff);
                output.write(marker);
                sawEnd = true;
                continue;
            }
            if (marker == 0xd8) {
                throw new IOException("JPEG 시작 표시 위치가 올바르지 않습니다.");
            }
            if (isRestartMarker(marker) || marker == 0x01) {
                output.write(0xff);
                output.write(marker);
                continue;
            }

            int lengthHigh = readRequiredByte(input);
            int lengthLow = readRequiredByte(input);
            int segmentLength = (lengthHigh << 8) | lengthLow;
            if (segmentLength < 2) {
                throw new IOException("JPEG 내부 데이터 길이가 올바르지 않습니다.");
            }
            byte[] payload = new byte[segmentLength - 2];
            readFully(input, payload);
            boolean keep = shouldKeepJpegSegment(marker, payload);
            if (keep) {
                output.write(0xff);
                output.write(marker);
                output.write(lengthHigh);
                output.write(lengthLow);
                output.write(payload);
            }
            if (marker == 0xda) {
                if (!keep) {
                    throw new IOException("JPEG 이미지 헤더를 유지할 수 없습니다.");
                }
                sawScan = true;
                inScan = true;
            } else if (marker == 0xdc && sawScan) {
                inScan = true;
            }
        }
        if (!sawScan) {
            throw new IOException("JPEG에 이미지 데이터가 없습니다.");
        }
        output.flush();
    }

    private static int readJpegMarker(InputStream input) throws IOException {
        int prefix = readRequiredByte(input);
        if (prefix != 0xff) {
            throw new IOException("JPEG 이미지 데이터 구조가 올바르지 않습니다.");
        }
        int marker = readRequiredByte(input);
        while (marker == 0xff) {
            marker = readRequiredByte(input);
        }
        if (marker == 0x00) {
            throw new IOException("JPEG 이스케이프 데이터 위치가 올바르지 않습니다.");
        }
        return marker;
    }

    private static boolean shouldKeepJpegSegment(int marker, byte[] payload) {
        if (marker == 0xfe) {
            return false;
        }
        if (marker < 0xe0 || marker > 0xef) {
            return true;
        }
        if (marker == 0xe0) {
            return startsWith(payload, new byte[]{'J', 'F', 'I', 'F', 0});
        }
        if (marker == 0xee) {
            return startsWith(payload, new byte[]{'A', 'd', 'o', 'b', 'e'});
        }
        return false;
    }

    private static boolean isAllowedPngAncillaryChunk(String type) {
        return "sRGB".equals(type)
                || "gAMA".equals(type)
                || "cHRM".equals(type)
                || "tRNS".equals(type);
    }

    private static boolean isAllowedPngCriticalChunk(String type) {
        return "IHDR".equals(type)
                || "PLTE".equals(type)
                || "IDAT".equals(type)
                || "IEND".equals(type);
    }

    private static boolean isCriticalPngChunk(byte[] type) {
        return (type[0] & 0x20) == 0;
    }

    private static boolean isPngChunkType(byte[] type) {
        for (byte value : type) {
            if (!((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRestartMarker(int marker) {
        return marker >= 0xd0 && marker <= 0xd7;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static int readRequiredByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("저장된 이미지가 중간에 끝났습니다.");
        }
        return value;
    }

    private static void readFully(InputStream input, byte[] value) throws IOException {
        int offset = 0;
        while (offset < value.length) {
            int count = input.read(value, offset, value.length - offset);
            if (count < 0) {
                throw new EOFException("저장된 이미지가 중간에 끝났습니다.");
            }
            offset += count;
        }
    }
}
