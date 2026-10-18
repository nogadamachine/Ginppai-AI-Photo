package com.nogada.imageclean;

import android.net.Uri;
import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class WatermarkReferenceTest extends AndroidTestCase {
    public void testExportAndroidProcessedReferenceImages() throws Exception {
        File outputDirectory = new File(getContext().getExternalFilesDir(null), "watermark-reference");
        assertTrue(outputDirectory.isDirectory() || outputDirectory.mkdirs());
        String[] fixtures = getContext().getAssets().list("watermark");
        assertNotNull(fixtures);
        assertEquals(12, fixtures.length);
        for (String filename : fixtures) {
            File input = new File(getContext().getCacheDir(), filename);
            try {
                try (InputStream source = getContext().getAssets().open("watermark/" + filename);
                     FileOutputStream destination = new FileOutputStream(input)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = source.read(buffer)) != -1) destination.write(buffer, 0, count);
                }
                ImageProcessor.Result result = ImageProcessor.process(getContext(), Uri.fromFile(input), outputDirectory, false, false, true);
                assertEquals(512, result.width);
                assertEquals(512, result.height);
                assertTrue(result.details.contains("검출 미검증"));
                Files.move(result.file.toPath(), new File(outputDirectory, filename).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                input.delete();
            }
        }
    }
}
