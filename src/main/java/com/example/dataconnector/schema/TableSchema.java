package com.example.dataconnector.schema;

import java.util.List;
import java.util.Optional;

/**
 * The connector-neutral description of a dataset: its name and its columns.
 *
 * <p>Every connector (CSV, SQL, JSON, ...) translates its own native metadata into this
 * one simple shape. {@link DynamicSchemaBuilder} then turns it into a Protobuf descriptor.
 */
public record TableSchema(String connectorId, String dataset, List<Column> columns) {

  public TableSchema {
    columns = List.copyOf(columns);
  }

  public Optional<Column> column(String name) {
    return columns.stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
  }
}
