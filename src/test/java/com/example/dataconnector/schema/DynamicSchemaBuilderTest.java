package com.example.dataconnector.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.example.dataconnector.v1.ColumnType;
import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DynamicSchemaBuilderTest {

  private final TableSchema schema =
      new TableSchema(
          "crm-files",
          "customer list",
          List.of(
              new Column("Customer ID", ColumnType.COLUMN_TYPE_INT64, false),
              new Column("name", ColumnType.COLUMN_TYPE_STRING, false),
              new Column("lifetimeValue", ColumnType.COLUMN_TYPE_DOUBLE, true),
              new Column("active", ColumnType.COLUMN_TYPE_BOOL, false)));

  @Test
  void buildsValidProto3MessageWithSanitizedNames() {
    ResolvedSchema rs = DynamicSchemaBuilder.build(schema);
    assertEquals("dataconnector.dynamic.crm_files.CustomerList", rs.messageType());
    Descriptor d = rs.descriptor();
    assertEquals(List.of("customer_id", "name", "lifetime_value", "active"),
        d.getFields().stream().map(f -> f.getName()).toList());
    assertTrue(d.findFieldByName("lifetime_value").hasPresence(), "optional fields track presence");
    assertEquals(4, rs.fields().size());
  }

  @Test
  void descriptorSetRoundTripsThroughClientResolver() throws Exception {
    ResolvedSchema rs = DynamicSchemaBuilder.build(schema);
    Map<String, Object> row = new HashMap<>();
    row.put("Customer ID", 7L);
    row.put("name", "Ada");
    row.put("lifetimeValue", null);
    row.put("active", true);
    Any any = Any.pack(RowEncoder.encode(rs, row, List.of()));

    // Simulate the wire: only bytes cross over.
    var wireSet = com.google.protobuf.DescriptorProtos.FileDescriptorSet.parseFrom(rs.descriptorSet().toByteArray());
    Descriptor clientSide = DescriptorResolver.resolve(wireSet, rs.messageType());
    DynamicMessage decoded = DescriptorResolver.unpack(Any.parseFrom(any.toByteArray()), clientSide);

    assertEquals(7L, decoded.getField(clientSide.findFieldByName("customer_id")));
    assertEquals("Ada", decoded.getField(clientSide.findFieldByName("name")));
    assertFalse(decoded.hasField(clientSide.findFieldByName("lifetime_value")), "null stays unset");
    assertEquals(true, decoded.getField(clientSide.findFieldByName("active")));
  }

  @Test
  void projectionOnlyFillsRequestedColumns() {
    ResolvedSchema rs = DynamicSchemaBuilder.build(schema);
    DynamicMessage m = RowEncoder.encode(rs, Map.of("Customer ID", 1L, "name", "Bo", "active", false), List.of("name"));
    assertEquals(1, m.getAllFields().size());
  }

  @Test
  void identifierHelpers() {
    assertEquals("lifetime_value", DynamicSchemaBuilder.toSnakeIdentifier("Lifetime Value ($)"));
    assertEquals("f_2024_sales", DynamicSchemaBuilder.toSnakeIdentifier("2024 Sales"));
    assertEquals("SupportTickets", DynamicSchemaBuilder.toPascalIdentifier("support_tickets"));
  }

  @Test
  void schemaCacheReusesAndRebuildsOnChange() {
    SchemaCache cache = new SchemaCache();
    ResolvedSchema a = cache.resolve(schema);
    assertSame(a, cache.resolve(schema));
    TableSchema changed = new TableSchema(schema.connectorId(), schema.dataset(),
        List.of(new Column("only", ColumnType.COLUMN_TYPE_STRING, true)));
    assertNotSame(a, cache.resolve(changed));
  }

  @Test
  void wrongTypeUrlIsRejected() {
    ResolvedSchema rs = DynamicSchemaBuilder.build(schema);
    Any bogus = Any.newBuilder().setTypeUrl("type.googleapis.com/other.Msg").build();
    assertThrows(SchemaException.class, () -> DescriptorResolver.unpack(bogus, rs.descriptor()));
  }
}
