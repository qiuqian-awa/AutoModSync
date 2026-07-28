package com.example.automodsync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Shared path and file-integrity checks. All externally supplied paths pass through this class. */
public final class IntegrityUtil {
    public static final long MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L;

    private IntegrityUtil() {}

    public record Hashes(String sha1, String sha256, long size) {}

    public static Hashes hash(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular file: " + file);
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            long size = 0L;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    size += read;
                    if (size > MAX_DOWNLOAD_BYTES) throw new IOException("File exceeds safety limit: " + file);
                    sha1.update(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                }
            }
            return new Hashes(HexFormat.of().formatHex(sha1.digest()), HexFormat.of().formatHex(sha256.digest()), size);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean matches(Path file, String sha1, String sha256, long expectedSize) {
        if (!isHash(sha1, 40) && !isHash(sha256, 64)) return false;
        try {
            Hashes actual = hash(file);
            if (expectedSize >= 0 && actual.size != expectedSize) return false;
            if (isHash(sha256, 64) && !actual.sha256.equalsIgnoreCase(sha256)) return false;
            return !isHash(sha1, 40) || actual.sha1.equalsIgnoreCase(sha1);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isHash(String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** Resolve an untrusted relative path and prove that it remains below root. */
    public static Path safeResolve(Path root, String relative, boolean allowRoot) throws IOException {
        if (root == null || relative == null || relative.isBlank() || relative.indexOf('\0') >= 0) {
            throw new IOException("Empty or invalid path");
        }
        if (relative.indexOf(':') >= 0 || relative.startsWith("/") || relative.startsWith("\\")) {
            throw new IOException("Absolute paths are forbidden: " + relative);
        }
        Path supplied;
        try {
            supplied = Path.of(relative.replace('/', java.io.File.separatorChar));
        } catch (RuntimeException e) {
            throw new IOException("Malformed path: " + relative, e);
        }
        if (supplied.isAbsolute()) throw new IOException("Absolute paths are forbidden: " + relative);
        for (Path part : supplied) {
            String name = part.toString();
            if (name.equals(".") || name.equals("..") || name.isBlank() || isWindowsDeviceName(name)) {
                throw new IOException("Unsafe path component: " + name);
            }
        }

        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(supplied).normalize();
        if (!result.startsWith(normalizedRoot) || (!allowRoot && result.equals(normalizedRoot))) {
            throw new IOException("Path escapes managed root: " + relative);
        }

        // Existing symlink ancestors must also resolve below root.
        Path rootReal = Files.exists(normalizedRoot) ? normalizedRoot.toRealPath() : normalizedRoot;
        Path cursor = normalizedRoot;
        for (Path part : normalizedRoot.relativize(result)) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                Path real = cursor.toRealPath();
                if (!real.startsWith(rootReal)) throw new IOException("Symlink escapes managed root: " + relative);
            }
        }
        return result;
    }

    public static String normalizeFileName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Empty filename");
        Path path;
        try { path = Path.of(name); } catch (RuntimeException e) { throw new IOException("Malformed filename", e); }
        if (path.isAbsolute() || path.getNameCount() != 1 || !path.getFileName().toString().equals(name)
                || name.contains("/") || name.contains("\\") || name.indexOf(':') >= 0 || isWindowsDeviceName(name)) {
            throw new IOException("Unsafe filename: " + name);
        }
        return name;
    }

    private static boolean isWindowsDeviceName(String name) {
        String stem = name;
        int dot = stem.indexOf('.');
        if (dot >= 0) stem = stem.substring(0, dot);
        stem = stem.toUpperCase(Locale.ROOT);
        if (stem.equals("CON") || stem.equals("PRN") || stem.equals("AUX") || stem.equals("NUL")) return true;
        return stem.matches("COM[1-9]") || stem.matches("LPT[1-9]");
    }
}
