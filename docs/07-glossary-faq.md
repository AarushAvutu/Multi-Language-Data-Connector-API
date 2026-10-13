# 7. Glossary & FAQ

## Glossary

| Term | Plain-English meaning |
|------|----------------------|
| **API** | The agreed way one program asks another to do something. |
| **RPC** (Remote Procedure Call) | Calling a function that runs on another computer as if it were local. |
| **gRPC** | Google's open-source RPC framework: protobuf messages over HTTP/2, with generated code for ~12 languages. |
| **Protocol Buffers / protobuf** | A language for describing structured data plus a compact binary format for it. |
| **`.proto` file** | The text file where messages and services are declared — the contract. |
| **`protoc`** | The protobuf compiler. Turns `.proto` files into code for a chosen language. |
| **Message** | A protobuf record type (like a class with only fields). |
| **Field number** | The permanent numeric ID of a field on the wire (`= 1`, `= 2`). Never change or reuse one. |
| **Service** | A group of RPC methods in a `.proto` file. |
| **Stub** | Generated client object whose methods perform the remote calls. |
| **`ImplBase`** | Generated server base class you extend to implement the service (Java). |
| **Channel** | A client's (reusable) connection to a server. |
| **Unary RPC** | One request, one response. |
| **Server streaming RPC** | One request, a stream of responses (our `ReadData`). |
| **`StreamObserver`** | Java callback object: `onNext`, `onError`, `onCompleted`. |
| **Status code** | How a gRPC call ends: `OK`, `NOT_FOUND`, `INVALID_ARGUMENT`, `UNAVAILABLE`… |
| **Deadline** | A time limit after which the client gives up on a call. |
| **Interceptor** | Code that runs around every RPC (logging, auth, metrics). |
| **Server reflection** | A built-in service that lets tools discover your API at runtime. |
| **HTTP/2** | Modern HTTP: binary, multiplexed streams over one connection. gRPC's transport. |
| **Descriptor** | A machine-readable description of a protobuf type (names, numbers, types of fields). |
| **`FileDescriptorProto`** | A descriptor for a whole `.proto` file, itself a protobuf message. |
| **`FileDescriptorSet`** | A list of `FileDescriptorProto`s — what `GetSchema` sends. |
| **`DynamicMessage`** (Java) | A protobuf message whose type is given by a descriptor at runtime. |
| **`DescriptorPool`** (Python) | A registry where descriptors are loaded so classes can be made from them. |
| **`google.protobuf.Any`** | An envelope: a type URL + the bytes of any message. |
| **Type URL** | `type.googleapis.com/<full.message.Name>` — says what's inside an `Any`. |
| **proto3 `optional`** | Gives a field presence (`HasField`), so "unset/NULL" differs from `0`/`""`. |
| **Connector** | Our plug-in that knows how to read one kind of source. |
| **Dataset** | One table / file inside a connector. |
| **Projection** | Choosing which columns to return. |
| **Predicate / filter** | A yes/no test applied to each row. |
| **Pushdown** | Making the source (e.g. the database) apply the filter instead of the server. |
| **JDBC** | Java's standard API for SQL databases. |
| **H2** | A small SQL database written in Java; we run it in memory for the demo. |
| **Maven** | Java build tool: downloads libraries, runs `protoc`, compiles, tests, packages. |
| **Fat / shaded jar** | One jar containing your code *and* all libraries, runnable with `java -jar`. |

## FAQ

**Why not just use REST and JSON?**
REST is a fine choice for many public APIs. gRPC wins here because of (1) a strict, generated contract
in every language, (2) smaller and faster binary messages, (3) first-class streaming for large reads,
and (4) built-in deadlines, cancellation and status codes. The trade-off: browsers can't call gRPC
directly without a proxy (gRPC-Web), and binary payloads are harder to eyeball — that's why we enabled
reflection for `grpcurl`.

**If the rows are dynamic anyway, why use protobuf at all? Why not send JSON rows?**
You keep protobuf's compact encoding and its type information (an `int64` stays an integer — JSON
can't safely represent integers above 2⁵³), the schema is explicit and machine-checked, and every
language already has protobuf libraries that know how to load descriptors.

**Isn't calling `GetSchema` before every read slow?**
Clients cache the decoder per dataset (`self._decoders` in Python). The server caches descriptors too.

**What happens if the CSV changes between `GetSchema` and `ReadData`?**
The server encodes rows with the *new* descriptor; the `type_url` stays the same name but fields may
differ. Old fields keep their numbers if the column order is unchanged; unknown fields are skipped by
the client. A production version would include a schema fingerprint in each `DataRecord` so the client
knows to refresh — a nice extension project.

**Why are dates sent as strings?**
To keep the type system to four universally-understood types. You could add `google.protobuf.Timestamp`
support as an extension.

**Is this secure?**
The demo uses plaintext on localhost. For real use: enable TLS, add an authentication interceptor,
and restrict which datasets/columns each caller may read. The code already defends against path
traversal (CSV/JSON) and SQL injection via dataset names (JDBC).

**Can I write the *server* in another language too?**
Yes. Any language with gRPC support can implement `DataConnectorService` from the same `.proto`, and
the existing Java and Python clients would work with it unchanged.

**Where is this pattern used in industry?**
Data-access gateways at large companies, Apache Arrow Flight (gRPC for columnar data), Trino/Presto
connectors, Google BigQuery Storage Read API (gRPC streaming with a schema sent first — very similar!),
and data-virtualization products.
