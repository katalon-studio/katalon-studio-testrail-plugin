package com.katalon.plugin.testrail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips a test suite's report folder so it can be attached to a TestRail run.
 */
public final class ReportArchiver {
    // TestOps upload leaves HAR zips here, which are not part of the report
    static final String ANALYTICS_TEMP_FOLDER = "katalon-analytics-tmp";

    // Java logger lock files are empty, and Windows may refuse to read one that is still locked
    static final String LOCK_FILE_SUFFIX = ".lck";

    private ReportArchiver() {
    }

    /**
     * A zipped report folder.
     */
    public static final class Archive {
        /** The temporary zip, which the caller deletes. Null when the zip grew past the size limit. */
        public final File zip;

        /** Files left out because they could not be read, relative to the report folder. */
        public final List<String> skippedFiles;

        Archive(File zip, List<String> skippedFiles) {
            this.zip = zip;
            this.skippedFiles = Collections.unmodifiableList(skippedFiles);
        }

        public boolean isOverLimit() {
            return zip == null;
        }
    }

    /**
     * The attachment name, taken from the report folder, e.g. 20261015_142248.zip.
     */
    public static String attachmentName(File reportFolder) {
        return reportFolder.getName() + ".zip";
    }

    /**
     * Writes the folder's files into a new temporary zip, with paths relative to the folder. A file that cannot be
     * opened is left out and listed, so one locked file does not cost the whole report. The walk stops and the zip
     * is deleted as soon as it grows past maxBytes.
     */
    public static Archive zip(File reportFolder, long maxBytes) throws IOException {
        Path root = reportFolder.toPath();
        File zip = File.createTempFile("testrail-report-", ".zip");
        List<String> skippedFiles = new ArrayList<>();
        boolean[] overLimit = { false };
        try (CountingOutputStream counter = new CountingOutputStream(new FileOutputStream(zip));
                ZipOutputStream out = new ZipOutputStream(counter)) {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return ANALYTICS_TEMP_FOLDER.equals(dir.getFileName().toString()) && !dir.equals(root)
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || file.getFileName().toString().endsWith(LOCK_FILE_SUFFIX)) {
                        return FileVisitResult.CONTINUE;
                    }
                    InputStream input;
                    try {
                        input = Files.newInputStream(file);
                    } catch (IOException e) {
                        skippedFiles.add(relativeName(root, file));
                        return FileVisitResult.CONTINUE;
                    }
                    try (InputStream in = input) {
                        out.putNextEntry(new ZipEntry(relativeName(root, file)));
                        byte[] buffer = new byte[64 * 1024];
                        for (int read; (read = in.read(buffer)) > 0;) {
                            out.write(buffer, 0, read);
                            if (counter.count > maxBytes) {
                                overLimit[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                        }
                        out.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // A file or folder whose attributes or listing cannot be read
                    skippedFiles.add(relativeName(root, file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            zip.delete();
            throw e;
        }
        if (overLimit[0]) {
            zip.delete();
            return new Archive(null, skippedFiles);
        }
        return new Archive(zip, skippedFiles);
    }

    private static String relativeName(Path root, Path file) {
        String name = root.relativize(file).toString().replace(File.separatorChar, '/');
        return name.isEmpty() ? root.getFileName().toString() : name;
    }

    /** Counts the bytes written, so the walk can stop once the zip is too big to attach. */
    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
