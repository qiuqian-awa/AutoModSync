package com.example.automodsync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Content-addressed backup store. Legacy filename-only backups remain readable. */
public final class WarehouseManager {
    private static Path warehouseDir;

    private WarehouseManager() {}

    public static synchronized void init(Path rootStorageDir) {
        warehouseDir = rootStorageDir.resolve("warehouse").toAbsolutePath().normalize();
        try { Files.createDirectories(warehouseDir); }
        catch (IOException e) { e.printStackTrace(); }
    }

    public static void backupToWarehouse(Path file) {
        try {
            IntegrityUtil.Hashes hashes = IntegrityUtil.hash(file);
            backupToWarehouse(file, hashes.sha1(), hashes.sha256(), hashes.size());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void backupToWarehouse(Path file, String sha1, String sha256, long size) {
        if (warehouseDir == null || file == null || !Files.isRegularFile(file)) return;
        try {
            String filename = IntegrityUtil.normalizeFileName(file.getFileName().toString());
            IntegrityUtil.Hashes actual = IntegrityUtil.hash(file);
            String contentHash = IntegrityUtil.isHash(sha256, 64) ? sha256.toLowerCase() : actual.sha256();
            if (!actual.sha256().equalsIgnoreCase(contentHash)
                    || (IntegrityUtil.isHash(sha1, 40) && !actual.sha1().equalsIgnoreCase(sha1))
                    || (size >= 0 && actual.size() != size)) {
                throw new IOException("Refused to warehouse a file whose hash changed: " + filename);
            }
            Path bucket = IntegrityUtil.safeResolve(warehouseDir, "sha256/" + contentHash, false);
            Files.createDirectories(bucket);
            Path target = IntegrityUtil.safeResolve(bucket, filename, false);
            if (Files.exists(target) && IntegrityUtil.matches(target, actual.sha1(), actual.sha256(), actual.size())) return;
            copyAtomically(file, target);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized Path getFileInWarehouse(String filename, String sha1, String sha256, long size) {
        if (warehouseDir == null) return null;
        try {
            filename = IntegrityUtil.normalizeFileName(filename);
            if (IntegrityUtil.isHash(sha256, 64)) {
                Path exact = IntegrityUtil.safeResolve(warehouseDir, "sha256/" + sha256.toLowerCase() + "/" + filename, false);
                if (IntegrityUtil.matches(exact, sha1, sha256, size)) return exact;
            }
            Path legacy = IntegrityUtil.safeResolve(warehouseDir, filename, false);
            return IntegrityUtil.matches(legacy, sha1, sha256, size) ? legacy : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Legacy lookup is intentionally restricted to a single safe filename. */
    public static synchronized Path getFileInWarehouse(String filename) {
        if (warehouseDir == null) return null;
        try {
            Path legacy = IntegrityUtil.safeResolve(warehouseDir, IntegrityUtil.normalizeFileName(filename), false);
            return Files.isRegularFile(legacy) ? legacy : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static synchronized boolean restoreFromWarehouse(String filename, Path targetDir,
                                                              String sha1, String sha256, long size) {
        Path source = getFileInWarehouse(filename, sha1, sha256, size);
        if (source == null) return false;
        try {
            Path target = IntegrityUtil.safeResolve(targetDir, IntegrityUtil.normalizeFileName(filename), false);
            Files.createDirectories(target.getParent());
            copyAtomically(source, target);
            return IntegrityUtil.matches(target, sha1, sha256, size);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean restoreFromWarehouse(String filename, Path targetDir) {
        return false; // Unsafe legacy restores without an expected hash are deliberately disabled.
    }

    public static synchronized boolean isInWarehouse(String filename, String sha1, String sha256, long size) {
        return getFileInWarehouse(filename, sha1, sha256, size) != null;
    }

    public static synchronized boolean isInWarehouse(String filename) {
        return getFileInWarehouse(filename) != null;
    }

    private static void copyAtomically(Path source, Path target) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
