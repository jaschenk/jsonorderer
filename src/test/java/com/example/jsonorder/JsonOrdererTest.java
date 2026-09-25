package com.example.jsonorder;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONValue;
import net.minidev.json.parser.JSONParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonOrdererTest {

    private static Object parse(String json) throws Exception {
        return new JSONParser(JSONParser.MODE_PERMISSIVE).parse(json);
    }

    private static String compact(Object o) {
        return JSONValue.toJSONString(o);
    }

    @Test
    void alphabetical_descends_into_arrays() throws Exception {
        JSONObject in = (JSONObject) parse("""
            {"z":1,"a":[{"y":1,"x":[{"n":1,"m":2}]}, 5, "s"]}""");
        String out = compact(JsonOrderer.alphabetical().order(in));
        assertEquals("""
            {"a":[{"x":[{"m":2,"n":1}],"y":1},5,"s"],"z":1}""", out);
    }

    @Test
    void alphabetical_does_not_reorder_array_elements_by_default() throws Exception {
        JSONArray in = (JSONArray) parse("[3,1,2]");
        assertEquals("[3,1,2]", compact(JsonOrderer.alphabetical().order(in)));
    }

    @Test
    void explicit_keys_then_alphabetical_rest() throws Exception {
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$"
                keys: [id, name]
            """);
        JSONObject in = (JSONObject) parse("""
            {"zeta":1,"name":"n","alpha":2,"id":7}""");
        assertEquals("""
            {"id":7,"name":"n","alpha":2,"zeta":1}""", compact(new JsonOrderer(cfg).order(in)));
    }

    @Test
    void unlisted_original_preserves_parser_order_after_listed_keys() throws Exception {
        // JSONObject is a HashMap, so use a LinkedHashMap input to make "original" observable
        Map<String, Object> in = new java.util.LinkedHashMap<>();
        in.put("zeta", 1); in.put("name", "n"); in.put("alpha", 2); in.put("id", 7);
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$"
                keys: [id]
                unlistedKeys: original
            """);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) new JsonOrderer(cfg).order(in);
        assertEquals(List.of("id", "zeta", "name", "alpha"), List.copyOf(out.keySet()));
    }

    @Test
    void missing_listed_keys_are_skipped() throws Exception {
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$"
                keys: [id, doesNotExist, name]
            """);
        JSONObject in = (JSONObject) parse("""
            {"name":"n","id":7}""");
        assertEquals("""
            {"id":7,"name":"n"}""", compact(new JsonOrderer(cfg).order(in)));
    }

    @Test
    void array_element_rule_and_sortBy_multi_key_desc() throws Exception {
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$.readings"
                sortBy: [ts, reg]
                direction: desc
              - path: "$.readings[]"
                keys: [ts, reg, v]
            """);
        JSONObject in = (JSONObject) parse("""
            {"readings":[
              {"v":1,"reg":"R2","ts":"2025-09-01"},
              {"v":2,"reg":"R1","ts":"2025-09-02"},
              {"v":3,"reg":"R1","ts":"2025-09-01"},
              {"v":4,"reg":"R2","ts":"2025-09-02"}]}""");
        assertEquals("""
            {"readings":[{"ts":"2025-09-02","reg":"R2","v":4},{"ts":"2025-09-02","reg":"R1","v":2},{"ts":"2025-09-01","reg":"R2","v":1},{"ts":"2025-09-01","reg":"R1","v":3}]}""",
                compact(new JsonOrderer(cfg).order(in)));
    }

    @Test
    void sortBy_numeric_compares_numerically_not_lexically() throws Exception {
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$"
                sortBy: n
            """);
        JSONArray in = (JSONArray) parse("""
            [{"n":10},{"n":9},{"n":100},{"n":1.5}]""");
        assertEquals("""
            [{"n":1.5},{"n":9},{"n":10},{"n":100}]""", compact(new JsonOrderer(cfg).order(in)));
    }

    @Test
    void sortBy_supports_dotted_path_and_missing_values_first() throws Exception {
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$"
                sortBy: meter.serial
            """);
        JSONArray in = (JSONArray) parse("""
            [{"meter":{"serial":"B"}},{"other":1},{"meter":{"serial":"A"}}]""");
        assertEquals("""
            [{"other":1},{"meter":{"serial":"A"}},{"meter":{"serial":"B"}}]""", compact(new JsonOrderer(cfg).order(in)));
    }

    @Test
    void scalar_array_sorting_via_rule_and_via_default() throws Exception {
        JSONObject in = (JSONObject) parse("""
            {"tags":["b","c","a"],"nums":[3,1,2],"mixed":[true,"x",2,null]}""");

        OrderingConfig ruleCfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$.tags"
                sortElements: true
            """);
        assertEquals("""
            {"mixed":[true,"x",2,null],"nums":[3,1,2],"tags":["a","b","c"]}""",
                compact(new JsonOrderer(ruleCfg).order(in)));

        OrderingConfig dfltCfg = OrderingConfig.fromYaml("""
            defaults:
              sortScalarArrays: true
            rules:
              - path: "$.nums"
                sortElements: false
            """);
        assertEquals("""
            {"mixed":[null,true,2,"x"],"nums":[3,1,2],"tags":["a","b","c"]}""",
                compact(new JsonOrderer(dfltCfg).order(in)));
    }

    @Test
    void deep_wildcard_matches_any_depth_and_specific_rule_wins() throws Exception {
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$.**.address"
                keys: [line1, city, postcode]
              - path: "$.site.address"
                keys: [postcode, city, line1]
            """);
        JSONObject in = (JSONObject) parse("""
            {"address":{"postcode":"P","city":"C","line1":"L"},
             "customer":{"contacts":[{"address":{"postcode":"P","city":"C","line1":"L"}}]},
             "site":{"address":{"postcode":"P","city":"C","line1":"L"}}}""");
        assertEquals("""
            {"address":{"line1":"L","city":"C","postcode":"P"},"customer":{"contacts":[{"address":{"line1":"L","city":"C","postcode":"P"}}]},"site":{"address":{"postcode":"P","city":"C","line1":"L"}}}""",
                compact(new JsonOrderer(cfg).order(in)));
    }

    @Test
    void single_wildcard_matches_exactly_one_segment() {
        JsonPathPattern p = JsonPathPattern.parse("$.*.address");
        assertTrue(p.matches(List.of("site", "address")));
        assertTrue(p.matches(List.of("[]", "address")));
        assertFalse(p.matches(List.of("address")));
        assertFalse(p.matches(List.of("a", "b", "address")));
    }

    @Test
    void path_parsing_of_array_markers() {
        JsonPathPattern p = JsonPathPattern.parse("$.matrix[][].cell");
        assertTrue(p.matches(List.of("matrix", "[]", "[]", "cell")));
        assertFalse(p.matches(List.of("matrix", "[]", "cell")));

        assertTrue(JsonPathPattern.parse("$[]").matches(List.of("[]")));
        assertTrue(JsonPathPattern.parse("$").matches(List.of()));
        assertFalse(JsonPathPattern.parse("$").matches(List.of("x")));
    }

    @Test
    void case_insensitive_key_matching_and_sorting() throws Exception {
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            defaults:
              caseInsensitive: true
            rules:
              - path: "$"
                keys: [meterid]
            """);
        JSONObject in = (JSONObject) parse("""
            {"Zulu":1,"alpha":2,"MeterId":"M"}""");
        assertEquals("""
            {"MeterId":"M","alpha":2,"Zulu":1}""", compact(new JsonOrderer(cfg).order(in)));
    }

    @Test
    void later_rule_wins_on_equal_specificity() throws Exception {
        OrderingConfig cfg = OrderingConfig.fromYaml("""
            rules:
              - path: "$"
                keys: [a, b]
              - path: "$"
                keys: [b, a]
            """);
        JSONObject in = (JSONObject) parse("""
            {"a":1,"b":2}""");
        assertEquals("""
            {"b":2,"a":1}""", compact(new JsonOrderer(cfg).order(in)));
    }

    @Test
    void invalid_yaml_values_fail_fast() {
        assertThrows(IllegalArgumentException.class, () -> OrderingConfig.fromYaml("""
            rules:
              - path: "$"
                direction: sideways
            """));
        assertThrows(NullPointerException.class, () -> OrderingConfig.fromYaml("""
            rules:
              - keys: [a]
            """));
    }
}
