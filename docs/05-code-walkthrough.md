# 5. Code Walkthrough

A guided tour of every file, in the order you should read them. Open each file side by side with this
page. Line counts are approximate.

---

## Step 1 — The contract: `proto/dataconnector/v1/data_connector.proto` (~150 lines)

Read this first; everything else implements it.

- `package dataconnector.v1;` — the protobuf namespace. The `v1` lets you add a `v2` later without
  breaking old clients.
- `option java_package = "com.example.dataconnector.v1";` — where the generated Java classes go.
- `option java_multiple_files = true;` — one `.java` file per message instead of one giant file.
- `import "google/protobuf/any.proto";` and `descriptor.proto` — two of protobuf's "well-known types".
- `service DataConnectorService { ... }` — the four RPCs.
- `ReadDataRequest` has `repeated string columns` (projection), `repeated Filter filters`, and `limit`.
- `Filter` has a **nested enum** `Operator`. Note the first value is always `..._UNSPECIFIED = 0`: in
  proto3 the zero value is the default, so making it "unspecified" lets the server detect "you forgot
  to set this".
- `DataRecord.payload` is `google.protobuf.Any` — the envelope for dynamic rows.

**Generated from it** (in `target/generated-sources/protobuf/` after `mvn compile`):
`ReadDataRequest.java`, `Filter.java`, … and `DataConnectorServiceGrpc.java` (stubs + `ImplBase`).

> Want to read the generated code without building? Open
> [`generated-code/`](../generated-code/README.md). It has a committed snapshot of the Java and
> Python output with an annotated tour, plus the descriptors the server builds at runtime for the
> `customers` CSV and the `orders` table, shown as the `.proto` files nobody wrote.

---

## Step 2 — The connector plug-in interface: `connector/DataConnector.java`

```java
public interface DataConnector extends AutoCloseable {
  String id();  String type();  String description();
  List<String> listDatasets();
  TableSchema schema(String dataset);
  Stream<Map<String, Object>> read(String dataset);
}
```

Things to notice:

- `read` returns a **`Stream`**, not a `List`, so a 10-GB table doesn't have to fit in memory.
- The Javadoc says callers must **close** the stream — that's how a CSV file handle or SQL cursor gets
  released. The service uses try-with-resources: `try (Stream<...> rows = c.read(...)) { ... }`.
- `extends AutoCloseable` lets the server close all connectors at shutdown.

Related small files: `DatasetNotFoundException` (→ `NOT_FOUND`), `ConnectorException` (→ `UNAVAILABLE`).

---

## Step 3 — Three connectors

### `CsvConnector.java`

- `listDatasets()` lists `*.csv` files in the folder.
- `schema()` reads all lines and uses **`TypeInference`** to guess each column's type:
  start with "unknown", look at each non-empty value, and **widen** as needed:
  `INT64 + DOUBLE → DOUBLE`, `anything + text → STRING`. Empty cells mark the column `nullable`.
- `read()` returns `reader.lines().skip(1).map(parseLine).map(toRow)` with `.onClose(reader::close)`.
- `parseLine()` is a tiny CSV parser that handles `"quoted, commas"` and `""` escaped quotes.
- `fileFor()` blocks **path traversal**: a dataset named `../../etc/passwd` resolves outside the folder
  and is rejected. (Always validate user input that becomes a file path!)

### `JsonConnector.java`

- Uses Gson to parse a JSON **array of objects**.
- Infers types from JSON value kinds; a key missing from some objects or set to `null` → nullable.
- Nested arrays/objects (like `"tags": ["school", "portable"]`) are passed through as JSON text.

### `JdbcConnector.java`

- Works with **any** JDBC database. The demo uses **H2**, an in-memory database written in Java, seeded
  from `data/sql/seed.sql`, so there's nothing to install.
- Schema comes from `DatabaseMetaData.getColumns(...)` — the database tells us the types; no guessing.
  `fromSqlType` maps `java.sql.Types` to our four column types.
- `read()` wraps a `ResultSet` in a `Spliterator` so rows are pulled from the database cursor one at a
  time, and `onClose` closes the result set, statement and connection.
- `rs.wasNull()` is how JDBC tells you a numeric column was `NULL` (since `getLong` returns `0`).
- Table names are quoted (`"ORDERS"`) and must match a real table, preventing SQL injection through the
  dataset name.

### `ConnectorFactory.java` & `ConnectorRegistry.java`

The factory reads `config/connectors.json` and `switch`es on `"type"` to create connectors — the
**Factory pattern**. The registry is a `Map<id, connector>`.

---

## Step 4 — The schema layer (`schema/`)

