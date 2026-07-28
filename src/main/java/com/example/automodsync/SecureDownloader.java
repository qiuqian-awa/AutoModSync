package com.example.automodsync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Downloads one verified file, trying official HTTPS URLs before the server fallback. */
public final class SecureDownloader {
    private static final String USER_AGENT = "AutoModSync/3.2 (Minecraft client)";
    private static final int MAX_REDIRECTS = 5;

    private SecureDownloader() {}

    @FunctionalInterface
    public interface ProgressListener {
        void update(String source, long downloaded, long total);
    }

    public record Result(String source, long bytes) {}

    public static Result download(List<String> officialUrls, URL serverFallback, Path target,
                                  String sha1, String sha256, long expectedSize,
                                  ProgressListener listener) throws IOException {
        if (!IntegrityUtil.isHash(sha1, 40) && !IntegrityUtil.isHash(sha256, 64)) {
            throw new IOException("The manifest has no usable hash");
        }
        List<String> errors = new ArrayList<>();
        if (officialUrls != null) {
            for (String candidate : officialUrls) {
                if (!OfficialSourceResolver.isAllowedOfficialUrl(candidate)) continue;
                try {
                    return downloadOne(URI.create(candidate).toURL(), target, sha1, sha256, expectedSize, true, listener);
                } catch (Exception e) {
                    errors.add(shortError("official", e));
                }
            }
        }
        if (serverFallback != null) {
            try {
                return downloadOne(serverFallback, target, sha1, sha256, expectedSize, false, listener);
            } catch (Exception e) {
                errors.add(shortError("server", e));
            }
        }
        throw new IOException("All download sources failed: " + String.join("; ", errors));
    }

    private static Result downloadOne(URL initial, Path target, String sha1, String sha256, long expectedSize,
                                      boolean official, ProgressListener listener) throws IOException {
        URL current = initial;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateUrl(current, official);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/java-archive, application/octet-stream;q=0.9, */*;q=0.1");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without Location");
                current = URI.create(current.toString()).resolve(location).toURL();
                continue;
            }
            if (code != 200) {
                connection.disconnect();
                throw new IOException("HTTP " + code);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > IntegrityUtil.MAX_DOWNLOAD_BYTES) {
                connection.disconnect();
                throw new IOException("Download is too large");
            }
            if (expectedSize >= 0 && contentLength >= 0 && contentLength != expectedSize) {
                connection.disconnect();
                throw new IOException("Content-Length does not match manifest");
            }

            Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            Path part = target.resolveSibling(target.getFileName() + ".part");
            long downloaded = 0L;
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    downloaded += read;
                    if (downloaded > IntegrityUtil.MAX_DOWNLOAD_BYTES
                            || (expectedSize >= 0 && downloaded > expectedSize)) {
                        throw new IOException("Download exceeded expected size");
                    }
                    out.write(buffer, 0, read);
                    if (listener != null) listener.update(official ? "官网" : "服务器", downloaded,
                            expectedSize >= 0 ? expectedSize : contentLength);
                }
            } catch (IOException e) {
                Files.deleteIfExists(part);
                throw e;
            } finally {
                connection.disconnect();
            }
            if (!IntegrityUtil.matches(part, sha1, sha256, expectedSize)) {
                Files.deleteIfExists(part);
                throw new IOException("Hash verification failed");
            }
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(part);
            }
            return new Result(official ? "官网" : "服务器", downloaded);
        }
        throw new IOException("Too many redirects");
    }

    private static void validateUrl(URL url, boolean official) throws IOException {
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        if (!(protocol.equals("http") || protocol.equals("https")) || url.getUserInfo() != null) {
            throw new IOException("Unsupported URL");
        }
        if (official && !OfficialSourceResolver.isAllowedOfficialUrl(url.toString())) {
            throw new IOException("Official URL redirected outside approved CDNs");
        }
    }

    private static String shortError(String source, Exception error) {
        String message = error.getMessage();
        if (message == null) message = error.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ');
        if (message.length() > 120) message = message.substring(0, 120);
        return source + ": " + message;
    }
}
