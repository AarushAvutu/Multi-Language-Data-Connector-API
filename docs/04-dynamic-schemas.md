# 4. Dynamic Schemas: Protobuf Descriptors at Runtime

This is the most unusual — and most interesting — part of the project. Take your time with it.

## 4.1 The problem

Normal protobuf workflow:

```
write .proto  →  run protoc  →  compile your program  →  run it
```

The field list is frozen **before** the program runs. But our server must serve:

- a CSV file someone adds to `data/csv/` next week,
- a SQL table the DBA creates tomorrow,
- a JSON file with keys we've never seen.

We can't write `customers.proto`, `orders.proto`, `products.proto` ahead of time — we don't know what
datasets will exist. And we definitely don't want to recompile and redeploy every client in every
language each time a column is added.

## 4.2 The key insight: a schema is just data

When `protoc` compiles a `.proto` file, the very first thing it produces internally is a
**`FileDescriptorProto`** — a protobuf message (defined in `google/protobuf/descriptor.proto`) that
*describes* the `.proto` file: its package, its messages, each field's name, number and type.

Because a descriptor is itself a protobuf message, it can be:

- **built in code** (no `protoc` needed),
- **serialized to bytes and sent over the network**,
- **loaded on the other side** to recreate the message type.

Every protobuf library (Java, Python, Go, C++, C#…) can do the "load" step. That's the trick.

## 4.3 Server side: building a descriptor (Java)

`DynamicSchemaBuilder.build(TableSchema)` does in code what `protoc` does from a file. Simplified:

```java
DescriptorProto.Builder message = DescriptorProto.newBuilder().setName("Customers");

int number = 1;
for (Column column : schema.columns()) {
  message.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_" + fieldName));  // see 4.6
  message.addField(FieldDescriptorProto.newBuilder()
      .setName(fieldName)                    // "lifetime_value"
      .setNumber(number++)                   // 6
      .setType(toProtoType(column.type()))   // TYPE_DOUBLE
      .setLabel(LABEL_OPTIONAL)
      .setProto3Optional(true)
      .setOneofIndex(...));
}

FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
    .setName("dynamic/crm_files/customers.proto")
    .setPackage("dataconnector.dynamic.crm_files")
    .setSyntax("proto3")
    .addMessageType(message)
    .build();

FileDescriptor file = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]); // validate + link
Descriptor descriptor = file.findMessageTypeByName("Customers");
```

That `fileProto` is equivalent to this `.proto` file that **nobody ever wrote**:

```protobuf
syntax = "proto3";
package dataconnector.dynamic.crm_files;

message Customers {
  optional int64  customer_id    = 1;
  optional string name           = 2;
  optional string email          = 3;
  optional string country        = 4;
  optional int64  signup_year    = 5;
  optional double lifetime_value = 6;
  optional bool   is_active      = 7;
}
```

### Making names legal

Column names in the wild are messy: `"Lifetime Value ($)"`, `"2024 Sales"`, `"customerID"`.
Protobuf identifiers must be letters, digits and underscores, not starting with a digit.
`toSnakeIdentifier` cleans them up:

| Raw column | Field name |
|------------|-----------|
| `Lifetime Value ($)` | `lifetime_value` |
| `customerID` | `customer_id` |
| `2024 Sales` | `f_2024_sales` |
| two columns both becoming `name` | `name`, `name_2` |

The dataset name becomes the message name in PascalCase (`support_tickets` → `SupportTickets`) and
the connector id becomes the package (`crm-files` → `dataconnector.dynamic.crm_files`), so two
connectors that both have a `customers` dataset never clash.

## 4.4 Server side: building a row (Java)

With a `Descriptor` in hand, protobuf-java's `DynamicMessage` acts like a generated class whose fields
you set by descriptor instead of by method name. `RowEncoder` does:

```java
DynamicMessage.Builder b = DynamicMessage.newBuilder(descriptor);
b.setField(descriptor.findFieldByName("name"), "Ava Johnson");
b.setField(descriptor.findFieldByName("lifetime_value"), 1520.75);
DynamicMessage row = b.build();

Any payload = Any.pack(row);
// payload.type_url = "type.googleapis.com/dataconnector.dynamic.crm_files.Customers"
// payload.value    = the protobuf bytes of the row
```

`google.protobuf.Any` is a standard "envelope" message: a **type URL** (which message is inside) plus
the **bytes**. Our `.proto` declares `google.protobuf.Any payload = 2;` in `DataRecord`, which is how a
fixed API can carry rows of *any* shape.

## 4.5 Client side: loading the descriptor

### Java (`DescriptorResolver`)

```java
GetSchemaResponse s = stub.getSchema(...);
Descriptor d = DescriptorResolver.resolve(s.getDescriptorSet(), s.getMessageType());
// inside: FileDescriptor.buildFrom(fileProto, dependencies) — the same call the server used

DynamicMessage row = DynamicMessage.parseFrom(d, record.getPayload().getValue());
Object name = row.getField(d.findFieldByName("name"));
```

### Python (`dataconnector_client/schema.py`)

```python
pool = descriptor_pool.DescriptorPool()          # a private registry of types
for file_proto in resp.descriptor_set.file:
    pool.Add(file_proto)                         # dependency order handled by the helper
descriptor = pool.FindMessageTypeByName(resp.message_type)
Customers = message_factory.GetMessageClass(descriptor)   # a brand-new Python class!

row = Customers()
record.payload.Unpack(row)      # checks type_url, then parses bytes
row.name                        # 'Ava Johnson'
row.HasField("lifetime_value")  # False for Lucas O'Brien → the CSV cell was empty (NULL)
```

`GetMessageClass` returns a real class — the same kind of class `protoc --python_out` would generate.
You can use attribute access, `HasField`, `SerializeToString`, `json_format.MessageToDict`, everything.

### Any other language

| Language | Load descriptor | Make a message |
|----------|----------------|----------------|
| Go | `protodesc.NewFiles(fdset)` | `dynamicpb.NewMessage(md)` |
| C# | `FileDescriptor.BuildFromByteStrings(...)` | `descriptor.Parser.ParseFrom(...)` |
| C++ | `DescriptorPool::BuildFile` | `DynamicMessageFactory::GetPrototype` |
| Node.js | `protobufjs` `Root.fromDescriptor(...)` | `root.lookupType(name).decode(bytes)` |

## 4.6 Why every field is `optional`

In proto3, a plain `int64 signup_year = 5;` can't tell "0" from "not set" — both are encoded as
*nothing*. But databases have `NULL`, and `NULL` is not `0`! Marking a field `optional` gives it
**presence tracking**: `HasField` returns false when the server never set it.

Under the hood, proto3 `optional` is represented in a descriptor as a **synthetic oneof** containing
just that one field, named `_<field>`, with `proto3_optional = true`. That's why `DynamicSchemaBuilder`
adds a `OneofDescriptorProto` per field. (`protoc` does exactly the same when it sees `optional`.)

## 4.7 The schema cache

Building a descriptor is cheap but not free, and clients may call `GetSchema` often. `SchemaCache`
keys descriptors by `connectorId/dataset` and stores the `TableSchema` they were built from. On the next
request, if the freshly-read `TableSchema` **equals** the cached one, the cached descriptor is reused.
If someone added a column to the CSV, the schemas differ and a new descriptor is built automatically.

## 4.8 Static vs dynamic: when to use which

| | Static (normal `.proto` messages) | Dynamic (this project's rows) |
|---|---|---|
| Schema known | at compile time | at runtime |
| Type safety | compiler checks `row.getName()` | runtime lookups by name |
| Speed | fastest | slightly slower (reflection) |
| Flexibility | needs recompilation to change | adapts to any dataset |
| Used here for | the API itself (`ReadDataRequest`, `Filter`, …) | the data rows |

This project uses **both**: a stable, statically-typed API envelope, with dynamically-typed rows
inside. That combination is the pattern to remember.

## 4.9 Try it yourself

```bash
# Add a new CSV file while the server is running:
printf 'city,population,is_capital\nParis,2100000,true\nLyon,520000,false\n' > data/csv/cities.csv

python -m dataconnector_client schema crm-files cities
python -m dataconnector_client read crm-files cities --where "population>1000000"
```

No restart, no `.proto` edit, no client regeneration — and the Python client receives a brand-new
message type, `dataconnector.dynamic.crm_files.Cities`.

Next: [5. Code walkthrough](05-code-walkthrough.md)
