package com.example.dataconnector.schema;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import java.util.Collection;
import java.util.Map;

/**
 * Converts one row (a plain {@code Map<column, value>}) into a Protobuf {@link DynamicMessage}
 * that matches a {@link ResolvedSchema}.
 *
 * <p>A {@code null} value simply leaves the field unset, so clients see {@code hasField ==
 * false}. Values are coerced to the declared column type (e.g. "42" -> 42L).
 */
public final class RowEncoder {

  private RowEncoder() {}

  /**
   * @param projection columns to include; empty means "all columns"
   */
  public static DynamicMessage encode(
      ResolvedSchema schema, Map<String, Object> row, Collection<String> projection) {
    DynamicMessage.Builder builder = DynamicMessage.newBuilder(schema.descriptor());
    for (Map.Entry<String, FieldDescriptor> e : schema.columnToField().entrySet()) {
      String column = e.getKey();
      if (!projection.isEmpty() && projection.stream().noneMatch(column::equalsIgnoreCase)) {
        continue;
      }
      Object value = row.get(column);
      if (value != null) {
        builder.setField(e.getValue(), coerce(e.getValue(), value));
      }
    }
    return builder.build();
  }

  static Object coerce(FieldDescriptor field, Object value) {
    return switch (field.getJavaType()) {
      case LONG -> value instanceof Number n ? n.longValue() : Long.parseLong(value.toString().trim());
      case DOUBLE ->
          value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString().trim());
      case BOOLEAN ->
          value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString().trim());
      default -> value.toString();
    };
  }
}
