# 2. Core Concepts

This chapter builds up every idea the project depends on, one layer at a time. Each section ends with
a **"In this project"** box that points to where the idea shows up in the code.

---

## 2.1 What is an API?

An **API (Application Programming Interface)** is a set of rules for how one program asks another
program to do something. You already use APIs all the time:

- `Math.max(3, 7)` in Java — you call it, you get `7`, you don't care how it's implemented.
- `len("hello")` in Python.

Those are *local* APIs: the code runs inside your own program. A **network API** is the same idea, but
the code runs on another machine. The most common style on the web is **REST + JSON**:

```http
GET /customers?country=USA HTTP/1.1
→ 200 OK
[{"customer_id": 1, "name": "Ava Johnson", ...}]
```

REST is great and simple, but it has some pain points for our use-case: JSON is bulky text, there's no
built-in, enforced contract (a typo in a field name only fails at runtime), and every language needs
hand-written code to build URLs and parse responses.

> **In this project:** our API has four operations — `ListConnectors`, `ListDatasets`, `GetSchema`,
> `ReadData` — declared in `proto/dataconnector/v1/data_connector.proto`.

---

## 2.2 RPC — Remote Procedure Call

**RPC** is the idea that calling a function on another computer should *look like* calling a local
function. Instead of "build a URL, send an HTTP request, parse JSON", you write:

```java
ListConnectorsResponse resp = stub.listConnectors(ListConnectorsRequest.getDefaultInstance());
```

The object you call methods on is called a **stub** (or **client stub**). It's a stand-in for the real
service: it has the same method names, but each method secretly does the networking.

```
 Your code                     Stub                 Network               Server
 ─────────  call method  ─►  encode request ─►  bytes over HTTP/2  ─►  decode, run real code
            ◄─ return    ◄─  decode response ◄─  bytes back         ◄─  encode response
```

> **In this project:** `DataConnectorServiceGrpc.newBlockingStub(channel)` (Java) and
> `pb_grpc.DataConnectorServiceStub(channel)` (Python) create stubs. Both are **generated** — nobody
> wrote them by hand.

---

## 2.3 Protocol Buffers ("protobuf")

Protocol Buffers, created at Google, is two things:

1. **A language for describing data** (the `.proto` file), and
2. **A compact binary format** for sending that data.

### The description language

```protobuf
message ConnectorInfo {
  string id = 1;
  string type = 2;
  string description = 3;
}
```

- `message` is like a `class` with only fields (or a Python `dataclass`).
- `string`, `int64`, `double`, `bool` are field types.
- **`= 1`, `= 2`, `= 3` are field numbers, not default values!** They are the field's permanent ID on
  the wire. Names are for humans; numbers are what actually gets sent. This is why you can rename a
  field without breaking anything, but you must **never reuse or change a number**.
- `repeated` means "a list of".
- `enum` is a fixed set of named constants.

### The binary format — let's decode real bytes

Here is a real `Customers` row produced by this project's server
(`customer_id=1, name="Ava", lifetime_value=1520.75, is_active=true`). It is **19 bytes**:

```
08 01 | 12 03 41 76 61 | 31 00 00 00 00 00 c3 97 40 | 38 01
```

| Bytes | Meaning |
|-------|---------|
| `08` | **tag** = (field number 1 << 3) \| wire type 0 (varint) → "field 1, a number follows" |
| `01` | the number 1 → `customer_id = 1` |
| `12` | tag = (2 << 3) \| 2 (length-delimited) → "field 2, a length + bytes follow" |
| `03 41 76 61` | length 3, then ASCII `A v a` → `name = "Ava"` |
| `31` | tag = (6 << 3) \| 1 (64-bit) → "field 6, 8 bytes follow" |
| `00 … 40` | IEEE-754 double 1520.75 → `lifetime_value` |
| `38 01` | tag = (7 << 3) \| 0, value 1 → `is_active = true` |

The same data as JSON is `{"customer_id":1,"name":"Ava","lifetime_value":1520.75,"is_active":true}`
— **about 75 bytes**, four times bigger, and the computer must scan text character-by-character to
parse it. Notice too that fields that weren't set (`email`, `country`…) take **zero bytes**.

The key insight for later: **the bytes don't contain field names or types.** `08 01` only means "field
#1 is 1". To know that field #1 is called `customer_id` and is an `int64`, the reader needs the schema.
Normally the schema is baked into generated code. In this project, it's sent at runtime (chapter 4).

