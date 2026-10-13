package com.example.dataconnector.connector;

import com.example.dataconnector.v1.ColumnType;
import java.util.regex.Pattern;

/**
 * Guesses a column type from text values (used for CSV files, which have no types).
 *
 * <p>We start with the "narrowest" guess and widen it as we see more values: BOOL or INT64 ->
 * DOUBLE -> STRING. Empty cells are treated as NULL and don't change the guess.
 */
final class TypeInference {

  private static final Pattern INT = Pattern.compile("[-+]?\\d{1,18}");
  private static final Pattern DOUBLE = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

  private TypeInference() {}

  static ColumnType classify(String value) {
    String v = value.trim();
    if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
      return ColumnType.COLUMN_TYPE_BOOL;
    }
    if (INT.matcher(v).matches()) {
      return ColumnType.COLUMN_TYPE_INT64;
    }
    if (DOUBLE.matcher(v).matches()) {
      return ColumnType.COLUMN_TYPE_DOUBLE;
    }
    return ColumnType.COLUMN_TYPE_STRING;
  }

  /** Combine the type so far with the type of a new value. */
  static ColumnType widen(ColumnType current, ColumnType next) {
    if (current == ColumnType.COLUMN_TYPE_UNSPECIFIED || current == next) {
      return next;
    }
    boolean numeric =
        (current == ColumnType.COLUMN_TYPE_INT64 || current == ColumnType.COLUMN_TYPE_DOUBLE)
            && (next == ColumnType.COLUMN_TYPE_INT64 || next == ColumnType.COLUMN_TYPE_DOUBLE);
    return numeric ? ColumnType.COLUMN_TYPE_DOUBLE : ColumnType.COLUMN_TYPE_STRING;
  }

  /** Convert text to the Java value matching the inferred type. */
  static Object convert(String raw, ColumnType type) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String v = raw.trim();
    return switch (type) {
      case COLUMN_TYPE_INT64 -> Long.parseLong(v);
      case COLUMN_TYPE_DOUBLE -> Double.parseDouble(v);
      case COLUMN_TYPE_BOOL -> Boolean.parseBoolean(v);
      default -> raw;
    };
  }
}
