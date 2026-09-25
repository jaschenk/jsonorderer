# json-order

Deterministic, config-driven ordering of `net.minidev.json` (json-smart 2.6.0) trees on JDK 25.

## Why the original `jsonObjectToTreeMap` fell short

```java
if (value instanceof JSONObject) value = jsonObjectToTreeMap((JSONObject) value);
```

Only `JSONObject` children were recursed. A `JSONArray` (which is an `ArrayList`) was copied by
reference, so objects nested inside arrays kept the parser's `HashMap` order. In addition, a `TreeMap`
can only ever give alphabetical order, so there was no way to express "id first, then name, then the rest".

## What `JsonOrderer` does

* Recurses into **both** `Map` and `List` values, at any depth.
* Returns `LinkedHashMap` for objects (order is preserved by json-smart's serializer) and `JSONArray` for arrays.
* With no rules it is purely alphabetical: `JsonOrderer.alphabetical().order(jsonObject)` is a drop-in
  replacement for the old method, extended into arrays.
* With a YAML `OrderingConfig` you can pin key order per path, sort arrays of objects by one or more keys,
  and sort scalar arrays – no POJO annotations required.

## Usage

```java
OrderingConfig cfg = OrderingConfig.fromYaml(Path.of("json-order.yaml"));
JsonOrderer orderer = new JsonOrderer(cfg);            // thread-safe, build once

JSONObject parsed = (JSONObject) JSONValue.parse(text);
Map<String, Object> ordered = orderer.order(parsed);
String json = JSONValue.toJSONString(ordered);         // keys appear in the configured order
```

`OrderingConfig.fromMap(Map)` is also available if you already parse YAML with Jackson or another library.

## YAML reference

```yaml
defaults:
  unlistedKeys: alphabetical      # alphabetical | original  – keys not named in a rule
  caseInsensitive: false          # match `keys` and sort unlisted keys ignoring case
  sortScalarArrays: false         # sort every scalar array by value unless a rule says otherwise

rules:
  - path: "$"                     # root
    keys: [meterId, serviceAddress, readings]
  - path: "$.readings"            # the array node → sort its elements
    sortBy: [timestamp, registerId]   # tie-breakers in order; dotted paths allowed (meter.serial)
    direction: desc               # asc | desc
  - path: "$.readings[]"          # each element of the array → key order
    keys: [timestamp, registerId, value]
    unlistedKeys: original        # per-rule override
  - path: "$.**.address"          # any `address` object at any depth
    keys: [line1, city, postcode]
  - path: "$.tags"
    sortElements: true            # scalar array → sort values
```

### Path grammar

| Pattern | Matches |
|---|---|
| `$` | the root node |
| `$.a.b` | key `b` inside key `a` |
| `$.a[]` | every element of array `a` |
| `$.a[].b` | key `b` inside each element of `a` |
| `$[]` | elements of a root-level array |
| `$.*` | any single key or element |
| `$.**.x` | key `x` at any depth (`**` = zero or more segments) |

Rule resolution: the most specific matching rule wins (literal segments and `[]` score 3, `*` scores 1,
`**` scores 0); among equals the **last declared** wins. Keys listed in a rule but absent from the data are
skipped silently.

### Sort semantics

`sortBy` / `sortElements` use a total, null-safe comparator: `null` first, then booleans, then numbers
(compared numerically via `BigDecimal`, so `9 < 10 < 100`), then strings, then anything else by its JSON text.
Elements missing the sort key sort first.

## Build & test

```
mvn test
mvn exec:java -Dexec.mainClass=com.example.jsonorder.Demo   # or run Demo from your IDE
```
