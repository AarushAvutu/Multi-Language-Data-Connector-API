"""Command line interface — mirrors the Java client.

    python -m dataconnector_client                      # guided demo
    python -m dataconnector_client connectors
    python -m dataconnector_client datasets crm-files
    python -m dataconnector_client schema sales-db orders
    python -m dataconnector_client read crm-files customers --where country=USA --columns name,email --limit 5
"""

from __future__ import annotations

import argparse
import json
import sys

import grpc

from .client import DataConnectorClient


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="python -m dataconnector_client")
    p.add_argument("--target", default="localhost:50051", help="server host:port")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("demo")
    sub.add_parser("connectors")
    d = sub.add_parser("datasets"); d.add_argument("connector")
    s = sub.add_parser("schema"); s.add_argument("connector"); s.add_argument("dataset")
    r = sub.add_parser("read"); r.add_argument("connector"); r.add_argument("dataset")
    r.add_argument("--columns", help="comma separated column list")
    r.add_argument("--where", action="append", default=[], help="e.g. country=USA, price>10, name~lap")
    r.add_argument("--limit", type=int, default=0)
    args = p.parse_args(argv)

    try:
        with DataConnectorClient(args.target) as client:
            cmd = args.cmd or "demo"
            if cmd == "connectors":
                for c in client.connectors():
                    print(f"{c.id:<16} {c.type:<5} {c.description}")
            elif cmd == "datasets":
                print("\n".join(client.datasets(args.connector)))
            elif cmd == "schema":
                resp = client.schema(args.connector, args.dataset)
                print("message type:", resp.message_type)
                for f in resp.fields:
                    print(f"  {f.number:>2}  {f.name:<18} {f.type:<2} {'nullable' if f.nullable else ''}")
            elif cmd == "read":
                cols = args.columns.split(",") if args.columns else None
                n = 0
                for row in client.read(args.connector, args.dataset, cols, args.where, args.limit):
                    print(json.dumps(row)); n += 1
                print(f"({n} rows)")
            else:
                demo(client)
    except grpc.RpcError as e:
        print(f"RPC failed: {e.code().name} - {e.details()}", file=sys.stderr)
        return 1
    return 0


def demo(client: DataConnectorClient) -> None:
    print("=== Same API, three very different data sources (from Python!) ===")
    for c in client.connectors():
        print(f"\n[{c.id}] type={c.type}")
        for ds in client.datasets(c.id):
            dec = client.decoder(c.id, ds)
            print(f"  dataset {ds:<16} -> runtime class {dec.descriptor.full_name}")
            for row in client.read(c.id, ds, limit=2):
                print("     ", json.dumps(row))
    print("\n=== Filter + projection: products under $300 that are in stock ===")
    for row in client.read("product-catalog", "products", columns=["name", "price"],
                           where=["price<300", "in_stock=true"]):
        print("  ", row)


if __name__ == "__main__":
    sys.exit(main())
