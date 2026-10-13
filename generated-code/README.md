# Generated code: what `protoc` writes for you

This folder is a **read-only snapshot** of the code generated from
[`proto/dataconnector/v1/data_connector.proto`](../proto/dataconnector/v1/data_connector.proto).
It's committed so you can open it and see exactly what the compiler produces, without installing
or building anything.

> **Don't edit these files, and don't import them from the project.** The build makes its own fresh
> copies every time: Maven writes Java into `target/generated-sources/protobuf/`, and
> `python/generate.sh` writes Python into `python/generated/`. If you change the `.proto`, run
> `scripts/generate-code.sh` to refresh this snapshot.

```
generated-code/
├── java/com/example/dataconnector/v1/   26 files, ~11,000 lines, from a ~150-line .proto
├── python/dataconnector/v1/             data_connector_pb2.py (+ .pyi type hints)
├── descriptor/                          the API's own descriptor (binary + readable text)
└── dynamic-examples/                    descriptors the server builds AT RUNTIME, with readable versions
```

---

## 1. Java (`java/`), from `protoc --java_out`

For each `message` in the `.proto` you get **two files**:

| File | What it is |
|------|-----------|
| `ReadDataRequest.java` | The immutable message class plus its nested `Builder` |
| `ReadDataRequestOrBuilder.java` | An interface with the getters, shared by the message and its builder |

A few of the ~1,450 generated lines of `ReadDataRequest.java`:

```java
public java.lang.String getConnectorId()                         // string connector_id = 1;
public com.google.protobuf.ProtocolStringList getColumnsList()   // repeated string columns = 3;
public java.util.List<Filter> getFiltersList()                   // repeated Filter filters = 4;
public int getLimit()                                            // int32 limit = 5;

public static Builder newBuilder()                               // create one
public Builder toBuilder()                                       // copy and modify
public static ReadDataRequest parseFrom(byte[] data)             // bytes → object

// inside ReadDataRequest.Builder:
public Builder setLimit(int value)
public Builder addFilters(Filter value)
```

This is why the server and client code can write
`ReadDataRequest.newBuilder().setConnectorId("crm-files").setLimit(5).build()`. Every one of those
methods was generated.

Other files:

- **`ColumnType.java`**: a Java `enum` with `forNumber(int)` and `getNumber()`, plus an extra
  `UNRECOGNIZED` constant for numbers from a newer `.proto`.
- **`Filter.java`**: contains the nested enum `Filter.Operator`, because it was declared inside
  `message Filter` in the `.proto`.
- **`DataConnectorProto.java`**: the "outer class" (named by `option java_outer_classname`). It holds
  the whole `.proto` file's **descriptor**, embedded as bytes, and `getDescriptor()` returns it. The
  server builds the same kind of descriptor at runtime for CSV/SQL/JSON datasets (see
  `dynamic-examples/` below).

**One file is missing from this snapshot: `DataConnectorServiceGrpc.java`.** That's the gRPC service
stub with `DataConnectorServiceImplBase`, `newBlockingStub(...)` and friends. It comes from a separate
plugin, `protoc-gen-grpc-java`, which is only published on Maven Central. Maven Central was
unreachable when this snapshot was made. Run `mvn generate-sources` and you'll find it at
`target/generated-sources/protobuf/grpc-java/com/example/dataconnector/v1/DataConnectorServiceGrpc.java`.
`scripts/generate-code.sh` copies it in here too.

## 2. Python (`python/`), from `protoc --python_out --pyi_out`

**`data_connector_pb2.py`** is surprisingly short (~60 lines). Modern Python protobuf doesn't write a
class per message. Instead it embeds the serialized descriptor
(`AddSerializedFile(b'\n%dataconnector/v1/...')`) and asks the protobuf runtime to **build the classes
when the module is imported**. That's the same "descriptor → class" trick our Python client uses for
dynamic rows in `dataconnector_client/schema.py`.

**`data_connector_pb2.pyi`** holds type hints for your editor's autocomplete:

```python
class ReadDataRequest(_message.Message):
    connector_id: str
    dataset: str
    columns: _containers.RepeatedScalarFieldContainer[str]
    filters: _containers.RepeatedCompositeFieldContainer[Filter]
    limit: int
    def __init__(self, connector_id: _Optional[str] = ..., dataset: _Optional[str] = ...,
                 columns: _Optional[_Iterable[str]] = ..., filters: ..., limit: _Optional[int] = ...) -> None: ...
```

**Also missing here: `data_connector_pb2_grpc.py`** (with `DataConnectorServiceStub`). It's written
by the gRPC plugin bundled in the `grpcio-tools` package from PyPI, which was also unreachable when
this snapshot was made. `python/generate.sh` and `scripts/generate-code.sh` produce it.

## 3. Descriptor (`descriptor/`)

| File | What it is |
|------|-----------|
| `data_connector.protoset` | Binary `FileDescriptorSet` for the API, including its imports (`any.proto`, `descriptor.proto`). Tools can use it in place of the `.proto`, e.g. `grpcurl -protoset generated-code/descriptor/data_connector.protoset -plaintext localhost:50051 list` |
| `data_connector.descriptor.txt` | The same descriptor for **our** file only, printed as text. Compare it side by side with the `.proto`: every `message`, `field`, `number` and `type` is there as data. |

## 4. Runtime-built schemas (`dynamic-examples/`)

These are the most important files in this folder for understanding the project. Nobody ever wrote
a `.proto` for the `customers` CSV file or the `orders` SQL table. The server's
`DynamicSchemaBuilder` creates their descriptors **while it is running**, and `GetSchema` sends them
to clients.

| File | What it is |
|------|-----------|
| `crm_files_customers.descriptor.txt` | The exact `FileDescriptorSet` a client receives for `GetSchema("crm-files", "customers")`, decoded to text |
| `crm_files_customers.proto.txt` | The same thing written as the `.proto` file it's equivalent to |
| `sales_db_orders.*` | The same pair for the SQL table `orders` |

```protobuf
// crm_files_customers.proto.txt (the .proto file that NOBODY wrote)
package dataconnector.dynamic.crm_files;
message Customers {
  optional int64  customer_id    = 1;
  optional string name           = 2;
  ...
  optional double lifetime_value = 6;
  optional bool   is_active      = 7;
}
```

In the `.descriptor.txt` you can spot details from [chapter 4](../docs/04-dynamic-schemas.md):
`proto3_optional: true` on every field, and one synthetic `oneof_decl { name: "_customer_id" }` per
field. That's how proto3 `optional` is represented inside a descriptor.

These files were made from the Java server's real output, saved in `python/tests/fixtures/`, by
`scripts/dump_dynamic_examples.py`.

---

## Quick exercise

1. Open `data_connector.proto` and `java/.../ReadDataRequest.java` side by side. For each of the 5
   fields, find its getter, its builder setter and its `*_FIELD_NUMBER` constant.
2. Add `bool include_row_numbers = 6;` to `ReadDataRequest`, run `scripts/generate-code.sh`, and
   use `git diff generated-code/` to see everything one line of `.proto` changes.
