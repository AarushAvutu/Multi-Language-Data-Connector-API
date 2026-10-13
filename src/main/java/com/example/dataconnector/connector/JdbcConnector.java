package com.example.dataconnector.connector;

import com.example.dataconnector.schema.Column;
import com.example.dataconnector.schema.TableSchema;
import com.example.dataconnector.v1.ColumnType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Exposes the tables of any JDBC database (PostgreSQL, MySQL, SQL Server, H2...) as datasets.
 *
 * <p>The schema comes from the database's own metadata ({@link DatabaseMetaData#getColumns}),
 * so no type guessing is needed. Rows are streamed straight from the database cursor — we never
 * load a whole table into memory.
 *
 * <p>The demo uses an in-memory H2 database seeded from {@code data/sql/seed.sql}, so it runs
 * with zero setup. Point {@code url} at a real database to use it for real.
 */
public final class JdbcConnector implements DataConnector {

  private final String id;
  private final String url;
  private final String user;
  private final String password;
  private final String schemaName;
  // Keep one connection open: for H2 "mem:" databases this keeps the data alive.
  private final Connection keepAlive;

  public JdbcConnector(String id, String url, String user, String password, String schemaName, Path initScript) {
    this.id = id;
    this.url = url;
    this.user = user == null ? "" : user;
    this.password = password == null ? "" : password;
    this.schemaName = schemaName;
    try {
      this.keepAlive = DriverManager.getConnection(url, this.user, this.password);
      if (initScript != null) {
        try (Statement st = keepAlive.createStatement()) {
          st.execute("RUNSCRIPT FROM '" + initScript.toAbsolutePath().toString().replace("'", "''") + "'");
        }
      }
    } catch (SQLException e) {
      throw new ConnectorException("Cannot connect to " + url, e);
    }
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String type() {
    return "jdbc";
  }

  @Override
  public String description() {
    return "SQL database at " + url;
  }

  @Override
  public List<String> listDatasets() {
    try (Connection c = open();
        ResultSet rs = c.getMetaData().getTables(null, schemaName, "%", new String[] {"TABLE"})) {
      List<String> names = new ArrayList<>();
      while (rs.next()) {
        names.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
      }
      names.sort(String::compareTo);
      return names;
    } catch (SQLException e) {
      throw new ConnectorException("Cannot list tables", e);
    }
  }

  @Override
  public TableSchema schema(String dataset) {
    String table = actualTableName(dataset);
    try (Connection c = open();
        ResultSet rs = c.getMetaData().getColumns(null, schemaName, table, "%")) {
      List<Column> columns = new ArrayList<>();
      while (rs.next()) {
        columns.add(
            new Column(
                rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                fromSqlType(rs.getInt("DATA_TYPE")),
                rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
      }
      return new TableSchema(id, dataset, columns);
    } catch (SQLException e) {
      throw new ConnectorException("Cannot describe " + dataset, e);
    }
  }

  @Override
  public Stream<Map<String, Object>> read(String dataset) {
    String table = actualTableName(dataset);
    try {
      Connection c = open();
      Statement st = c.createStatement();
      String qualified = (schemaName == null ? "" : quote(schemaName) + ".") + quote(table);
      ResultSet rs = st.executeQuery("SELECT * FROM " + qualified);
      ResultSetMetaData md = rs.getMetaData();

      Spliterator<Map<String, Object>> rows =
          new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED) {
            @Override
            public boolean tryAdvance(Consumer<? super Map<String, Object>> action) {
              try {
                if (!rs.next()) {
                  return false;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                  row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), toJava(rs, i, md.getColumnType(i)));
                }
                action.accept(row);
                return true;
              } catch (SQLException e) {
                throw new ConnectorException("Error reading " + dataset, e);
              }
            }
          };
      return StreamSupport.stream(rows, false)
          .onClose(
              () -> {
                try (c; st; rs) {
                  // closes cursor, statement and connection
                } catch (SQLException ignored) {
                  // nothing useful to do while closing
                }
              });
    } catch (SQLException e) {
      throw new ConnectorException("Cannot read " + dataset, e);
    }
  }

  @Override
  public void close() {
    try {
      keepAlive.close();
    } catch (SQLException ignored) {
      // shutting down anyway
    }
  }

  private Connection open() throws SQLException {
    return DriverManager.getConnection(url, user, password);
  }

  /** Datasets are exposed in lower case; find the real (often upper-case) table name. */
  private String actualTableName(String dataset) {
    try (Connection c = open();
        ResultSet rs = c.getMetaData().getTables(null, schemaName, "%", new String[] {"TABLE"})) {
      while (rs.next()) {
        String name = rs.getString("TABLE_NAME");
        if (name.equalsIgnoreCase(dataset)) {
          return name;
        }
      }
    } catch (SQLException e) {
      throw new ConnectorException("Cannot look up table " + dataset, e);
    }
    throw new DatasetNotFoundException(id, dataset);
  }

  private static String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  static ColumnType fromSqlType(int sqlType) {
    return switch (sqlType) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> ColumnType.COLUMN_TYPE_INT64;
      case Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> ColumnType.COLUMN_TYPE_DOUBLE;
      case Types.BOOLEAN, Types.BIT -> ColumnType.COLUMN_TYPE_BOOL;
      default -> ColumnType.COLUMN_TYPE_STRING; // VARCHAR, DATE, TIMESTAMP, ... sent as text
    };
  }

  private static Object toJava(ResultSet rs, int i, int sqlType) throws SQLException {
    Object value =
        switch (fromSqlType(sqlType)) {
          case COLUMN_TYPE_INT64 -> rs.getLong(i);
          case COLUMN_TYPE_DOUBLE -> rs.getDouble(i);
          case COLUMN_TYPE_BOOL -> rs.getBoolean(i);
          default -> rs.getString(i);
        };
    return rs.wasNull() ? null : value;
  }
}
