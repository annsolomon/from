package in.dexter.contracts;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The JSON Schema subset our contracts use (type incl. type arrays, required, properties,
 * additionalProperties=false, format uuid/date-time, pattern, minimum, minLength/maxLength, items,
 * enum). Unknown keywords fail loudly, so a schema can never silently rely on something unchecked.
 */
final class MiniSchema {
  private static final Set<String> KNOWN = Set.of("$schema", "$id", "title", "description", "type", "required",
      "properties", "additionalProperties", "format", "pattern", "minimum", "maxLength", "minLength", "items", "enum");

  private MiniSchema() {}

  static List<String> validate(JsonNode schema, JsonNode value) {
    List<String> errors = new ArrayList<>();
    check(schema, value, "$", errors);
    return errors;
  }

  private static void check(JsonNode schema, JsonNode v, String path, List<String> errors) {
    for (String k : schema.propertyNames()) {
      if (!KNOWN.contains(k)) {
        throw new IllegalArgumentException("unsupported schema keyword " + k + " at " + path);
      }
    }
    JsonNode type = schema.get("type");
    if (type != null) {
      Set<String> allowed = new HashSet<>();
      if (type.isArray()) {
        type.forEach(t -> allowed.add(t.stringValue()));
      } else {
        allowed.add(type.stringValue());
      }
      String actual = typeOf(v);
      boolean ok = allowed.contains(actual) || (actual.equals("integer") && allowed.contains("number"));
      if (!ok) {
        errors.add(path + ": expected " + allowed + " but was " + actual);
        return;
      }
    }
    if (v.isNull()) {
      return;
    }
    if (schema.has("enum")) {
      boolean found = false;
      for (JsonNode e : schema.get("enum")) {
        found |= e.equals(v);
      }
      if (!found) {
        errors.add(path + ": not in enum");
      }
    }
    if (v.isString()) {
      String s = v.stringValue();
      if (schema.has("minLength") && s.length() < schema.get("minLength").intValue()) {
        errors.add(path + ": shorter than minLength");
      }
      if (schema.has("maxLength") && s.length() > schema.get("maxLength").intValue()) {
        errors.add(path + ": longer than maxLength");
      }
      if (schema.has("pattern") && !Pattern.compile(schema.get("pattern").stringValue()).matcher(s).find()) {
        errors.add(path + ": does not match pattern");
      }
      if (schema.has("format")) {
        String f = schema.get("format").stringValue();
        try {
          switch (f) {
            case "uuid" -> {
              if (!UUID.fromString(s).toString().equals(s)) {
                errors.add(path + ": not a canonical uuid");
              }
            }
            case "date-time" -> Instant.parse(s);
            default -> throw new IllegalArgumentException("unsupported format " + f);
          }
        } catch (IllegalArgumentException | DateTimeParseException e) {
          if (e.getMessage() != null && e.getMessage().startsWith("unsupported format")) {
            throw e;
          }
          errors.add(path + ": bad " + f);
        }
      }
    }
    if (v.isNumber() && schema.has("minimum") && v.longValue() < schema.get("minimum").longValue()) {
      errors.add(path + ": below minimum");
    }
    if (v.isObject()) {
      JsonNode props = schema.get("properties");
      if (schema.has("required")) {
        for (JsonNode r : schema.get("required")) {
          if (!v.has(r.stringValue())) {
            errors.add(path + ": missing required " + r.stringValue());
          }
        }
      }
      for (Map.Entry<String, JsonNode> e : v.properties()) {
        JsonNode sub = props == null ? null : props.get(e.getKey());
        if (sub == null) {
          JsonNode ap = schema.get("additionalProperties");
          if (ap != null && ap.isBoolean() && !ap.booleanValue()) {
            errors.add(path + ": unexpected property " + e.getKey());
          }
        } else {
          check(sub, e.getValue(), path + "." + e.getKey(), errors);
        }
      }
    }
    if (v.isArray() && schema.has("items")) {
      int i = 0;
      for (JsonNode item : v) {
        check(schema.get("items"), item, path + "[" + i++ + "]", errors);
      }
    }
  }

  static String typeOf(JsonNode v) {
    if (v.isNull()) {
      return "null";
    }
    if (v.isObject()) {
      return "object";
    }
    if (v.isArray()) {
      return "array";
    }
    if (v.isString()) {
      return "string";
    }
    if (v.isBoolean()) {
      return "boolean";
    }
    if (v.isIntegralNumber()) {
      return "integer";
    }
    if (v.isNumber()) {
      return "number";
    }
    return "unknown";
  }
}
