package com.example.automodsync;

import java.io.IOException;
import java.nio.file.*;

public class WarehouseManager {
    private static Path warehouseDir;

    public static void init(Path rootStorageDir) {
        warehouseDir = rootStorageDir.resolve("warehouse");
        try {
            if (!Files.exists(warehouseDir)) Files.createDirectories(warehouseDir);
        } catch (IOException e) { e.printStackTrace(); }
    }

    public static void backupToWarehouse(Path modFile) {
        if (warehouseDir == null) return;
        try {
            if (!Files.exists(modFile)) return;
            if (!Files.exists(warehouseDir)) Files.createDirectories(warehouseDir);
            Path target = warehouseDir.resolve(modFile.getFileName());
            if (!Files.exists(target)) {
                Files.copy(modFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    public static Path getFileInWarehouse(String filename) {
        if (warehouseDir == null) return null;
        Path p = warehouseDir.resolve(filename);
        return Files.exists(p) ? p : null;
    }

    public static boolean restoreFromWarehouse(String fileName, Path targetDir) {
        if (warehouseDir == null) return false;
        try {
            Path source = warehouseDir.resolve(fileName);
            if (Files.exists(source)) {
                Files.copy(source, targetDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        } catch (IOException e) { e.printStackTrace(); }
        return false;
    }

    public static boolean isInWarehouse(String fileName) {
        return warehouseDir != null && Files.exists(warehouseDir.resolve(fileName));
    }
}
