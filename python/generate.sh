#!/usr/bin/env bash
# Generates Python code from the SAME .proto file the Java server uses.
#   data_connector_pb2.py       -> message classes (ReadDataRequest, DataRecord, ...)
#   data_connector_pb2_grpc.py  -> the client stub (DataConnectorServiceStub)
#   data_connector_pb2.pyi      -> type hints for your editor
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p generated
python -m grpc_tools.protoc \
  -I ../proto \
  --python_out=generated \
  --pyi_out=generated \
  --grpc_python_out=generated \
  ../proto/dataconnector/v1/data_connector.proto
echo "Generated Python stubs in python/generated/"
