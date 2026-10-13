package com.example.dataconnector.server;

import com.example.dataconnector.connector.ConnectorException;
import com.example.dataconnector.connector.ConnectorRegistry;
import com.example.dataconnector.connector.DataConnector;
import com.example.dataconnector.connector.DatasetNotFoundException;
import com.example.dataconnector.schema.ResolvedSchema;
import com.example.dataconnector.schema.RowEncoder;
import com.example.dataconnector.schema.SchemaCache;
import com.example.dataconnector.schema.SchemaException;
import com.example.dataconnector.schema.TableSchema;
import com.example.dataconnector.v1.ConnectorInfo;
import com.example.dataconnector.v1.DataConnectorServiceGrpc;
import com.example.dataconnector.v1.DataRecord;
import com.example.dataconnector.v1.DatasetInfo;
import com.example.dataconnector.v1.GetSchemaRequest;
import com.example.dataconnector.v1.GetSchemaResponse;
import com.example.dataconnector.v1.ListConnectorsRequest;
import com.example.dataconnector.v1.ListConnectorsResponse;
import com.example.dataconnector.v1.ListDatasetsRequest;
import com.example.dataconnector.v1.ListDatasetsResponse;
import com.example.dataconnector.v1.ReadDataRequest;
import com.google.protobuf.Any;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The gRPC service: implements the four RPCs declared in {@code data_connector.proto}.
 *
 * <p>{@code DataConnectorServiceGrpc.DataConnectorServiceImplBase} was <em>generated</em> by
 * protoc from the .proto file. We extend it and fill in the method bodies. Every method follows
 * the same pattern: validate input → find the connector → do the work → {@code onNext(...)}
 * the answer(s) → {@code onCompleted()}. Errors are reported with {@code onError(Status...)}.
 */
public final class DataConnectorServiceImpl extends DataConnectorServiceGrpc.DataConnectorServiceImplBase {

  private static final Logger LOG = Logger.getLogger(DataConnectorServiceImpl.class.getName());

  private final ConnectorRegistry registry;
  private final SchemaCache schemaCache = new SchemaCache();

  public DataConnectorServiceImpl(ConnectorRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void listConnectors(ListConnectorsRequest request, StreamObserver<ListConnectorsResponse> out) {
    ListConnectorsResponse.Builder resp = ListConnectorsResponse.newBuilder();
    for (DataConnector c : registry.all()) {
      resp.addConnectors(
          ConnectorInfo.newBuilder().setId(c.id()).setType(c.type()).setDescription(c.description()));
    }
    out.onNext(resp.build());
    out.onCompleted();
  }

  @Override
  public void listDatasets(ListDatasetsRequest request, StreamObserver<ListDatasetsResponse> out) {
    run(out, () -> {
      DataConnector c = connector(request.getConnectorId());
      ListDatasetsResponse.Builder resp = ListDatasetsResponse.newBuilder();
      for (String name : c.listDatasets()) {
        resp.addDatasets(DatasetInfo.newBuilder().setConnectorId(c.id()).setName(name));
      }
      out.onNext(resp.build());
      out.onCompleted();
    });
  }

  @Override
  public void getSchema(GetSchemaRequest request, StreamObserver<GetSchemaResponse> out) {
    run(out, () -> {
      ResolvedSchema schema = resolve(request.getConnectorId(), request.getDataset());
      out.onNext(
          GetSchemaResponse.newBuilder()
              .setMessageType(schema.messageType())
              .setDescriptorSet(schema.descriptorSet())
              .addAllFields(schema.fields())
              .build());
      out.onCompleted();
    });
  }

  @Override
  public void readData(ReadDataRequest request, StreamObserver<DataRecord> out) {
    ServerCallStreamObserver<DataRecord> call = (ServerCallStreamObserver<DataRecord>) out;
    run(out, () -> {
      DataConnector c = connector(request.getConnectorId());
      ResolvedSchema schema = resolve(c.id(), request.getDataset());
      TableSchema table = schema.source();

      List<String> projection = request.getColumnsList();
      for (String col : projection) {
        if (table.column(col).isEmpty()) {
          throw new IllegalArgumentException("Unknown column: " + col);
        }
      }
      Predicate<Map<String, Object>> keep = RowFilter.compile(request.getFiltersList(), table);
      int limit = request.getLimit();
      if (limit < 0) {
        throw new IllegalArgumentException("limit must be >= 0");
      }

      long sent = 0;
      long rowNumber = 0;
      try (Stream<Map<String, Object>> rows = c.read(table.dataset())) {
        Iterator<Map<String, Object>> it = rows.iterator();
        while (it.hasNext()) {
          if (call.isCancelled()) { // client hung up: stop reading the source
            LOG.info("ReadData cancelled by client after " + sent + " rows");
            return;
          }
          Map<String, Object> row = it.next();
          rowNumber++;
          if (!keep.test(row)) {
            continue;
          }
          out.onNext(
              DataRecord.newBuilder()
                  .setRowNumber(rowNumber)
                  .setPayload(Any.pack(RowEncoder.encode(schema, row, projection)))
                  .build());
          sent++;
          if (limit > 0 && sent >= limit) {
            break;
          }
        }
      }
      out.onCompleted();
    });
  }

  // ------------------------------------------------------------------ helpers

  private DataConnector connector(String id) {
    if (id.isBlank()) {
      throw new IllegalArgumentException("connector_id is required");
    }
    return registry
        .find(id)
        .orElseThrow(() -> Status.NOT_FOUND.withDescription("Unknown connector: " + id).asRuntimeException());
  }

  private ResolvedSchema resolve(String connectorId, String dataset) {
    if (dataset.isBlank()) {
      throw new IllegalArgumentException("dataset is required");
    }
    return schemaCache.resolve(connector(connectorId).schema(dataset));
  }

  /** Runs an RPC body and converts Java exceptions into proper gRPC status codes. */
  private static void run(StreamObserver<?> out, Runnable body) {
    try {
      body.run();
    } catch (StatusRuntimeException e) {
      out.onError(e);
    } catch (DatasetNotFoundException e) {
      out.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
    } catch (IllegalArgumentException e) {
      out.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
    } catch (ConnectorException e) {
      LOG.log(Level.WARNING, "Connector failure", e);
      out.onError(Status.UNAVAILABLE.withDescription(e.getMessage()).withCause(e).asRuntimeException());
    } catch (SchemaException e) {
      out.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
    } catch (RuntimeException e) {
      LOG.log(Level.SEVERE, "Unexpected error", e);
      out.onError(Status.INTERNAL.withDescription("Internal error").withCause(e).asRuntimeException());
    }
  }
}
