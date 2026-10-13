package com.example.dataconnector.client;

import com.example.dataconnector.schema.DescriptorResolver;
import com.example.dataconnector.v1.ConnectorInfo;
import com.example.dataconnector.v1.DataConnectorServiceGrpc;
import com.example.dataconnector.v1.DataRecord;
import com.example.dataconnector.v1.DatasetInfo;
import com.example.dataconnector.v1.FieldInfo;
import com.example.dataconnector.v1.Filter;
import com.example.dataconnector.v1.GetSchemaRequest;
import com.example.dataconnector.v1.GetSchemaResponse;
import com.example.dataconnector.v1.ListConnectorsRequest;
import com.example.dataconnector.v1.ListDatasetsRequest;
import com.example.dataconnector.v1.ReadDataRequest;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A friendly Java wrapper around the generated gRPC stub.
 *
 * <p>Usage:
 *
 * <pre>
 *   java -cp target/data-connector-api.jar com.example.dataconnector.client.DataConnectorClient
 *   java -cp ... DataConnectorClient localhost:50051 read crm-files customers --where country=USA --limit 3
 * </pre>
 *
 * Commands: {@code connectors}, {@code datasets <connector>}, {@code schema <connector>
 * <dataset>}, {@code read <connector> <dataset> [--columns a,b] [--where col<op>value] [--limit
 * n]}. With no command it runs a guided demo of every connector.
 */
public final class DataConnectorClient {

  private final DataConnectorServiceGrpc.DataConnectorServiceBlockingStub stub;

  public DataConnectorClient(Channel channel) {
    this.stub = DataConnectorServiceGrpc.newBlockingStub(channel);
  }

  public List<ConnectorInfo> connectors() {
    return stub.listConnectors(ListConnectorsRequest.getDefaultInstance()).getConnectorsList();
  }

  public List<DatasetInfo> datasets(String connectorId) {
    return stub.listDatasets(ListDatasetsRequest.newBuilder().setConnectorId(connectorId).build()).getDatasetsList();
  }

  public GetSchemaResponse schema(String connectorId, String dataset) {
    return stub.getSchema(GetSchemaRequest.newBuilder().setConnectorId(connectorId).setDataset(dataset).build());
  }

  /**
   * Reads rows and decodes them with a descriptor resolved at runtime. Step 1: ask for the
   * schema. Step 2: rebuild a Descriptor from the FileDescriptorSet. Step 3: stream rows and
   * parse each Any payload with that Descriptor.
   */
  public List<DynamicMessage> read(ReadDataRequest request) {
    GetSchemaResponse schema = schema(request.getConnectorId(), request.getDataset());
    Descriptor descriptor = DescriptorResolver.resolve(schema.getDescriptorSet(), schema.getMessageType());
    List<DynamicMessage> rows = new ArrayList<>();
    Iterator<DataRecord> it = stub.withDeadlineAfter(30, TimeUnit.SECONDS).readData(request);
    while (it.hasNext()) {
      rows.add(DescriptorResolver.unpack(it.next().getPayload(), descriptor));
    }
    return rows;
  }

  // -------------------------------------------------------------------- CLI

