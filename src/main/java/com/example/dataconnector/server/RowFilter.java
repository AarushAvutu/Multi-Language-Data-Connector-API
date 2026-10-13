package com.example.dataconnector.server;

import com.example.dataconnector.schema.Column;
import com.example.dataconnector.schema.TableSchema;
import com.example.dataconnector.v1.ColumnType;
import com.example.dataconnector.v1.Filter;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns the {@code filters} in a ReadDataRequest into a Java {@link Predicate} that says "keep
 * this row?". Filtering happens on the server, so only matching rows travel over the network.
 *
 * <p>Invalid input (unknown column, "abc" compared to a number, CONTAINS on a number) throws
 * {@link IllegalArgumentException}, which the service reports as gRPC {@code INVALID_ARGUMENT}.
 */
final class RowFilter {

  private RowFilter() {}

  static Predicate<Map<String, Object>> compile(List<Filter> filters, TableSchema schema) {
    Predicate<Map<String, Object>> all = row -> true;
    for (Filter f : filters) {
      all = all.and(compileOne(f, schema));
    }
    return all;
  }

  private static Predicate<Map<String, Object>> compileOne(Filter f, TableSchema schema) {
    Column column =
        schema
            .column(f.getColumn())
            .orElseThrow(() -> new IllegalArgumentException("Unknown filter column: " + f.getColumn()));
    Filter.Operator op = f.getOp();
    if (op == Filter.Operator.OPERATOR_UNSPECIFIED || op == Filter.Operator.UNRECOGNIZED) {
      throw new IllegalArgumentException("Filter on '" + f.getColumn() + "' needs an operator");
    }
    if (op == Filter.Operator.CONTAINS && column.type() != ColumnType.COLUMN_TYPE_STRING) {
      throw new IllegalArgumentException("CONTAINS only works on string columns");
    }
    Comparable<?> target = parse(f.getValue(), column);
    String name = column.name();

    return row -> {
      Object actual = row.get(name);
      if (actual == null) {
        return op == Filter.Operator.NOT_EQUALS;
      }
      if (op == Filter.Operator.CONTAINS) {
        return actual.toString().toLowerCase().contains(f.getValue().toLowerCase());
      }
      int cmp = compare(actual, target);
      return switch (op) {
        case EQUALS -> cmp == 0;
        case NOT_EQUALS -> cmp != 0;
        case GREATER_THAN -> cmp > 0;
        case LESS_THAN -> cmp < 0;
        default -> false;
      };
    };
  }

  private static Comparable<?> parse(String value, Column column) {
    try {
      return switch (column.type()) {
        case COLUMN_TYPE_INT64, COLUMN_TYPE_DOUBLE -> Double.parseDouble(value.trim());
        case COLUMN_TYPE_BOOL -> {
          if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new NumberFormatException();
          }
          yield Boolean.parseBoolean(value);
        }
        default -> value;
      };
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Value '" + value + "' is not valid for column '" + column.name() + "' of type " + column.type());
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compare(Object actual, Comparable<?> target) {
    if (actual instanceof Number n) {
      return Double.compare(n.doubleValue(), (Double) target);
    }
    if (actual instanceof Boolean b) {
      return Boolean.compare(b, (Boolean) target);
    }
    return ((Comparable) actual.toString()).compareTo(target.toString());
  }
}
