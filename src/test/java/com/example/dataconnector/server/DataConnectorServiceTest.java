package com.example.dataconnector.server;

import static org.junit.jupiter.api.Assertions.*;

import com.example.dataconnector.client.DataConnectorClient;
import com.example.dataconnector.connector.ConnectorFactory;
import com.example.dataconnector.connector.ConnectorRegistry;
import com.example.dataconnector.v1.ColumnType;
import com.example.dataconnector.v1.Filter;
import com.example.dataconnector.v1.GetSchemaResponse;
import com.example.dataconnector.v1.ReadDataRequest;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** End-to-end tests: real service + real client over an in-process gRPC channel. */
class DataConnectorServiceTest {

  static Server server;
  static ManagedChannel channel;
  static DataConnectorClient client;
  static ConnectorRegistry registry;

  @BeforeAll
  static void start() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    registry = ConnectorFactory.load(root.resolve("config/connectors.json"), root);
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor()
        .addService(new DataConnectorServiceImpl(registry)).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    client = new DataConnectorClient(channel);
  }

  @AfterAll
  static void stop() {
    channel.shutdownNow();
    server.shutdownNow();
    registry.close();
  }

  @Test
  void listsAllConnectorsAndDatasets() {
    assertEquals(List.of("sales-db", "crm-files", "product-catalog"),
        client.connectors().stream().map(c -> c.getId()).toList());
    assertEquals(List.of("orders", "regions"),
        client.datasets("sales-db").stream().map(d -> d.getName()).toList());
  }

  @Test
  void schemaContainsUsableDescriptor() {
    GetSchemaResponse s = client.schema("sales-db", "orders");
    assertEquals("dataconnector.dynamic.sales_db.Orders", s.getMessageType());
    assertEquals(1, s.getDescriptorSet().getFileCount());
    assertEquals(ColumnType.COLUMN_TYPE_DOUBLE,
        s.getFieldsList().stream().filter(f -> f.getName().equals("amount")).findFirst().orElseThrow().getType());
  }

  @Test
  void sameApiReadsEveryKindOfSource() {
    for (String[] src : new String[][] {{"sales-db", "orders"}, {"crm-files", "customers"}, {"product-catalog", "products"}}) {
      List<DynamicMessage> rows = client.read(ReadDataRequest.newBuilder().setConnectorId(src[0]).setDataset(src[1]).build());
      assertFalse(rows.isEmpty(), src[0]);
    }
  }

  @Test
  void filtersProjectionAndLimit() {
    List<DynamicMessage> rows = client.read(ReadDataRequest.newBuilder()
        .setConnectorId("crm-files").setDataset("customers")
        .addColumns("name")
        .addFilters(Filter.newBuilder().setColumn("country").setOp(Filter.Operator.EQUALS).setValue("USA"))
        .addFilters(Filter.newBuilder().setColumn("lifetime_value").setOp(Filter.Operator.GREATER_THAN).setValue("300"))
        .build());
    assertEquals(2, rows.size()); // Ava (1520.75) and Noah (450.25)
    Descriptor d = rows.get(0).getDescriptorForType();
    assertEquals(1, rows.get(0).getAllFields().size());
    assertEquals("Ava Johnson", rows.get(0).getField(d.findFieldByName("name")));

    assertEquals(3, client.read(ReadDataRequest.newBuilder()
        .setConnectorId("product-catalog").setDataset("products").setLimit(3).build()).size());
  }

  @Test
  void containsAndBooleanFilters() {
    assertEquals(2, client.read(ReadDataRequest.newBuilder()
        .setConnectorId("product-catalog").setDataset("products")
        .addFilters(Filter.newBuilder().setColumn("name").setOp(Filter.Operator.CONTAINS).setValue("laptop"))
        .build()).size());
    assertEquals(2, client.read(ReadDataRequest.newBuilder()
        .setConnectorId("sales-db").setDataset("orders")
        .addFilters(Filter.newBuilder().setColumn("is_shipped").setOp(Filter.Operator.EQUALS).setValue("false"))
        .build()).size());
  }

  @Test
  void errorsMapToGrpcStatusCodes() {
    assertCode(Status.Code.NOT_FOUND, () -> client.datasets("missing"));
    assertCode(Status.Code.NOT_FOUND, () -> client.schema("crm-files", "nope"));
    assertCode(Status.Code.INVALID_ARGUMENT, () -> client.read(ReadDataRequest.newBuilder()
        .setConnectorId("crm-files").setDataset("customers").addColumns("salary").build()));
    assertCode(Status.Code.INVALID_ARGUMENT, () -> client.read(ReadDataRequest.newBuilder()
        .setConnectorId("crm-files").setDataset("customers")
        .addFilters(Filter.newBuilder().setColumn("signup_year").setOp(Filter.Operator.GREATER_THAN).setValue("soon"))
        .build()));
  }

  private static void assertCode(Status.Code code, Runnable r) {
    StatusRuntimeException e = assertThrows(StatusRuntimeException.class, r::run);
    assertEquals(code, e.getStatus().getCode(), e.getMessage());
  }
}
