package com.example.dataconnector.connector;

import static org.junit.jupiter.api.Assertions.*;

import com.example.dataconnector.schema.Column;
import com.example.dataconnector.schema.TableSchema;
import com.example.dataconnector.v1.ColumnType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ConnectorsTest {

  static final Path ROOT = Path.of("").toAbsolutePath();

  @Test
  void csvInfersTypesAndNullability() {
    CsvConnector csv = new CsvConnector("crm", ROOT.resolve("data/csv"));
    assertEquals(List.of("customers", "support_tickets"), csv.listDatasets());
    TableSchema s = csv.schema("customers");
    assertEquals(ColumnType.COLUMN_TYPE_INT64, s.column("customer_id").orElseThrow().type());
    assertEquals(ColumnType.COLUMN_TYPE_DOUBLE, s.column("lifetime_value").orElseThrow().type());
    assertEquals(ColumnType.COLUMN_TYPE_BOOL, s.column("is_active").orElseThrow().type());
    assertTrue(s.column("email").orElseThrow().nullable());
    try (Stream<Map<String, Object>> rows = csv.read("customers")) {
      List<Map<String, Object>> all = rows.toList();
      assertEquals(10, all.size());
      assertEquals("Lucas O'Brien", all.get(5).get("name"));
      assertNull(all.get(5).get("lifetime_value"));
    }
  }

  @Test
  void csvParserHandlesQuotes() {
    assertEquals(List.of("a", "b, c", "say \"hi\""), CsvConnector.parseLine("a,\"b, c\",\"say \"\"hi\"\"\""));
  }

  @Test
  void csvRejectsPathTraversal() {
    CsvConnector csv = new CsvConnector("crm", ROOT.resolve("data/csv"));
    assertThrows(DatasetNotFoundException.class, () -> csv.schema("../json/products"));
  }

  @Test
  void jsonConnector() {
    JsonConnector json = new JsonConnector("cat", ROOT.resolve("data/json"));
    TableSchema s = json.schema("products");
    assertEquals(ColumnType.COLUMN_TYPE_DOUBLE, s.column("price").orElseThrow().type());
    assertEquals(ColumnType.COLUMN_TYPE_INT64, s.column("stock_qty").orElseThrow().type());
    Column tags = s.column("tags").orElseThrow();
    assertEquals(ColumnType.COLUMN_TYPE_STRING, tags.type());
    assertTrue(tags.nullable());
    try (Stream<Map<String, Object>> rows = json.read("products")) {
      assertEquals("[\"school\",\"portable\"]", rows.findFirst().orElseThrow().get("tags"));
    }
  }

  @Test
  void jdbcConnectorUsesDatabaseMetadata() {
    try (JdbcConnector db = new JdbcConnector("sales", "jdbc:h2:mem:conn_test;DB_CLOSE_DELAY=-1", "", "", "PUBLIC",
        ROOT.resolve("data/sql/seed.sql"))) {
      assertEquals(List.of("orders", "regions"), db.listDatasets());
      TableSchema s = db.schema("orders");
      assertEquals(ColumnType.COLUMN_TYPE_DOUBLE, s.column("amount").orElseThrow().type());
      assertEquals(ColumnType.COLUMN_TYPE_STRING, s.column("order_date").orElseThrow().type());
      assertFalse(s.column("order_id").orElseThrow().nullable());
      assertTrue(s.column("coupon_code").orElseThrow().nullable());
      try (Stream<Map<String, Object>> rows = db.read("ORDERS")) {
        assertEquals(8, rows.count());
      }
      assertThrows(DatasetNotFoundException.class, () -> db.schema("nope"));
    }
  }
}
