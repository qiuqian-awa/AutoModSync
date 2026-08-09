package com.example.automodsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Content-addressed client/server artifact warehouse. */
public class WarehouseManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path warehouseDir;
    private static Path metadataDir;
    private static Path sha1Dir;
    private static Path indexFile;
    private static final Map<String, String> fileNameIndex = new HashMap<>();

    public static synchronized void init(Path rootStorageDir) {
        warehouseDir = rootStorageDir.resolve("warehouse");
        metadataDir = warehouseDir.resolve("metadata");
        sha1Dir = warehouseDir.resolve("sha1");
        indexFile = metadataDir.resolve("artifact_index.json");
        try {
            Files.createDirectories(metadataDir);
            Files.createDirectories(sha1Dir);
            migrateLegacyIndex();
            loadIndex();
            migrateFlatHashObjects();
            migrateShardedObjects();
            migrateLegacyFiles();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static synchronized void backupToWarehouse(Path modFile) {
        store(modFile);
    }

    public static synchronized String store(Path file) {
        return store(file, file == null ? null : file.getFileName().toString());
    }

    public static synchronized String store(Path file, String originalFileName) {
        if (warehouseDir == null || file == null || !Files.isRegularFile(file)) return null;
        try {
            String sha1 = calculateSha1(file);
            if (sha1.isEmpty()) return null;
            String safeName = sanitizeFileName(originalFileName, "artifact.bin");
            Path hashDir = getObjectDirectory(sha1);
            Files.createDirectories(hashDir);
            Path existing = findObjectFile(sha1);
            if (existing == null) {
                Path target = hashDir.resolve(safeName);
                Path part = target.resolveSibling(target.getFileName() + ".part");
                Files.copy(file, part, StandardCopyOption.REPLACE_EXISTING);
                if (!sha1.equalsIgnoreCase(calculateSha1(part))) {
                    Files.deleteIfExists(part);
                    throw new IOException("SHA-1 changed while storing " + file);
                }
                moveAtomically(part, target);
            }
            fileNameIndex.put(safeName, sha1);
            saveIndex();
            return sha1;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static synchronized Path getFileByHash(String sha1) {
        if (!isValidSha1(sha1) || sha1Dir == null) return null;
        return findObjectFile(sha1.toLowerCase());
    }

    /** Legacy filename lookup retained for old manifests and migration. */
    public static synchronized Path getFileInWarehouse(String filename) {
        if (warehouseDir == null || filename == null) return null;
        String sha1 = fileNameIndex.get(filename);
        if (sha1 != null) {
            Path byHash = getFileByHash(sha1);
            if (byHash != null) return byHash;
        }
        Path legacy = warehouseDir.resolve(filename).normalize();
        return legacy.startsWith(warehouseDir) && Files.isRegularFile(legacy) ? legacy : null;
    }

    public static synchronized boolean restoreByHash(String sha1, Path target) {
        Path source = getFileByHash(sha1);
        if (source == null || target == null) return false;
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return sha1.equalsIgnoreCase(calculateSha1(target));
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static synchronized boolean restoreFromWarehouse(String fileName, Path targetDir) {
        String sha1 = fileNameIndex.get(fileName);
        if (sha1 != null) return restoreByHash(sha1, targetDir.resolve(fileName));
        Path legacy = getFileInWarehouse(fileName);
        if (legacy == null) return false;
        try {
            Files.copy(legacy, targetDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static synchronized boolean isInWarehouse(String fileName) {
        return getFileInWarehouse(fileName) != null;
    }

    public static synchronized boolean isHashInWarehouse(String sha1) {
        return getFileByHash(sha1) != null;
    }

    public static String calculateSha1(Path file) {
        try (FileInputStream input = new FileInputStream(file.toFile())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder result = new StringBuilder(40);
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized Path getObjectDirectory(String sha1) {
        if (!isValidSha1(sha1) || sha1Dir == null) return null;
        String normalized = sha1.toLowerCase();
        return sha1Dir.resolve(normalized);
    }

    private static Path findObjectFile(String sha1) {
        Path hashDir = getObjectDirectory(sha1);
        if (hashDir == null || !Files.isDirectory(hashDir)) return null;
        String preferredName = preferredFileNameForHash(sha1);
        if (preferredName != null) {
            Path preferred = hashDir.resolve(preferredName);
            if (Files.isRegularFile(preferred)) return preferred;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(hashDir)) {
            for (Path candidate : stream) if (Files.isRegularFile(candidate)) return candidate;
        } catch (IOException ignored) {}
        return null;
    }

    private static String preferredFileNameForHash(String sha1) {
        return fileNameIndex.entrySet().stream()
                .filter(entry -> sha1.equalsIgnoreCase(entry.getValue()))
                .map(Map.Entry::getKey)
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder()).findFirst().orElse(null);
    }

    private static String sanitizeFileName(String value, String fallback) {
        try {
            if (value != null && !value.isBlank()) {
                String name = Path.of(value).getFileName().toString();
                if (!name.isBlank()) return name;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean isValidSha1(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{40}");
    }

    private static void loadIndex() {
        fileNameIndex.clear();
        if (!Files.isRegularFile(indexFile)) return;
        try (Reader reader = Files.newBufferedReader(indexFile)) {
            Map<String, String> loaded = GSON.fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
            if (loaded != null) fileNameIndex.putAll(loaded);
        } catch (Exception ignored) {}
    }

    private static void migrateLegacyIndex() throws IOException {
        Path legacy = warehouseDir.resolve("index.json");
        if (Files.isRegularFile(indexFile) || !Files.isRegularFile(legacy)) return;
        Files.copy(legacy, indexFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void saveIndex() throws IOException {
        Path part = indexFile.resolveSibling(indexFile.getFileName() + ".part");
        try (Writer writer = Files.newBufferedWriter(part)) {
            GSON.toJson(fileNameIndex, writer);
        }
        moveAtomically(part, indexFile);
    }

    private static void migrateLegacyFiles() throws IOException {
        boolean changed = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(warehouseDir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file) || file.equals(indexFile)
                        || file.getFileName().toString().equalsIgnoreCase("index.json")) continue;
                String sha1 = calculateSha1(file);
                if (sha1.isEmpty()) continue;
                Path hashDir = getObjectDirectory(sha1);
                Files.createDirectories(hashDir);
                if (findObjectFile(sha1) == null) Files.copy(file, hashDir.resolve(file.getFileName()));
                fileNameIndex.put(file.getFileName().toString(), sha1);
                changed = true;
            }
        }
        if (changed) saveIndex();
    }

    /** Migrates objects from the former sha1/ab/<full-sha1>.jar layout. */
    private static void migrateShardedObjects() throws IOException {
        try (DirectoryStream<Path> prefixes = Files.newDirectoryStream(sha1Dir)) {
            for (Path prefixDir : prefixes) {
                if (!Files.isDirectory(prefixDir)) continue;
                String prefix = prefixDir.getFileName().toString();
                if (!prefix.matches("(?i)[0-9a-f]{2}")) continue;
                try (DirectoryStream<Path> objects = Files.newDirectoryStream(prefixDir, "*.jar")) {
                    for (Path object : objects) {
                        String fileName = object.getFileName().toString();
                        String sha1 = fileName.substring(0, fileName.length() - 4).toLowerCase();
                        if (!isValidSha1(sha1) || !sha1.startsWith(prefix.toLowerCase())) continue;
                        if (!sha1.equalsIgnoreCase(calculateSha1(object))) continue;
                        migrateHashNamedObject(object, sha1);
                    }
                }
            }
        }
    }

    /** Migrates objects from the former sha1/<full-sha1>.jar layout. */
    private static void migrateFlatHashObjects() throws IOException {
        try (DirectoryStream<Path> objects = Files.newDirectoryStream(sha1Dir, "*.jar")) {
            for (Path object : objects) {
                String fileName = object.getFileName().toString();
                String sha1 = fileName.substring(0, fileName.length() - 4).toLowerCase();
                if (!isValidSha1(sha1) || !sha1.equalsIgnoreCase(calculateSha1(object))) continue;
                migrateHashNamedObject(object, sha1);
            }
        }
    }

    private static void migrateHashNamedObject(Path object, String sha1) throws IOException {
        Path hashDir = getObjectDirectory(sha1);
        Files.createDirectories(hashDir);
        Path existing = findObjectFile(sha1);
        if (existing != null) return;
        String originalName = preferredFileNameForHash(sha1);
        if (originalName == null) originalName = "artifact.bin";
        Files.copy(object, hashDir.resolve(originalName), StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
