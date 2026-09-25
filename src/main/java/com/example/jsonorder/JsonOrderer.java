package com.example.jsonorder;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Recursively re-orders a json-smart {@link JSONObject} / {@link JSONArray} tree.
 *
 * <p>Replaces the classic {@code jsonObjectToTreeMap} helper with three improvements:
 * <ol>
 *   <li>Descends into {@link JSONArray}s (and any {@link List}), so objects nested inside arrays are ordered too.</li>
 *   <li>Optionally sorts array elements – objects by one or more keys, scalars by natural order.</li>
 *   <li>Key order can be driven by a YAML {@link OrderingConfig}; anything not covered by a rule falls back to
 *       alphabetical (or original) order, so the result is always deterministic.</li>
 * </ol>
 *
 * <p>The returned structure uses {@link LinkedHashMap} for objects (to preserve the chosen order – a
 * {@code TreeMap} could only ever be alphabetical) and {@link JSONArray} for arrays. Because {@code JSONObject}
 * extends {@code HashMap}, feeding the result back into json-smart's {@code JSONValue.toJSONString(Map)} retains
 * the order.
 */
public final class JsonOrderer {

    private final OrderingConfig config;
    private final List<CompiledRule> rules;

    private record CompiledRule(JsonPathPattern pattern, OrderingConfig.Rule rule, int index) {}

    public JsonOrderer(OrderingConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        List<CompiledRule> compiled = new ArrayList<>();
        int i = 0;
        for (OrderingConfig.Rule r : config.rules()) {
            compiled.add(new CompiledRule(JsonPathPattern.parse(r.path()), r, i++));
        }
        // most specific first; later declaration wins among equals
        compiled.sort(Comparator.comparingInt((CompiledRule c) -> c.pattern().specificity()).reversed()
                .thenComparing(Comparator.comparingInt(CompiledRule::index).reversed()));
        this.rules = List.copyOf(compiled);
    }

    /** Alphabetical everywhere – the behaviour of the original method, extended into arrays. */
    public static JsonOrderer alphabetical() {
        return new JsonOrderer(OrderingConfig.alphabetical());
    }

    // ------------------------------------------------------------------ public API

    /** Drop-in replacement for the old {@code jsonObjectToTreeMap}. */
    public LinkedHashMap<String, Object> order(JSONObject jsonObject) {
        return orderObject(jsonObject, new ArrayList<>());
    }

    /** Order a root-level array. */
    public JSONArray order(JSONArray jsonArray) {
        return orderArray(jsonArray, new ArrayList<>());
    }

    /** Order whatever the parser returned (object, array or scalar). */
    public Object order(Object parsed) {
        return orderValue(parsed, new ArrayList<>());
    }

    /**
     * Convenience: keep the concrete {@link JSONObject} type. Note that {@code JSONObject} is a {@code HashMap}
     * subclass, so its own iteration order is <em>not</em> stable – prefer {@link #order(JSONObject)} and serialise
     * the returned {@code LinkedHashMap} via {@code JSONValue.toJSONString(map)} / {@code JSONObject.toJSONString(map)}.
     */
    public String toJsonString(JSONObject jsonObject) {
        return JSONObject.toJSONString(order(jsonObject));
    }

    // ------------------------------------------------------------------ recursion

    @SuppressWarnings("unchecked")
    private Object orderValue(Object value, List<String> path) {
        if (value instanceof Map<?, ?> m) {
            return orderObject((Map<String, Object>) m, path);
        }
        if (value instanceof List<?> l) {
            return orderArray((List<Object>) l, path);
        }
        return value;
    }

