#!/usr/bin/env bash
# Regenerates generated-code/ — a READ-ONLY snapshot of everything protoc produces from
# proto/dataconnector/v1/data_connector.proto, committed so you can study it without building.
#
# You never need to run this to build or use the project: Maven and python/generate.sh
# generate their own copies. Run it after you change the .proto to refresh the snapshot.
#
# Needs: Maven (Java code), Python with grpcio-tools (Python code + descriptor set).
#   pip install grpcio-tools protobuf
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/generated-code"
PROTO=dataconnector/v1/data_connector.proto
cd "$ROOT"

echo "==> Java: message classes + gRPC service stub (via Maven's protoc and protoc-gen-grpc-java)"
mvn -q -B generate-sources
rm -rf "$OUT/java" && mkdir -p "$OUT/java"
cp -R target/generated-sources/protobuf/java/. "$OUT/java/"
cp -R target/generated-sources/protobuf/grpc-java/. "$OUT/java/"

echo "==> Python: *_pb2.py, *_pb2.pyi and *_pb2_grpc.py (via grpcio-tools)"
rm -rf "$OUT/python" && mkdir -p "$OUT/python"
python -m grpc_tools.protoc -I proto \
  --python_out="$OUT/python" --pyi_out="$OUT/python" --grpc_python_out="$OUT/python" "proto/$PROTO"

echo "==> Descriptor set (.protoset) + human-readable dump"
mkdir -p "$OUT/descriptor"
python -m grpc_tools.protoc -I proto --include_imports \
  --descriptor_set_out="$OUT/descriptor/data_connector.protoset" "proto/$PROTO"
python - "$OUT" <<'PY'
import sys
from google.protobuf import descriptor_pb2, text_format
out = sys.argv[1]
fds = descriptor_pb2.FileDescriptorSet.FromString(open(f"{out}/descriptor/data_connector.protoset", "rb").read())
own = descriptor_pb2.FileDescriptorSet(file=[f for f in fds.file if f.name.startswith("dataconnector/")])
open(f"{out}/descriptor/data_connector.descriptor.txt", "w").write(text_format.MessageToString(own))
PY

echo "==> Runtime-built (dynamic) schema examples"
python scripts/dump_dynamic_examples.py

echo "Done. See generated-code/README.md"
