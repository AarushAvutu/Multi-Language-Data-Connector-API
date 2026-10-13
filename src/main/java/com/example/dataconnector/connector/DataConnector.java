package com.example.dataconnector.connector;

import com.example.dataconnector.schema.TableSchema;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The plug-in interface every data source implements.
 *
 * <p>This is the heart of the "single API, many sources" idea. The gRPC service only ever
 * talks to this interface; it has no idea whether rows come from a CSV file, a SQL database or
 * a JSON document. To support a new source (S3, Snowflake, a REST API...) you write one new
 * class that implements these few methods and register it in {@link ConnectorFactory}.
 */
public interface DataConnector extends AutoCloseable {

  /** Unique id clients use to pick this connector, e.g. "sales-db". */
  String id();

  /** Kind of source, e.g. "csv", "jdbc", "json". */
  String type();

  String description();

  /** Names of the tables / files this connector exposes. */
  List<String> listDatasets();

  /**
   * Describes the columns of one dataset.
   *
   * @throws DatasetNotFoundException if the dataset does not exist
   */
  TableSchema schema(String dataset);

  /**
   * Streams the rows of one dataset. Each row maps column name to a value that is a {@link
   * String}, {@link Long}, {@link Double}, {@link Boolean} or {@code null}.
   *
   * <p>Callers must close the stream (use try-with-resources) so files / DB cursors are freed.
   */
  Stream<Map<String, Object>> read(String dataset);

  @Override
  default void close() {}
}
