package com.example.dataconnector.connector;

import com.example.dataconnector.schema.Column;
import com.example.dataconnector.schema.TableSchema;
import com.example.dataconnector.v1.ColumnType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Exposes every {@code *.json} file in a folder as a dataset. Each file must contain a JSON
 * array of flat objects, e.g. {@code [{"sku": "A1", "price": 9.99}, ...]}.
 *
 * <p>Nested objects/arrays are passed through as JSON text in a STRING column.
 */
public final class JsonConnector implements DataConnector {

  private final String id;
  private final Path directory;

  public JsonConnector(String id, Path directory) {
    this.id = id;
    this.directory = directory;
    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("JSON directory does not exist: " + directory.toAbsolutePath());
    }
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String type() {
    return "json";
  }

  @Override
  public String description() {
    return "JSON documents in " + directory;
  }

  @Override
  public List<String> listDatasets() {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.toLowerCase().endsWith(".json"))
          .map(n -> n.substring(0, n.length() - 5))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new ConnectorException("Cannot list " + directory, e);
    }
  }

  @Override
  public TableSchema schema(String dataset) {
    Map<String, ColumnType> types = new LinkedHashMap<>();
    Map<String, Boolean> nullable = new LinkedHashMap<>();
    List<JsonObject> objects = load(dataset);

    for (JsonObject obj : objects) {
      for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
        types.merge(e.getKey(), typeOf(e.getValue()), TypeInference::widen);
        if (e.getValue().isJsonNull()) {
          nullable.put(e.getKey(), true);
        }
      }
    }
    // A key missing from some objects is also nullable.
    for (JsonObject obj : objects) {
      types.keySet().forEach(k -> { if (!obj.has(k)) nullable.put(k, true); });
    }
    List<Column> columns = new ArrayList<>();
    types.forEach(
        (name, t) ->
            columns.add(
                new Column(
                    name,
                    t == ColumnType.COLUMN_TYPE_UNSPECIFIED ? ColumnType.COLUMN_TYPE_STRING : t,
                    nullable.getOrDefault(name, false))));
    return new TableSchema(id, dataset, columns);
  }

  @Override
  public Stream<Map<String, Object>> read(String dataset) {
    TableSchema schema = schema(dataset);
    return load(dataset).stream()
        .map(
            obj -> {
              Map<String, Object> row = new LinkedHashMap<>();
              for (Column c : schema.columns()) {
                row.put(c.name(), toJava(obj.get(c.name()), c.type()));
              }
              return row;
            });
  }

  private List<JsonObject> load(String dataset) {
    Path file = directory.resolve(dataset + ".json").normalize();
    if (!file.startsWith(directory.normalize()) || !Files.isRegularFile(file)) {
      throw new DatasetNotFoundException(id, dataset);
    }
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      JsonElement root = JsonParser.parseReader(reader);
      if (!root.isJsonArray()) {
        throw new ConnectorException(dataset + ".json must contain a JSON array", null);
      }
      List<JsonObject> out = new ArrayList<>();
      for (JsonElement el : (JsonArray) root) {
        if (el.isJsonObject()) {
          out.add(el.getAsJsonObject());
        }
      }
      return out;
    } catch (IOException e) {
      throw new ConnectorException("Cannot read " + file, e);
    }
  }

  private static ColumnType typeOf(JsonElement el) {
    if (el == null || el.isJsonNull()) {
      return ColumnType.COLUMN_TYPE_UNSPECIFIED;
    }
    if (el.isJsonPrimitive()) {
      JsonPrimitive p = el.getAsJsonPrimitive();
      if (p.isBoolean()) {
        return ColumnType.COLUMN_TYPE_BOOL;
      }
      if (p.isNumber()) {
        return TypeInference.classify(p.getAsString());
      }
    }
    return ColumnType.COLUMN_TYPE_STRING;
  }

  private static Object toJava(JsonElement el, ColumnType type) {
    if (el == null || el.isJsonNull()) {
      return null;
    }
    if (!el.isJsonPrimitive()) {
      return el.toString(); // nested JSON kept as text
    }
    return switch (type) {
      case COLUMN_TYPE_INT64 -> el.getAsLong();
      case COLUMN_TYPE_DOUBLE -> el.getAsDouble();
      case COLUMN_TYPE_BOOL -> el.getAsBoolean();
      default -> el.getAsString();
    };
  }
}