  public static void main(String[] args) throws Exception {
    String target = "localhost:50051";
    int i = 0;
    if (args.length > 0 && args[0].contains(":")) {
      target = args[0];
      i = 1;
    }
    ManagedChannel channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    try {
      DataConnectorClient client = new DataConnectorClient(channel);
      String cmd = i < args.length ? args[i] : "demo";
      switch (cmd) {
        case "connectors" -> client.connectors().forEach(c -> System.out.printf("%-16s %-5s %s%n", c.getId(), c.getType(), c.getDescription()));
        case "datasets" -> client.datasets(args[i + 1]).forEach(d -> System.out.println(d.getName()));
        case "schema" -> printSchema(client.schema(args[i + 1], args[i + 2]));
        case "read" -> printRows(client.read(parseRead(args, i + 1)));
        case "demo" -> demo(client);
        default -> System.err.println("Unknown command: " + cmd);
      }
    } catch (StatusRuntimeException e) {
      System.err.println("RPC failed: " + e.getStatus().getCode() + " - " + e.getStatus().getDescription());
      System.exit(1);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static void demo(DataConnectorClient client) {
    System.out.println("=== Connectors ===");
    for (ConnectorInfo c : client.connectors()) {
      System.out.printf("%n[%s] type=%s  (%s)%n", c.getId(), c.getType(), c.getDescription());
      for (DatasetInfo d : client.datasets(c.getId())) {
        GetSchemaResponse s = client.schema(c.getId(), d.getName());
        System.out.printf("  dataset %-12s -> message %s%n", d.getName(), s.getMessageType());
        List<DynamicMessage> rows =
            client.read(ReadDataRequest.newBuilder().setConnectorId(c.getId()).setDataset(d.getName()).setLimit(2).build());
        rows.forEach(r -> System.out.println("     " + toJson(r)));
      }
    }
    System.out.println("\n=== Filtered read: crm-files/customers where country = USA, columns name,lifetime_value ===");
    printRows(
        client.read(
            ReadDataRequest.newBuilder()
                .setConnectorId("crm-files")
                .setDataset("customers")
                .addColumns("name")
                .addColumns("lifetime_value")
                .addFilters(Filter.newBuilder().setColumn("country").setOp(Filter.Operator.EQUALS).setValue("USA"))
                .build()));
  }

  static ReadDataRequest parseRead(String[] args, int start) {
    ReadDataRequest.Builder b = ReadDataRequest.newBuilder().setConnectorId(args[start]).setDataset(args[start + 1]);
    for (int i = start + 2; i < args.length; i++) {
      switch (args[i]) {
        case "--limit" -> b.setLimit(Integer.parseInt(args[++i]));
        case "--columns" -> b.addAllColumns(List.of(args[++i].split(",")));
        case "--where" -> b.addFilters(parseFilter(args[++i]));
        default -> throw new IllegalArgumentException("Unknown option " + args[i]);
      }
    }
    return b.build();
  }

  /** "country=USA", "price>10", "price<5", "name!=Bob", "name~ann" (contains). */
  static Filter parseFilter(String expr) {
    String[][] ops = {{"!=", "NOT_EQUALS"}, {"=", "EQUALS"}, {">", "GREATER_THAN"}, {"<", "LESS_THAN"}, {"~", "CONTAINS"}};
    for (String[] op : ops) {
      int idx = expr.indexOf(op[0]);
      if (idx > 0) {
        return Filter.newBuilder()
            .setColumn(expr.substring(0, idx).trim())
            .setOp(Filter.Operator.valueOf(op[1]))
            .setValue(expr.substring(idx + op[0].length()).trim())
            .build();
      }
    }
    throw new IllegalArgumentException("Bad filter: " + expr);
  }

  private static void printSchema(GetSchemaResponse s) {
    System.out.println("message type: " + s.getMessageType());
    for (FieldInfo f : s.getFieldsList()) {
      System.out.printf("  %2d  %-18s %-20s %s%n", f.getNumber(), f.getName(), f.getType(), f.getNullable() ? "nullable" : "");
    }
  }

  private static void printRows(List<DynamicMessage> rows) {
    rows.forEach(r -> System.out.println(toJson(r)));
    System.out.println("(" + rows.size() + " rows)");
  }

  static String toJson(DynamicMessage m) {
    StringBuilder sb = new StringBuilder("{");
    for (FieldDescriptor f : m.getDescriptorForType().getFields()) {
      if (!m.hasField(f)) {
        continue;
      }
      if (sb.length() > 1) {
        sb.append(", ");
      }
      Object v = m.getField(f);
      sb.append('"').append(f.getName()).append("\": ");
      sb.append(v instanceof String s ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : v);
    }
    return sb.append('}').toString();
  }
}
