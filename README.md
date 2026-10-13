# Multi-Language Data Connector API

**gRPC · Protocol Buffers · Java (server) · Java + Python (clients)**

One API that lets a program written in *any* language read data from *many* different
data sources — a SQL database, a folder of CSV files, a folder of JSON documents — without
knowing or caring where the data really lives.

```
   Python app ─┐                                        ┌─► SQL database   (JDBC)
               │   gRPC + Protocol Buffers              │
   Java app  ──┼──────────────────────────► Data ───────┼─► CSV files
               │   one API: ListConnectors,  Connector  │
   Go / C# /  ─┘   ListDatasets, GetSchema,  Server     └─► JSON documents
   Node app        ReadData                  (Java)         (add more: S3, Snowflake, REST…)
```

What it does:

- **A single gRPC service** (`DataConnectorService`) is the one API every consumer uses to read from
  every data connector.
- **Protobuf descriptors are resolved at runtime.** The server builds a Protobuf message type for each
  dataset *while it is running* (from CSV headers, SQL metadata or JSON keys) and ships that schema to
  clients, so any language can decode the rows without recompiling.
- **Generated clients in different languages.** The same `.proto` file generates the Java client and
  the Python client. Both use the same API to list sources, fetch schemas and stream rows.

> **New to all this?** Start with **[docs/01-big-picture.md](docs/01-big-picture.md)**. It's written for
> a first-year college student and assumes only that you've written a little Java or Python.
> There is also a slide deck in [`presentation/`](presentation/) and three narrated videos on YouTube:
> [▶️ Part 1: The Big Picture](https://www.youtube.com/watch?v=tnoX0G_xRtE) · [▶️ Part 2: How It Works](https://www.youtube.com/watch?v=jxn29bWP1PI) · [▶️ Part 3: Build, Test & Run](https://youtu.be/PkzQWX9QnTI)
> (captions and scripts are in [`videos/`](videos/)).

---

## Quick start

### Prerequisites

| Tool | Version | Check with |
|------|---------|-----------|
| Java JDK | 17 or newer | `java -version` |
| Maven | 3.8+ | `mvn -v` |
| Python (optional, for the Python client) | 3.9+ | `python3 --version` |

On a Mac: `brew install openjdk@21 maven python`.

### 1. Build and test the Java server

```bash
mvn clean package          # compiles the .proto, compiles Java, runs the tests, builds the jar
```

Maven downloads `protoc` and the gRPC code generator for your computer automatically
(including Apple Silicon). The result is `target/data-connector-api.jar`.

### 2. Start the server

```bash
java -jar target/data-connector-api.jar          # listens on localhost:50051
```

You'll see the three demo connectors it loaded from `config/connectors.json`.

### 3. Use the Java client (in a second terminal)

```bash
CP=target/data-connector-api.jar
MAIN=com.example.dataconnector.client.DataConnectorClient

java -cp $CP $MAIN                                   # guided demo of every connector
java -cp $CP $MAIN connectors
java -cp $CP $MAIN datasets sales-db
java -cp $CP $MAIN schema sales-db orders
java -cp $CP $MAIN read crm-files customers --where country=USA --columns name,email --limit 5
java -cp $CP $MAIN read product-catalog products --where "price<300" --where in_stock=true
```

### 4. Use the Python client

```bash
cd python
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
./generate.sh                         # generates Python code from the SAME .proto file
python -m dataconnector_client        # guided demo
python -m dataconnector_client read sales-db orders --where amount>200
python -m pytest tests                # includes a Java-encoded → Python-decoded test
```

### 5. (Optional) Poke the API with grpcurl

Server reflection is turned on, so tools can discover the API without the `.proto` file:

```bash
brew install grpcurl
grpcurl -plaintext localhost:50051 list
grpcurl -plaintext -d '{"connector_id":"crm-files","dataset":"customers","limit":2}' \
  localhost:50051 dataconnector.v1.DataConnectorService/ReadData
```

---

## The API at a glance

Defined in [`proto/dataconnector/v1/data_connector.proto`](proto/dataconnector/v1/data_connector.proto):

| RPC | Kind | What it does |
|-----|------|--------------|
| `ListConnectors` | unary | Which data sources does the server know? |
| `ListDatasets` | unary | Which tables/files does one source have? |
| `GetSchema` | unary | Column list **plus a `FileDescriptorSet`** that clients load at runtime |
| `ReadData` | server-streaming | Streams rows as `google.protobuf.Any`, with optional column projection, filters and limit |

Errors use standard gRPC status codes: `NOT_FOUND` (unknown connector/dataset),
`INVALID_ARGUMENT` (bad column, bad filter value), `UNAVAILABLE` (the source failed).

## Project layout

```
proto/dataconnector/v1/data_connector.proto   ← the contract (source of truth for every language)
config/connectors.json                        ← which connectors to start
data/                                         ← demo data: csv/, json/, sql/seed.sql
src/main/java/com/example/dataconnector/
  server/      DataConnectorServer (main), DataConnectorServiceImpl (the 4 RPCs),
               RowFilter, LoggingInterceptor
  connector/   DataConnector (plug-in interface), CsvConnector, JsonConnector,
               JdbcConnector, ConnectorFactory, ConnectorRegistry
  schema/      DynamicSchemaBuilder (TableSchema → protobuf descriptor),
               RowEncoder, SchemaCache, DescriptorResolver (client side)
  client/      DataConnectorClient (Java client + CLI)
src/test/java/                                ← JUnit tests (unit + end-to-end over in-process gRPC)
python/
  generate.sh                                 ← protoc → Python stubs
  dataconnector_client/                       ← Python client, runtime schema decoder, CLI
  tests/                                      ← includes cross-language decoding test
generated-code/                               ← read-only snapshot of what protoc generates (Java, Python,
                                                descriptors, and the schemas the server builds at runtime)
scripts/generate-code.sh                      ← refreshes generated-code/ after a .proto change
docs/                                         ← the learning guide (start at 01)
presentation/                                 ← slide deck (.pptx)
videos/                                       ← YouTube links, captions (.srt), narration scripts, video generator
```

## Learning guide

1. [The big picture](docs/01-big-picture.md) — what problem this solves, in plain English
2. [Core concepts](docs/02-core-concepts.md) — APIs, RPC, Protocol Buffers, gRPC, streaming
3. [Architecture](docs/03-architecture.md) — the pieces and how a request flows through them
4. [Dynamic schemas](docs/04-dynamic-schemas.md) — the clever part: descriptors at runtime
5. [Code walkthrough](docs/05-code-walkthrough.md) — every file, explained
6. [Hands-on labs](docs/06-hands-on-labs.md) — exercises, from "run it" to "write a connector"
7. [Glossary & FAQ](docs/07-glossary-faq.md)

## Adding a new data source

1. Implement `DataConnector` (5 methods: `id`, `type`, `description`, `listDatasets`, `schema`, `read`).
2. Add a `case` for your type in `ConnectorFactory.create`.
3. Add an entry to `config/connectors.json`.

No `.proto` change and no client change is needed — clients discover the new source and
its schema at runtime. Lab 4 in [docs/06-hands-on-labs.md](docs/06-hands-on-labs.md) walks through it.
