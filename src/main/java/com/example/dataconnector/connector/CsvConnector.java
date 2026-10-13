package com.example.dataconnector.connector;

import com.example.dataconnector.schema.Column;
import com.example.dataconnector.schema.TableSchema;
import com.example.dataconnector.v1.ColumnType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Exposes every {@code *.csv} file in a folder as a dataset. {@code customers.csv} becomes
 * dataset {@code customers}.
 *
 * <p>The first line is the header. Column types are inferred by scanning the values.
 */
public final class CsvConnector implements DataConnector {

  private final String id;
  private final Path directory;

  public CsvConnector(String id, Path directory) {
    this.id = id;
    this.directory = directory;
    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("CSV directory does not exist: " + directory.toAbsolutePath());
    }
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String type() {
    return "csv";
  }

  @Override
  public String description() {
    return "CSV files in " + directory;
  }

  @Override
  public List<String> listDatasets() {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .map(p -> p.getFileName().toString())
          .filter(n -> n.toLowerCase().endsWith(".csv"))
          .map(n -> n.substring(0, n.length() - 4))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new ConnectorException("Cannot list " + directory, e);
    }
  }

  @Override
  public TableSchema schema(String dataset) {
    List<List<String>> lines = readAll(dataset);
    List<String> header = lines.get(0);
    ColumnType[] types = new ColumnType[header.size()];
    boolean[] nullable = new boolean[header.size()];
    Arrays.fill(types, ColumnType.COLUMN_TYPE_UNSPECIFIED);

    for (List<String> row : lines.subList(1, lines.size())) {
      for (int i = 0; i < header.size(); i++) {
        String value = i < row.size() ? row.get(i) : "";
        if (value.isBlank()) {
          nullable[i] = true;
        } else {
          types[i] = TypeInference.widen(types[i], TypeInference.classify(value));
        }
      }
    }
    List<Column> columns = new ArrayList<>();
    for (int i = 0; i < header.size(); i++) {
      ColumnType t = types[i] == ColumnType.COLUMN_TYPE_UNSPECIFIED ? ColumnType.COLUMN_TYPE_STRING : types[i];
      columns.add(new Column(header.get(i).trim(), t, nullable[i]));
    }
    return new TableSchema(id, dataset, columns);
  }

  @Override
  public Stream<Map<String, Object>> read(String dataset) {
    TableSchema schema = schema(dataset);
    List<Column> columns = schema.columns();
    try {
      BufferedReader reader = Files.newBufferedReader(fileFor(dataset), StandardCharsets.UTF_8);
      return reader
          .lines()
          .skip(1)
          .filter(line -> !line.isBlank())
          .map(CsvConnector::parseLine)
          .map(
              cells -> {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                  Column c = columns.get(i);
                  row.put(c.name(), TypeInference.convert(i < cells.size() ? cells.get(i) : null, c.type()));
                }
                return row;
              })
          .onClose(
              () -> {
                try {
                  reader.close();
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new ConnectorException("Cannot read " + dataset, e);
    }
  }

  private Path fileFor(String dataset) {
    Path file = directory.resolve(dataset + ".csv").normalize();
    // Guard against "../../etc/passwd" style dataset names.
    if (!file.startsWith(directory.normalize()) || !Files.isRegularFile(file)) {
      throw new DatasetNotFoundException(id, dataset);
    }
    return file;
  }

  private List<List<String>> readAll(String dataset) {
    try (Stream<String> lines = Files.lines(fileFor(dataset), StandardCharsets.UTF_8)) {
      List<List<String>> parsed = lines.filter(l -> !l.isBlank()).map(CsvConnector::parseLine).toList();
      if (parsed.isEmpty()) {
        throw new ConnectorException("CSV file is empty: " + dataset, null);
      }
      return parsed;
    } catch (IOException e) {
      throw new ConnectorException("Cannot read " + dataset, e);
    }
  }

  /** Minimal RFC-4180 style parser: commas, "quoted, values" and "" escaped quotes. */
  static List<String> parseLine(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (inQuotes) {
        if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cur.append('"');
          i++;
        } else if (ch == '"') {
          inQuotes = false;
        } else {
          cur.append(ch);
        }
      } else if (ch == '"') {
        inQuotes = true;
      } else if (ch == ',') {
        cells.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(ch);
      }
    }
    cells.add(cur.toString());
    return cells;
  }
}
