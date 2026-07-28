package com.example.automodsync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Resolves official CurseForge/Modrinth file URLs on the server and persists a hash cache. */
public final class OfficialSourceResolver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type URL_MAP_TYPE = new TypeToken<Map<String, List<String>>>() {}.getType();
    private static final String USER_AGENT = "AutoModSync/3.2 (Minecraft server administrator)";
    private static final int MINECRAFT_GAME_ID = 432;

    private final Path cacheFile;
    private final Path overridesFile;
    private final Map<String, List<String>> cache = new TreeMap<>();
    private final Map<String, List<String>> overrides = new TreeMap<>();

    public OfficialSourceResolver(Path storageDir) {
        this.cacheFile = storageDir.resolve("official_source_cache.json");
        this.overridesFile = storageDir.resolve("official_sources.json");
        loadMap(cacheFile, cache);
        loadMap(overridesFile, overrides);
        if (!Files.exists(overridesFile)) {
            try {
                Files.writeString(overridesFile,
                        "{\n  \"example-mod.jar\": [\n    \"https://cdn.modrinth.com/data/.../versions/.../example-mod.jar\"\n  ]\n}\n",
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.warn("AutoModSync: Could not create official_sources.json", e);
            }
        }
    }

    public List<String> resolve(Path file, String fileName, String modId, String sha1) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        boolean cached;
        synchronized (this) {
            addValid(urls, overrides.get(fileName));
            addValid(urls, overrides.get(modId));
            addValid(urls, overrides.get(sha1));
            cached = cache.containsKey(sha1);
            addValid(urls, cache.get(sha1));
        }

        if (urls.isEmpty() && !cached && ModSyncMain.CONFIG.enableOfficialSourceLookup.get()) {
            addValid(urls, resolveModrinth(sha1, fileName));
            String apiKey = ModSyncMain.CONFIG.curseForgeApiKey.get().trim();
            if (!apiKey.isEmpty()) addValid(urls, resolveCurseForge(file, fileName, apiKey));
            synchronized (this) {
                cache.put(sha1, new ArrayList<>(urls));
                saveCache();
            }
        }
        return new ArrayList<>(urls);
    }

    private List<String> resolveModrinth(String sha1, String fileName) {
        List<String> result = new ArrayList<>();
        if (!IntegrityUtil.isHash(sha1, 40)) return result;
        try {
            URL url = URI.create("https://api.modrinth.com/v2/version_file/" + sha1 + "?algorithm=sha1").toURL();
            JsonObject root = requestJson(url, "GET", null, null);
            JsonArray files = root.has("files") && root.get("files").isJsonArray() ? root.getAsJsonArray("files") : new JsonArray();
            for (JsonElement element : files) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String candidateName = string(entry, "filename");
                String candidateUrl = string(entry, "url");
                if (fileName.equals(candidateName) && isAllowedOfficialUrl(candidateUrl)) result.add(candidateUrl);
            }
        } catch (IOException e) {
            LOGGER.debug("AutoModSync: Modrinth lookup unavailable for {}: {}", fileName, e.getMessage());
        }
        return result;
    }

    private List<String> resolveCurseForge(Path file, String fileName, String apiKey) {
        List<String> result = new ArrayList<>();
        try {
            long fingerprint = curseFingerprint(file);
            JsonObject body = new JsonObject();
            JsonArray fingerprints = new JsonArray();
            fingerprints.add(fingerprint);
            body.add("fingerprints", fingerprints);
            JsonObject root = requestJson(
                    URI.create("https://api.curseforge.com/v1/fingerprints/" + MINECRAFT_GAME_ID).toURL(),
                    "POST", body.toString(), apiKey);
            JsonObject data = root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : new JsonObject();
            JsonArray matches = data.has("exactMatches") && data.get("exactMatches").isJsonArray()
                    ? data.getAsJsonArray("exactMatches") : new JsonArray();
            for (JsonElement element : matches) {
                if (!element.isJsonObject()) continue;
                JsonObject match = element.getAsJsonObject();
                if (!match.has("file") || !match.get("file").isJsonObject()) continue;
                JsonObject matchedFile = match.getAsJsonObject("file");
                String candidateName = string(matchedFile, "fileName");
                String candidateUrl = string(matchedFile, "downloadUrl");
                if (fileName.equals(candidateName) && isAllowedOfficialUrl(candidateUrl)) result.add(candidateUrl);
            }
        } catch (IOException e) {
            LOGGER.debug("AutoModSync: CurseForge lookup unavailable for {}: {}", fileName, e.getMessage());
        }
        return result;
    }

    private JsonObject requestJson(URL url, String method, String body, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        if (apiKey != null) connection.setRequestProperty("x-api-key", apiKey);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        if (code == 404) throw new IOException("Not found");
        if (code != 200) throw new IOException("HTTP " + code);
        try (InputStream in = connection.getInputStream()) {
            byte[] json = readLimited(in, 2 * 1024 * 1024);
            JsonElement parsed = com.google.gson.JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("Invalid JSON response");
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON response", e);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("Response too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static long curseFingerprint(Path path) throws IOException {
        long rawSize = Files.size(path);
        if (rawSize > IntegrityUtil.MAX_DOWNLOAD_BYTES) throw new IOException("File exceeds fingerprint safety limit");
        int length = 0;
        try (InputStream in = Files.newInputStream(path)) {
            int value;
            while ((value = in.read()) != -1) {
                if (value != 9 && value != 10 && value != 13 && value != 32) {
                    if (length == Integer.MAX_VALUE) throw new IOException("File is too large to fingerprint");
                    length++;
                }
            }
        }
        int hash = murmurHash2(path, length, 1);
        return Integer.toUnsignedLong(hash);
    }

    private static int murmurHash2(Path path, int length, int seed) throws IOException {
        final int m = 0x5bd1e995;
        int hash = seed ^ length;
        int tail = 0;
        int tailBytes = 0;
        try (InputStream in = Files.newInputStream(path)) {
            int value;
            while ((value = in.read()) != -1) {
                if (value == 9 || value == 10 || value == 13 || value == 32) continue;
                tail |= (value & 0xff) << (tailBytes * 8);
                tailBytes++;
                if (tailBytes == 4) {
                    int k = tail * m;
                    k ^= k >>> 24;
                    k *= m;
                    hash *= m;
                    hash ^= k;
                    tail = 0;
                    tailBytes = 0;
                }
            }
        }
        if (tailBytes == 3) hash ^= tail & 0x00ffffff;
        else if (tailBytes == 2) hash ^= tail & 0x0000ffff;
        else if (tailBytes == 1) hash ^= tail & 0x000000ff;
        if (tailBytes > 0) {
            hash *= m;
        }
        hash ^= hash >>> 13;
        hash *= m;
        hash ^= hash >>> 15;
        return hash;
    }

    public static boolean isAllowedOfficialUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) return false;
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("cdn.modrinth.com") || host.endsWith(".modrinth.com")
                    || host.equals("curseforge.com") || host.endsWith(".curseforge.com")
                    || host.equals("edge.forgecdn.net") || host.endsWith(".forgecdn.net");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String string(JsonObject object, String name) {
        try { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : ""; }
        catch (RuntimeException e) { return ""; }
    }

    private static void addValid(LinkedHashSet<String> target, List<String> values) {
        if (values == null) return;
        for (String value : values) if (isAllowedOfficialUrl(value)) target.add(value);
    }

    private void loadMap(Path file, Map<String, List<String>> target) {
        if (!Files.isRegularFile(file)) return;
        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, List<String>> loaded = GSON.fromJson(reader, URL_MAP_TYPE);
            if (loaded != null) target.putAll(loaded);
        } catch (Exception e) {
            LOGGER.warn("AutoModSync: Ignored malformed source map {}", file, e);
        }
    }

    private void saveCache() {
        Path temp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(cache), StandardCharsets.UTF_8);
            try {
                Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("AutoModSync: Could not save official source cache", e);
        }
    }
}
