package com.example.jsonorder;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable ordering configuration, normally loaded from a small YAML document:
 *
 * <pre>
 * defaults:
 *   unlistedKeys: alphabetical      # alphabetical | original
 *   caseInsensitive: false
 *   sortScalarArrays: false
 *
 * rules:
 *   - path: "$"                     # root object
 *     keys: [meterId, serviceAddress, readings]
 *   - path: "$.readings"            # the array itself -> sort its elements
 *     sortBy: timestamp             # string or list of strings (tie-breakers)
 *     direction: asc                # asc | desc
 *   - path: "$.readings[]"          # each element of the array
 *     keys: [timestamp, kwh, quality]
 *   - path: "$.**.address"          # 'address' object at any depth
 *     keys: [line1, city, postcode]
 *   - path: "$.tags"
 *     sortElements: true            # scalar array -> sort values
 * </pre>
 *
 * Path grammar: segments separated by '.', array element marked by '[]',
 * '*' matches any single segment (key or []), '**' matches zero or more segments.
 */
public record OrderingConfig(Defaults defaults, List<Rule> rules) {

    public enum Unlisted { ALPHABETICAL, ORIGINAL }
    public enum Direction { ASC, DESC }

    public record Defaults(Unlisted unlistedKeys, boolean caseInsensitive, boolean sortScalarArrays) {
        public static final Defaults STANDARD = new Defaults(Unlisted.ALPHABETICAL, false, false);
    }

    public record Rule(String path,
                       List<String> keys,          // explicit key order for objects (may be empty)
                       Unlisted unlistedKeys,      // per-rule override, null -> defaults
                       List<String> sortBy,        // for arrays of objects (may be empty)
                       Direction direction,
                       Boolean sortElements) {     // for scalar arrays, null -> defaults

        public Rule {
            Objects.requireNonNull(path, "rule.path is required");
            keys = keys == null ? List.of() : List.copyOf(keys);
            sortBy = sortBy == null ? List.of() : List.copyOf(sortBy);
            direction = direction == null ? Direction.ASC : direction;
        }
    }

    public OrderingConfig {
        defaults = defaults == null ? Defaults.STANDARD : defaults;
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** Pure recursive alphabetical ordering, no rules – the behaviour of the original TreeMap approach. */
    public static OrderingConfig alphabetical() {
        return new OrderingConfig(Defaults.STANDARD, List.of());
    }

    // ------------------------------------------------------------------ loading

    public static OrderingConfig fromYaml(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file)) {
            return fromYaml(r);
        }
    }

    public static OrderingConfig fromYaml(InputStream in) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        return fromMap(yaml.load(in));
    }

    public static OrderingConfig fromYaml(String yamlText) {
        return fromYaml(new StringReader(yamlText));
    }

    public static OrderingConfig fromYaml(Reader reader) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        return fromMap(yaml.load(reader));
    }

    /** Build from an already-parsed generic map (lets callers swap in any YAML/JSON/properties source). */
    @SuppressWarnings("unchecked")
    public static OrderingConfig fromMap(Map<String, Object> root) {
        if (root == null) return alphabetical();

        Defaults defaults = Defaults.STANDARD;
        Object d = root.get("defaults");
        if (d instanceof Map<?, ?> dm) {
            defaults = new Defaults(
                    parseUnlisted(dm.get("unlistedKeys"), Unlisted.ALPHABETICAL),
                    bool(dm.get("caseInsensitive"), false),
                    bool(dm.get("sortScalarArrays"), false));
        }

        List<Rule> rules = new ArrayList<>();
        Object r = root.get("rules");
        if (r instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> rm)) {
                    throw new IllegalArgumentException("Each rule must be a mapping, got: " + item);
                }
                rules.add(new Rule(
                        str(rm.get("path")),
                        strList(rm.get("keys")),
                        rm.containsKey("unlistedKeys") ? parseUnlisted(rm.get("unlistedKeys"), null) : null,
                        strList(rm.get("sortBy")),
                        parseDirection(rm.get("direction")),
                        rm.containsKey("sortElements") ? bool(rm.get("sortElements"), false) : null));
            }
        } else if (r != null) {
            throw new IllegalArgumentException("'rules' must be a list");
        }
        return new OrderingConfig(defaults, Collections.unmodifiableList(rules));
    }

    // ------------------------------------------------------------------ helpers

    private static Unlisted parseUnlisted(Object v, Unlisted dflt) {
        if (v == null) return dflt;
        return Unlisted.valueOf(v.toString().trim().toUpperCase(Locale.ROOT));
    }

    private static Direction parseDirection(Object v) {
        if (v == null) return Direction.ASC;
        return Direction.valueOf(v.toString().trim().toUpperCase(Locale.ROOT));
    }

    private static boolean bool(Object v, boolean dflt) {
        if (v == null) return dflt;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static List<String> strList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) out.add(String.valueOf(o));
            return out;
        }
        return List.of(v.toString()); // allow a single scalar, e.g. sortBy: timestamp
    }
}
