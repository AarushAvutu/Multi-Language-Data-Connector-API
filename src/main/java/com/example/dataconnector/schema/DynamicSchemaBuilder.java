package com.example.dataconnector.schema;

import com.example.dataconnector.v1.ColumnType;
import com.example.dataconnector.v1.FieldInfo;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a Protobuf message type <em>at runtime</em> from a {@link TableSchema}.
 *
 * <p>Normally you write a {@code .proto} file and run {@code protoc}. Here we can't: nobody
 * knows ahead of time what columns a CSV file or SQL table will have. Instead we create the
 * same data structure {@code protoc} would have produced — a {@link FileDescriptorProto} — in
 * plain Java code, then ask the Protobuf library to validate and "link" it with {@link
 * FileDescriptor#buildFrom}. The result behaves exactly like a compiled message type.
 *
 * <p>For a CSV file {@code customers.csv} in connector {@code crm-files} we generate the
 * equivalent of:
 *
 * <pre>
 * syntax = "proto3";
 * package dataconnector.dynamic.crm_files;
 * message Customers {
 *   optional int64  customer_id    = 1;
 *   optional string name           = 2;
 *   optional double lifetime_value = 3;
 *   ...
 * }
 * </pre>
 *
 * Every field is {@code optional} so a client can tell "value is 0 / empty" apart from "value
 * is NULL" (via {@code hasField}).
 */
public final class DynamicSchemaBuilder {

  public static final String PACKAGE_PREFIX = "dataconnector.dynamic.";

  private DynamicSchemaBuilder() {}

  public static ResolvedSchema build(TableSchema schema) {
    if (schema.columns().isEmpty()) {
      throw new SchemaException("Dataset '" + schema.dataset() + "' has no columns");
    }
    String pkg = PACKAGE_PREFIX + toSnakeIdentifier(schema.connectorId());
    String messageName = toPascalIdentifier(schema.dataset());

    DescriptorProto.Builder message = DescriptorProto.newBuilder().setName(messageName);
    List<FieldInfo> fieldInfos = new ArrayList<>();
    Map<String, String> columnToFieldName = new LinkedHashMap<>();
    Set<String> usedNames = new HashSet<>();

    int number = 1;
    for (Column column : schema.columns()) {
      String fieldName = uniqueName(toSnakeIdentifier(column.name()), usedNames);
      int oneofIndex = message.getOneofDeclCount();

      // proto3 "optional" is represented as a synthetic one-field oneof named "_<field>".
      message.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_" + fieldName));
      message.addField(
          FieldDescriptorProto.newBuilder()
              .setName(fieldName)
              .setJsonName(fieldName)
              .setNumber(number)
              .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
              .setType(toProtoType(column.type()))
              .setProto3Optional(true)
              .setOneofIndex(oneofIndex));

      fieldInfos.add(
          FieldInfo.newBuilder()
              .setName(fieldName)
              .setNumber(number)
              .setType(column.type())
              .setNullable(column.nullable())
              .build());
      columnToFieldName.put(column.name(), fieldName);
      number++;
    }

    FileDescriptorProto fileProto =
        FileDescriptorProto.newBuilder()
            .setName(
                "dynamic/"
                    + toSnakeIdentifier(schema.connectorId())
                    + "/"
                    + toSnakeIdentifier(schema.dataset())
                    + ".proto")
            .setPackage(pkg)
            .setSyntax("proto3")
            .addMessageType(message)
            .build();

    FileDescriptor file;
    try {
      file = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    } catch (DescriptorValidationException e) {
      throw new SchemaException("Generated descriptor is invalid: " + e.getMessage(), e);
    }
    Descriptor descriptor = file.findMessageTypeByName(messageName);

    Map<String, FieldDescriptor> columnToField = new LinkedHashMap<>();
    columnToFieldName.forEach((col, field) -> columnToField.put(col, descriptor.findFieldByName(field)));

    return new ResolvedSchema(
        schema,
        descriptor,
        FileDescriptorSet.newBuilder().addFile(fileProto).build(),
        List.copyOf(fieldInfos),
        Map.copyOf(columnToField));
  }

  static FieldDescriptorProto.Type toProtoType(ColumnType type) {
    return switch (type) {
      case COLUMN_TYPE_INT64 -> FieldDescriptorProto.Type.TYPE_INT64;
      case COLUMN_TYPE_DOUBLE -> FieldDescriptorProto.Type.TYPE_DOUBLE;
      case COLUMN_TYPE_BOOL -> FieldDescriptorProto.Type.TYPE_BOOL;
      default -> FieldDescriptorProto.Type.TYPE_STRING;
    };
  }

  /** "Lifetime Value ($)" -> "lifetime_value". Always a valid protobuf identifier. */
  public static String toSnakeIdentifier(String raw) {
    String s =
        raw.trim()
            .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    if (s.isEmpty()) {
      s = "field";
    }
    if (Character.isDigit(s.charAt(0))) {
      s = "f_" + s;
    }
    return s;
  }

  /** "order_items" -> "OrderItems". */
  public static String toPascalIdentifier(String raw) {
    StringBuilder out = new StringBuilder();
    for (String part : toSnakeIdentifier(raw).split("_")) {
      if (!part.isEmpty()) {
        out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
      }
    }
    if (Character.isDigit(out.charAt(0))) {
      out.insert(0, 'T');
    }
    return out.toString();
  }

  private static String uniqueName(String base, Set<String> used) {
    String candidate = base;
    int i = 2;
    while (!used.add(candidate)) {
      candidate = base + "_" + i++;
    }
    return candidate;
  }
}
