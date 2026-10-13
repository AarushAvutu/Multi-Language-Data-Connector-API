package com.example.dataconnector.connector;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates connectors from a JSON config file such as {@code config/connectors.json}:
 *
 * <pre>
 * [
 *   {"id": "crm-files", "type": "csv",  "path": "data/csv"},
 *   {"id": "sales-db",  "type": "jdbc", "url": "jdbc:h2:mem:sales;DB_CLOSE_DELAY=-1",
 *    "initScript": "data/sql/seed.sql"}
 * ]
 * </pre>
 *
 * Relative paths are resolved against {@code baseDir}. To add a new connector type, add a
 * {@code case} to {@link #create}.
 */
public final class ConnectorFactory {

  private ConnectorFactory() {}

  public static ConnectorRegistry load(Path configFile, Path baseDir) throws IOException {
    ConnectorRegistry registry = new ConnectorRegistry();
    try (Reader reader = Files.newBufferedReader(configFile)) {
      JsonArray entries = JsonParser.parseReader(reader).getAsJsonArray();
      for (JsonElement el : entries) {
        registry.register(create(el.getAsJsonObject(), baseDir));
      }
    }
    return registry;
  }

  public static DataConnector create(JsonObject cfg, Path baseDir) {
    String id = required(cfg, "id");
    String type = required(cfg, "type");
    return switch (type) {
      case "csv" -> new CsvConnector(id, baseDir.resolve(required(cfg, "path")));
      case "json" -> new JsonConnector(id, baseDir.resolve(required(cfg, "path")));
      case "jdbc" ->
          new JdbcConnector(
              id,
              required(cfg, "url"),
              optional(cfg, "user"),
              optional(cfg, "password"),
              optional(cfg, "schema"),
              optional(cfg, "initScript") == null ? null : baseDir.resolve(optional(cfg, "initScript")));
      default -> throw new IllegalArgumentException("Unknown connector type '" + type + "' for " + id);
    };
  }

  private static String required(JsonObject cfg, String key) {
    String v = optional(cfg, key);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException("Connector config is missing '" + key + "': " + cfg);
    }
    return v;
  }

  private static String optional(JsonObject cfg, String key) {
    return cfg.has(key) && !cfg.get(key).isJsonNull() ? cfg.get(key).getAsString() : null;
  }
}
