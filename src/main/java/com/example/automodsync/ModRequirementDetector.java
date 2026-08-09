package com.example.automodsync;

import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModInfo;

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
 * on connecting clients. DisplayTest predicates are deliberately not executed:
 * they describe server-list compatibility and are not a reliable side detector.
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
        Result runtime = detectRuntime(mod);
        if (runtime.requirement == Requirement.SERVER_ONLY || runtime.requirement == Requirement.OPTIONAL) return runtime;

        Result loaded = detectLoadedMetadata(mod);
        if (loaded.requirement != Requirement.UNKNOWN) return loaded;

        Result jar = detectJarMetadata(jarPath);
        if (jar.requirement != Requirement.UNKNOWN) return jar;

        // A mod that is loaded by a dedicated server is client-required unless
        // it explicitly advertises otherwise. This is safer than silently
        // omitting a genuinely shared mod from a client update.
        return new Result(Requirement.REQUIRED, Confidence.MEDIUM,
                "dedicated server mod with no explicit remote-ignore declaration");
    }

    public static Result detectJar(Path jarPath) {
        Result result = detectJarMetadata(jarPath);
        if (result.requirement != Requirement.UNKNOWN) return result;
        return new Result(Requirement.REQUIRED, Confidence.MEDIUM,
                "JAR has no explicit remote-ignore declaration");
    }

    private static Result detectRuntime(IModInfo mod) {
        try {
            Optional<? extends ModContainer> container = ModList.get().getModContainerById(mod.getModId());
            if (container.isEmpty()) return unknown("no runtime ModContainer");
            Optional<IExtensionPoint.DisplayTest> extension =
                    container.get().getCustomExtension(IExtensionPoint.DisplayTest.class);
            if (extension.isEmpty()) return unknown("no runtime DisplayTest");

            Object supplied = extension.get().suppliedVersion().get();
            if (IExtensionPoint.DisplayTest.IGNORESERVERONLY.equals(String.valueOf(supplied))) {
                return new Result(Requirement.SERVER_ONLY, Confidence.HIGH,
                        "runtime DisplayTest supplied SERVER_ONLY");
            }
            if (String.valueOf(supplied).isEmpty()) {
                return new Result(Requirement.OPTIONAL, Confidence.MEDIUM,
                        "runtime DisplayTest supplied IGNORE_ALL marker");
            }
            return unknown("custom DisplayTest without SERVER_ONLY marker");
        } catch (Exception ignored) {
            return unknown("runtime DisplayTest unavailable");
        }
    }

    private static Result detectLoadedMetadata(IModInfo mod) {
        try {
            var config = mod.getOwningFile().getFile().getModFileInfo().getConfig();
            boolean clientSideOnly = config.getConfigElement("clientSideOnly")
                    .map(Boolean.TRUE::equals).orElse(false);
            if (clientSideOnly) {
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
            ZipEntry entry = zip.getEntry("META-INF/mods.toml");
            if (entry == null) return unknown("no META-INF/mods.toml");
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
                                "parsed mods.toml clientSideOnly=true");
                    }
                    if ("displayTest".equalsIgnoreCase(key)) {
                        Result result = fromDisplayTest(value, "parsed mods.toml");
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
