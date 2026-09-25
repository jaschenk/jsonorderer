package com.example.jsonorder;

import net.minidev.json.JSONObject;
import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;

import java.nio.file.Files;
import java.nio.file.Path;

public class Demo {

    private static final String INPUT = """
        {
          "tags": ["smart", "AMI", "commercial", "3-phase"],
          "readings": [
            {"value": 1520.25, "registerId": "R2", "timestamp": "2025-09-01T00:00:00Z", "quality": "ESTIMATED"},
            {"value": 40210.5, "registerId": "R1", "timestamp": "2025-09-02T00:00:00Z", "quality": "ACTUAL"},
            {"value": 40100.0, "registerId": "R1", "timestamp": "2025-09-01T00:00:00Z", "quality": "ACTUAL"},
            {"value": 1533.75, "registerId": "R2", "timestamp": "2025-09-02T00:00:00Z", "quality": "ACTUAL"}
          ],
          "registers": [
            {"unit": "kVArh", "registerId": "R2", "multiplier": 1, "note": "reactive"},
            {"unit": "kWh",   "registerId": "R1", "multiplier": 10}
          ],
          "serviceAddress": {"postcode": "SW1A 1AA", "city": "London", "line1": "1 Grid Way", "line2": "Substation B"},
          "meterId": "MTR-000451",
          "customer": {
            "name": "Acme Foundry",
            "serviceAddress": {"postcode": "M1 1AA", "line1": "9 Furnace Rd", "city": "Manchester"}
          }
        }
        """;

    public static void main(String[] args) throws Exception {
        JSONObject json = parse(INPUT);

        System.out.println("=== 1. Original TreeMap approach (arrays NOT descended) ===");
        System.out.println(pretty(legacyTreeMap(json)));

        System.out.println("\n=== 2. JsonOrderer.alphabetical() – recursive incl. arrays ===");
        System.out.println(pretty(JsonOrderer.alphabetical().order(json)));

        System.out.println("\n=== 3. JsonOrderer with YAML rules ===");
        Path yaml = Path.of(args.length > 0 ? args[0] : "src/main/resources/json-order.yaml");
        OrderingConfig cfg = OrderingConfig.fromYaml(Files.readString(yaml));
        JsonOrderer orderer = new JsonOrderer(cfg);
        System.out.println(pretty(orderer.order(json)));
    }

    // the user's existing method, kept for side-by-side comparison
    protected static java.util.TreeMap<String, Object> legacyTreeMap(JSONObject jsonObject) {
        java.util.TreeMap<String, Object> treeMap = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, Object> entry : jsonObject.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof JSONObject) {
                value = legacyTreeMap((JSONObject) value);
            }
            treeMap.put(entry.getKey(), value);
        }
        return treeMap;
    }

    static JSONObject parse(String text) throws ParseException {
        return (JSONObject) new JSONParser(JSONParser.MODE_PERMISSIVE).parse(text);
    }

    /** json-smart has no pretty printer; tiny indenter over its compact output for readability only. */
    static String pretty(Object value) {
        String compact = JSONValue.toJSONString(value, JSONStyle.NO_COMPRESS);
        StringBuilder sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c == '"' && (i == 0 || compact.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) { sb.append(c); continue; }
            switch (c) {
                case '{', '[' -> { sb.append(c).append('\n'); indent++; sb.append("  ".repeat(indent)); }
                case '}', ']' -> { sb.append('\n'); indent--; sb.append("  ".repeat(indent)).append(c); }
                case ',' -> sb.append(c).append('\n').append("  ".repeat(indent));
                case ':' -> sb.append(": ");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