| File | Role |
|------|------|
| `Column`, `TableSchema` | Java **records**: immutable data carriers with auto-generated `equals`/`hashCode` (which `SchemaCache` relies on). |
| `DynamicSchemaBuilder` | `TableSchema` → `FileDescriptorProto` → `FileDescriptor` → `Descriptor`. Explained in [chapter 4](04-dynamic-schemas.md). |
| `ResolvedSchema` | Bundles the descriptor, the serializable `FileDescriptorSet`, the `FieldInfo` list and a column→field map. |
| `RowEncoder` | `Map` row → `DynamicMessage`. Skips nulls (leaves the field unset) and honours the projection list. `coerce` converts e.g. `"42"` → `42L`. |
| `SchemaCache` | `ConcurrentHashMap.compute(...)` — thread-safe "reuse if unchanged, else rebuild". |
| `DescriptorResolver` | Client-side: `FileDescriptorSet` → `Descriptor`, and `Any` → `DynamicMessage` with a type check. Builds files in dependency order (recursion). |

---

## Step 5 — The service: `server/DataConnectorServiceImpl.java`

```java
public final class DataConnectorServiceImpl
    extends DataConnectorServiceGrpc.DataConnectorServiceImplBase {
```

`ImplBase` was generated. We **override** one method per RPC. gRPC-Java uses the *observer pattern*:
instead of `return`ing a value, you push values into a `StreamObserver`:

```java
out.onNext(response);   // send a message (call many times for streaming)
out.onCompleted();      // finish successfully
// or
out.onError(Status.NOT_FOUND.withDescription("...").asRuntimeException());
```

Walk through `readData`:

1. Cast `out` to `ServerCallStreamObserver` so we can check `isCancelled()`.
2. Look up connector and resolved schema (may throw → mapped to a status code).
3. Validate projection columns; compile filters with `RowFilter.compile`.
4. `try (Stream rows = c.read(...))` → iterate → filter → encode → `Any.pack` → `onNext`.
5. Stop early at `limit` or if the client cancelled.
6. `onCompleted()`.

The private `run(...)` helper wraps every RPC body and converts exceptions to status codes — one
place to get error handling right.

### `RowFilter.java`

Compiles a list of `Filter` messages into one `Predicate<Map<String,Object>>` by chaining `.and(...)`.
It validates **up front** (unknown column, wrong value type, `CONTAINS` on a number) so a bad request
fails fast with `INVALID_ARGUMENT` before any data is read. Numbers are compared as doubles, so
`signup_year > 2020.5` works.

### `LoggingInterceptor.java`

Wraps the `ServerCall` and overrides `close(Status, Metadata)` — the moment every call finishes — to
log method, status code and duration:

```
17:02:11 INFO rpc - dataconnector.v1.DataConnectorService/ReadData -> OK (12 ms)
```

### `DataConnectorServer.java`

`main` → load config → `NettyServerBuilder.forPort(50051)` → add our service (wrapped by the
interceptor), **reflection** and **health** services → `start()` → `awaitTermination()`. A JVM
**shutdown hook** stops the server cleanly on Ctrl-C.

---

## Step 6 — The Java client: `client/DataConnectorClient.java`

- `ManagedChannelBuilder.forTarget("localhost:50051").usePlaintext().build()` — connection
  (plaintext = no TLS, fine for localhost).
- `DataConnectorServiceGrpc.newBlockingStub(channel)` — a **blocking** stub: calls wait for the answer.
  (There are also async and future stubs.)
- `read(...)`: `getSchema` → `DescriptorResolver.resolve` → iterate the streaming `readData` iterator
  → `DescriptorResolver.unpack` each row.
- The CLI parses `--where country=USA`, `--columns a,b`, `--limit 5`.

---

## Step 7 — The Python client: `python/dataconnector_client/`

| File | What it does |
|------|--------------|
| `__init__.py` | Adds `python/generated` to `sys.path` so generated modules import correctly. |
| `schema.py` | `RowDecoder`: `DescriptorPool` + `GetMessageClass` + `Any.Unpack` + `to_dict`. Only needs `protobuf`. |
| `client.py` | `DataConnectorClient` wraps `DataConnectorServiceStub`, caches one decoder per dataset, and `read()` is a **generator** (`yield`) so rows are processed as they stream in. |
| `__main__.py` | `python -m dataconnector_client ...` CLI mirroring the Java one. |

Compare `client.read` (Python) with `DataConnectorClient.read` (Java) — same four steps, different
syntax. That's the multi-language promise.

---

## Step 8 — Tests

| Test | What it proves |
|------|----------------|
| `DynamicSchemaBuilderTest` | Names are sanitized; a descriptor survives a bytes round-trip; NULLs stay unset; projection works; cache rebuilds on change; wrong type URLs are rejected. |
| `ConnectorsTest` | CSV type inference & quoted cells; path-traversal protection; JSON nested values; JDBC metadata, nullability and row streaming. |
| `DataConnectorServiceTest` | **End-to-end** over an *in-process* gRPC channel: all three sources through one API, filters, projection, limit, and every error status code. |
| `python/tests/test_cross_language_decoding.py` | Bytes **produced by the Java server** decode correctly in **Python**. |

Run them with `mvn test` and `cd python && python -m pytest tests`.

Next: [6. Hands-on labs](06-hands-on-labs.md)
