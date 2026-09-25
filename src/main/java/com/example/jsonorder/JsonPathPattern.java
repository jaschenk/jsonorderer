package com.example.jsonorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal glob-style matcher for the rule paths used in {@link OrderingConfig}.
 *
 * <ul>
 *   <li>{@code $}             – root</li>
 *   <li>{@code $.a.b}         – key {@code b} inside key {@code a}</li>
 *   <li>{@code $.a[]}         – every element of array {@code a}</li>
 *   <li>{@code $.a[].b}       – key {@code b} inside each element of array {@code a}</li>
 *   <li>{@code $.*}           – any single key or array element under root</li>
 *   <li>{@code $.**.address}  – key {@code address} at any depth (including directly under root)</li>
 *   <li>{@code $[]}           – elements of a root-level array</li>
 * </ul>
 *
 * The concrete path of a node is represented as a list of segments where a key is its
 * literal name and an array element is the marker {@link #ARRAY_ELEMENT}.
 */
final class JsonPathPattern {

    static final String ARRAY_ELEMENT = "[]";
    private static final String ANY = "*";
    private static final String DEEP = "**";

    private final String source;
    private final List<String> segments;
    private final int specificity;

    private JsonPathPattern(String source, List<String> segments) {
        this.source = source;
        this.segments = List.copyOf(segments);
        int score = 0;
        for (String s : segments) {
            if (DEEP.equals(s)) continue;          // contributes nothing
            score += ANY.equals(s) ? 1 : 3;         // literal / [] outrank wildcards
        }
        this.specificity = score;
    }

    static JsonPathPattern parse(String path) {
        String p = path == null ? "" : path.trim();
        if (p.isEmpty() || p.equals("$")) {
            return new JsonPathPattern(path, List.of());
        }
        if (p.startsWith("$.")) {
            p = p.substring(2);
        } else if (p.startsWith("$")) {
            p = p.substring(1);      // e.g. "$[]"
        }
        List<String> segs = new ArrayList<>();
        for (String raw : p.split("\\.")) {
            String tok = raw.trim();
            if (tok.isEmpty()) continue;
            // peel off trailing "[]" markers: "readings[]" -> "readings", "[]"; "matrix[][]" -> "matrix", "[]", "[]"
            int markers = 0;
            while (tok.endsWith(ARRAY_ELEMENT)) {
                tok = tok.substring(0, tok.length() - ARRAY_ELEMENT.length());
                markers++;
            }
            if (!tok.isEmpty()) segs.add(tok);
            for (int i = 0; i < markers; i++) segs.add(ARRAY_ELEMENT);
        }
        return new JsonPathPattern(path, segs);
    }

    /** Higher is more specific; ties are resolved by declaration order in the caller. */
    int specificity() {
        return specificity;
    }

    String source() {
        return source;
    }

    boolean matches(List<String> actual) {
        return match(0, 0, actual);
    }

    private boolean match(int pi, int ai, List<String> actual) {
        while (pi < segments.size()) {
            String pat = segments.get(pi);
            if (DEEP.equals(pat)) {
                // collapse consecutive ** and try every possible remaining suffix
                while (pi + 1 < segments.size() && DEEP.equals(segments.get(pi + 1))) pi++;
                if (pi == segments.size() - 1) return true; // trailing ** matches everything
                for (int k = ai; k <= actual.size(); k++) {
                    if (match(pi + 1, k, actual)) return true;
                }
                return false;
            }
            if (ai >= actual.size()) return false;
            String act = actual.get(ai);
            if (!ANY.equals(pat) && !pat.equals(act)) return false;
            pi++;
            ai++;
        }
        return ai == actual.size();
    }

    @Override
    public String toString() {
        return source;
    }
}