### Code generation

```bash
protoc --java_out=...   data_connector.proto    # → Java classes
protoc --python_out=... data_connector.proto    # → Python classes
```

From one `.proto` file you get, in every language, classes with builders/constructors, getters,
`serialize` / `parse` methods, and type checking.

> **In this project:** Maven runs `protoc` automatically during `mvn package` (see the
> `protobuf-maven-plugin` in `pom.xml`). For Python, `python/generate.sh` runs it.

---

## 2.4 gRPC

**gRPC** is an RPC framework (originally from Google, now open source under the CNCF) that uses
Protocol Buffers for messages and **HTTP/2** for transport. You add a `service` block to the `.proto`:

```protobuf
service DataConnectorService {
  rpc ListConnectors(ListConnectorsRequest) returns (ListConnectorsResponse);
  rpc ReadData(ReadDataRequest) returns (stream DataRecord);
}
```

and the gRPC plugin for `protoc` generates:

- **Server side:** an abstract base class (`DataConnectorServiceImplBase`) with one method per RPC.
  You extend it and fill in the bodies.
- **Client side:** stubs with the same method names.

### Why HTTP/2?

HTTP/2 can carry many requests at once over one TCP connection (**multiplexing**), compresses
headers, and supports long-lived **streams** in both directions. That's what makes streaming RPCs
possible.

### Four kinds of RPC

| Kind | Shape | Example in this project |
|------|-------|-------------------------|
| **Unary** | 1 request → 1 response | `ListConnectors`, `ListDatasets`, `GetSchema` |
| **Server streaming** | 1 request → many responses | **`ReadData`** — one row per message |
| Client streaming | many requests → 1 response | (not used — e.g. uploading a file in chunks) |
| Bidirectional | many ↔ many | (not used — e.g. a chat app) |

Why stream rows? If a table has 10 million rows, a unary call would have to build one gigantic
response in memory first. With streaming, the server sends row 1 while it's still reading row 2, and
the client can start processing immediately — or **cancel** halfway and the server stops reading
(see `call.isCancelled()` in `DataConnectorServiceImpl.readData`).

### Status codes

Every gRPC call ends with a **status**. Instead of HTTP's 404/400/500, gRPC has its own set:

| Code | When we use it |
|------|----------------|
| `OK` | success |
| `NOT_FOUND` | unknown connector or dataset |
| `INVALID_ARGUMENT` | unknown column, `"abc"` compared to a number, negative limit |
| `UNAVAILABLE` | the CSV file couldn't be read, the database is down |
| `FAILED_PRECONDITION` | a schema couldn't be built |
| `INTERNAL` | a bug |

> **In this project:** `DataConnectorServiceImpl.run(...)` translates Java exceptions into these codes.

### Deadlines, channels, interceptors

- A **channel** is a client's connection to a server (`ManagedChannelBuilder.forTarget("localhost:50051")`).
  Create it once and reuse it.
- A **deadline** is "give up if this takes longer than X" — the Java client uses
  `withDeadlineAfter(30, SECONDS)`; the Python client passes `timeout=30`.
- An **interceptor** is code that wraps every call — logging, authentication, metrics.
  See `LoggingInterceptor.java`.

---

## 2.5 Multi-language: what "generated clients" really means

The same `.proto` file is compiled by `protoc` for each language. The generated Java code and the
generated Python code are different *source code*, but they produce and understand **exactly the same
bytes**. So:

```
Java server ── bytes ──► Python client      ✓
Java server ── bytes ──► Java client        ✓
Java server ── bytes ──► Go/C#/Node client  ✓ (just run protoc with that language's plugin)
```

gRPC officially supports C++, Java, Kotlin, Python, Go, C#, Node.js, Ruby, PHP, Dart, Objective-C and
Swift.

> **In this project:** `python/tests/test_cross_language_decoding.py` proves it: it decodes bytes that
> were produced by the **Java** server, using **Python**.

---

## 2.6 Quick self-check

1. In `string name = 2;`, what does the `2` mean? *(field number on the wire, not a default value)*
2. Why can't you decode protobuf bytes without a schema? *(bytes carry only numbers and wire types)*
3. Which RPC kind is `ReadData` and why? *(server streaming — big results, early processing, cancel)*
4. A client asks for dataset `"customerz"`. Which status code comes back? *(NOT_FOUND)*

Next: [3. Architecture](03-architecture.md)
