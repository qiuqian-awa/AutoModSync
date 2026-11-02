package com.example.automodsync;

import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Determines whether a mod loaded on the dedicated server must also be present
 * on connecting clients.
 *
 * <p>NeoForge 21.1 removed {@code IExtensionPoint.DisplayTest}; client/server
 * compatibility is now negotiated through the configuration-phase handshake.
 * The old {@code clientSideOnly} and {@code displayTest} metadata keys are still
 * understood when they are present (for example in legacy jars), but a mod
 * without an explicit declaration is conservatively treated as client-required.
 * That is the safe direction: a missing shared mod would otherwise be silently
 * left out of the client update.</p>
 */
public final class ModRequirementDetector {
    private ModRequirementDetector() {}

    public enum Requirement { REQUIRED, SERVER_ONLY, OPTIONAL, UNKNOWN }
    public enum Confidence { HIGH, MEDIUM, LOW }

    public static final class Result {
        public final Requirement requirement;
        public final Confidence confidence;
        public final String evidence;

        private Result(Requirement requirement, Confidence confidence, String evidence) {
            this.requirement = requirement;
            this.confidence = confidence;
            this.evidence = evidence;
        }

        public boolean isClientRequired() {
            return requirement == Requirement.REQUIRED || requirement == Requirement.UNKNOWN;
        }
    }

    private static final Pattern TOML_VALUE = Pattern.compile(
            "^\\s*(clientSideOnly|displayTest)\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|'([^']*)'|([^\\s#]+))\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    public static Result detect(IModInfo mod, Path jarPath) {
        Result loaded = detectLoadedMetadata(mod);
        if (loaded.requirement != Requirement.UNKNOWN) return loaded;

        Result jar = detectJarMetadata(jarPath);
        if (jar.requirement != Requirement.UNKNOWN) return jar;

        return new Result(Requirement.REQUIRED, Confidence.MEDIUM,
                "mod loaded on a dedicated server with no explicit client-requirement declaration");
    }

    public static Result detectJar(Path jarPath) {
        Result result = detectJarMetadata(jarPath);
        if (result.requirement != Requirement.UNKNOWN) return result;
        return new Result(Requirement.REQUIRED, Confidence.MEDIUM,
                "JAR has no explicit client-requirement declaration");
    }

    private static Result detectLoadedMetadata(IModInfo mod) {
        try {
            IConfigurable config = mod.getOwningFile().getConfig();

            Optional<Object> clientSideOnly = config.getConfigElement("clientSideOnly");
            if (clientSideOnly.isPresent() && Boolean.TRUE.equals(clientSideOnly.get())) {
                return new Result(Requirement.OPTIONAL, Confidence.HIGH,
                        "loaded metadata clientSideOnly=true");
            }

            Optional<Object> rawDisplayTest = config.getConfigElement("displayTest");
            if (rawDisplayTest.isPresent()) {
                return fromDisplayTest(String.valueOf(rawDisplayTest.get()), "loaded metadata");
            }
        } catch (Exception ignored) {}
        return unknown("no explicit loaded side metadata");
    }

    private static Result detectJarMetadata(Path jarPath) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
            String sourceName = "META-INF/neoforge.mods.toml";
            if (entry == null) {
                entry = zip.getEntry("META-INF/mods.toml");
                sourceName = "META-INF/mods.toml";
            }
            if (entry == null) return unknown("no mod metadata");

            try (InputStream input = zip.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String effective = stripTomlComment(line).trim();
                    if (effective.isEmpty()) continue;
                    Matcher matcher = TOML_VALUE.matcher(effective);
                    if (!matcher.matches()) continue;
                    String key = matcher.group(1);
                    String value = firstNonNull(matcher.group(2), matcher.group(3), matcher.group(4));
                    if ("clientSideOnly".equalsIgnoreCase(key) && "true".equalsIgnoreCase(value)) {
                        return new Result(Requirement.OPTIONAL, Confidence.HIGH,
                                "parsed " + sourceName + " clientSideOnly=true");
                    }
                    if ("displayTest".equalsIgnoreCase(key)) {
                        Result result = fromDisplayTest(value, "parsed " + sourceName);
                        if (result.requirement != Requirement.UNKNOWN) return result;
                    }
                }
            }
        } catch (Exception ignored) {}
        return unknown("no explicit parsed side metadata");
    }

    private static Result fromDisplayTest(String value, String source) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "IGNORE_SERVER_VERSION" -> new Result(Requirement.SERVER_ONLY, Confidence.HIGH,
                    source + " displayTest=IGNORE_SERVER_VERSION");
            case "IGNORE_ALL_VERSION" -> new Result(Requirement.OPTIONAL, Confidence.MEDIUM,
                    source + " displayTest=IGNORE_ALL_VERSION");
            case "MATCH_VERSION" -> new Result(Requirement.REQUIRED, Confidence.HIGH,
                    source + " displayTest=MATCH_VERSION");
            default -> unknown(source + " displayTest=" + normalized);
        };
    }

    private static String stripTomlComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inDouble) {
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble) return line.substring(0, i);
        }
        return line;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) if (value != null) return value;
        return "";
    }

    private static Result unknown(String evidence) {
        return new Result(Requirement.UNKNOWN, Confidence.LOW, evidence);
    }
}
