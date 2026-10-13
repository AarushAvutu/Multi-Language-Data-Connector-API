# 1. The Big Picture

*Reading time: ~10 minutes. No prior knowledge of gRPC or Protocol Buffers needed.*

## The problem, as a story

Imagine you just got an internship at a mid-sized company. Your manager says:

> "Our customer list is in a bunch of **CSV files** the sales team exports. Orders live in a
> **SQL database**. The product catalog is a **JSON file** the website team maintains. The data
> science team writes **Python**, the backend team writes **Java**, and the mobile team wants in
> too. Everybody keeps writing their own code to read each of these. Can you fix that?"

Right now the situation looks like this — every team writes code for every source:

```
              CSV files     SQL database     JSON files
Python team     ✍️ code        ✍️ code          ✍️ code
Java team       ✍️ code        ✍️ code          ✍️ code
Go team         ✍️ code        ✍️ code          ✍️ code
```

3 languages × 3 sources = **9 separate pieces of code** that all do roughly the same thing, all with
their own bugs, all needing database passwords, all breaking when someone renames a column. Add one
more language and one more source and it becomes 16. This is called the **N × M problem**.

## The solution: one "universal adapter" in the middle

This project puts **one service** in the middle. Every team talks only to the service, and only the
service talks to the data sources:

```
Python team ─┐                      ┌─► CSV files
Java team  ──┼──►  Data Connector ──┼─► SQL database
Go team    ──┘        Service       └─► JSON files
```

Now it's 3 + 3 = **6 pieces**, and the "client" pieces are generated automatically by a tool, so in
practice you only hand-write the 3 connectors. This is the **N + M** solution.

**Analogy: a travel power adapter.** Your laptop charger (the client) doesn't need to know whether
it's plugged into a US, UK, or European socket (the data source). The adapter (our service) has one
side that always looks the same to the charger and a different plug on the other side for each
country.

## Three big ideas you'll learn

### Big idea #1 — A contract everyone agrees on (Protocol Buffers)

Before two programs can talk, they must agree on the *shape* of the messages. We write that agreement
in a small file called a **`.proto` file**. It's like a restaurant **menu**: it lists every dish you can
order (`ReadData`, `GetSchema`…) and exactly what you give and get back.

```protobuf
rpc ReadData(ReadDataRequest) returns (stream DataRecord);
```

A tool called **`protoc`** reads the menu and **writes code for you** in Java, Python, Go, C#, and more.
That's how a Python program and a Java program can talk without anybody hand-writing networking code.

### Big idea #2 — Calling a function on another computer (gRPC)

**gRPC** lets a program call a function that actually runs on a *different* computer, as if it were a
normal local function:

```python
rows = client.read("crm-files", "customers", where=["country=USA"])
```

Behind that one line, gRPC packs your request into compact bytes, sends it across the network over
HTTP/2, the Java server runs the real function, and the answer streams back. You never see the
network.

### Big idea #3 — Describing data the server didn't know about in advance (dynamic descriptors)

Here's the twist that makes this project interesting. Protocol Buffers normally needs to know the
exact fields of every message **before** you compile your program. But our server has no idea what
columns are in a CSV file somebody drops into a folder tomorrow!

So the server **invents the message type while it's running**. It reads the CSV header, builds a
Protobuf "message type" on the fly, and sends a description of that type (a **descriptor**) to the
client. The client — in Java *or* Python — loads that description and uses it to decode the rows.

**Analogy:** You receive a box of LEGO bricks from a friend in another country. Instead of you needing
to already own the instruction booklet, the friend puts the booklet **in the box**. The descriptor is
the booklet; the rows are the bricks.

## What happens when you run it (the 30-second version)

1. **Start the server.** It reads `config/connectors.json` and opens three sources:
   a SQL database (`sales-db`), a folder of CSV files (`crm-files`), a folder of JSON files
   (`product-catalog`).
2. **A client asks "what sources exist?"** → `ListConnectors` → `sales-db, crm-files, product-catalog`.
3. **The client asks "what's in crm-files?"** → `ListDatasets` → `customers, support_tickets`.
4. **The client asks "what do customers look like?"** → `GetSchema` → "7 columns: customer_id (number),
   name (text)… and here's the Protobuf descriptor for a message called `Customers`."
5. **The client says "send me US customers, just name and email"** → `ReadData` → the server
   reads the CSV, filters, encodes each row as a `Customers` message, and **streams** them back one
   by one.
6. **The client decodes each row** using the descriptor from step 4 and prints it.

The client used the **exact same four calls** for the SQL database and the JSON files. That's the
whole point.

## Why do real companies build things like this?

- **Security:** only the service holds database passwords — not every laptop and script.
- **Consistency:** one place to fix bugs, apply rules ("never send the `salary` column"), and log access.
- **Speed:** Protocol Buffers are ~3–10× smaller than JSON and much faster to parse, and gRPC streams
  large results instead of loading them all into memory.
- **Freedom of language:** each team keeps its favorite language.

Products like Google's internal data access layers, Apache Arrow Flight, Trino connectors and
"data virtualization" platforms are grown-up versions of this same idea.

## Where to go next

- Haven't heard of APIs, RPC, or binary encoding? → [2. Core concepts](02-core-concepts.md)
- Want to see the boxes and arrows? → [3. Architecture](03-architecture.md)
- Just want to run it? → the Quick start in the [README](../README.md)