    private LinkedHashMap<String, Object> orderObject(Map<String, Object> obj, List<String> path) {
        OrderingConfig.Rule rule = findRule(path);
        LinkedHashMap<String, Object> out = new LinkedHashMap<>(Math.max(16, obj.size() * 2));

        Set<String> emitted = new LinkedHashSet<>();
        if (rule != null) {
            for (String key : rule.keys()) {
                String actual = resolveKey(obj, key);
                if (actual != null && emitted.add(actual)) {
                    out.put(actual, recurseChild(obj.get(actual), path, actual));
                }
            }
        }

        OrderingConfig.Unlisted unlisted = rule != null && rule.unlistedKeys() != null
                ? rule.unlistedKeys() : config.defaults().unlistedKeys();

        List<String> remaining = new ArrayList<>();
        for (String k : obj.keySet()) {
            if (!emitted.contains(k)) remaining.add(k);
        }
        if (unlisted == OrderingConfig.Unlisted.ALPHABETICAL) {
            remaining.sort(config.defaults().caseInsensitive()
                    ? String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())
                    : Comparator.naturalOrder());
        }
        for (String k : remaining) {
            out.put(k, recurseChild(obj.get(k), path, k));
        }
        return out;
    }

    private Object recurseChild(Object child, List<String> parentPath, String segment) {
        parentPath.add(segment);
        try {
            return orderValue(child, parentPath);
        } finally {
            parentPath.remove(parentPath.size() - 1);
        }
    }

    private JSONArray orderArray(List<Object> arr, List<String> path) {
        // 1. recurse into elements first (so sort keys are looked up on already-normalised maps)
        JSONArray out = new JSONArray();
        for (Object element : arr) {
            out.add(recurseChild(element, path, JsonPathPattern.ARRAY_ELEMENT));
        }

        // 2. optionally sort the elements themselves
        OrderingConfig.Rule rule = findRule(path);
        if (rule != null && !rule.sortBy().isEmpty()) {
            Comparator<Object> cmp = byKeys(rule.sortBy());
            if (rule.direction() == OrderingConfig.Direction.DESC) cmp = cmp.reversed();
            out.sort(cmp);
        } else {
            boolean sortScalars = rule != null && rule.sortElements() != null
                    ? rule.sortElements() : config.defaults().sortScalarArrays();
            if (sortScalars && allScalars(out)) {
                out.sort(SCALAR_COMPARATOR);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ rule lookup

    private OrderingConfig.Rule findRule(List<String> path) {
        for (CompiledRule c : rules) {
            if (c.pattern().matches(path)) return c.rule();
        }
        return null;
    }

    private String resolveKey(Map<String, Object> obj, String key) {
        if (obj.containsKey(key)) return key;
        if (config.defaults().caseInsensitive()) {
            for (String k : obj.keySet()) {
                if (k.equalsIgnoreCase(key)) return k;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ comparators

    private static boolean allScalars(List<?> list) {
        for (Object o : list) {
            if (o instanceof Map || o instanceof List) return false;
        }
        return true;
    }

    /** Null-safe, type-aware comparator for JSON scalars: nulls first, numbers numerically, then everything by string. */
    static final Comparator<Object> SCALAR_COMPARATOR = (a, b) -> {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            return toBigDecimal(na).compareTo(toBigDecimal(nb));
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba.compareTo(bb);
        }
        // mixed / string: group by type rank so ordering is total, then compare textual form
        int ra = rank(a), rb = rank(b);
        if (ra != rb) return Integer.compare(ra, rb);
        return a.toString().compareTo(b.toString());
    };

    private static int rank(Object o) {
        if (o instanceof Boolean) return 0;
        if (o instanceof Number) return 1;
        if (o instanceof String) return 2;
        return 3;
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal bd) return bd;
        if (n instanceof Double || n instanceof Float) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(n.toString());
    }

    /**
     * Compares array elements by the given keys in turn. Elements that are not objects (or lack the key) sort
     * before those that have it, keeping the comparator total.
     */
    private static Comparator<Object> byKeys(List<String> keys) {
        Comparator<Object> cmp = null;
        for (String key : keys) {
            Comparator<Object> c = Comparator.comparing(o -> extract(o, key), SCALAR_COMPARATOR);
            cmp = cmp == null ? c : cmp.thenComparing(c);
        }
        return cmp;
    }

    /** Supports dotted sub-paths, e.g. {@code sortBy: meter.serial}. */
    private static Object extract(Object element, String dottedKey) {
        Object cur = element;
        for (String part : dottedKey.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part);
        }
        // nested containers can't be compared meaningfully – treat as their JSON text for stability
        if (cur instanceof Map || cur instanceof List) return cur.toString();
        return cur;
    }
}
