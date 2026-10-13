"""A friendly wrapper around the generated gRPC stub.

    from dataconnector_client.client import DataConnectorClient
    with DataConnectorClient("localhost:50051") as client:
        for row in client.read("crm-files", "customers", where=["country=USA"], limit=5):
            print(row)
"""

from __future__ import annotations

from typing import Dict, Iterator, List, Optional

import grpc

from . import schema as _schema  # noqa: F401  (sets up sys.path for generated code)
from dataconnector.v1 import data_connector_pb2 as pb
from dataconnector.v1 import data_connector_pb2_grpc as pb_grpc

# "price>10" style filter syntax shared with the Java CLI. Order matters ("!=" before "=").
_OPERATORS = [
    ("!=", pb.Filter.NOT_EQUALS),
    ("=", pb.Filter.EQUALS),
    (">", pb.Filter.GREATER_THAN),
    ("<", pb.Filter.LESS_THAN),
    ("~", pb.Filter.CONTAINS),
]


def parse_filter(expr: str) -> pb.Filter:
    for symbol, op in _OPERATORS:
        idx = expr.find(symbol)
        if idx > 0:
            return pb.Filter(
                column=expr[:idx].strip(), op=op, value=expr[idx + len(symbol):].strip()
            )
    raise ValueError(f"Bad filter {expr!r}; use e.g. country=USA, price>10, name~lap")


class DataConnectorClient:
    def __init__(self, target: str = "localhost:50051", timeout: float = 30.0):
        self._channel = grpc.insecure_channel(target)
        self._stub = pb_grpc.DataConnectorServiceStub(self._channel)
        self._timeout = timeout
        self._decoders: Dict[tuple, _schema.RowDecoder] = {}

    # -- context manager so the channel is always closed ------------------------
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def close(self) -> None:
        self._channel.close()

    # -- RPCs -------------------------------------------------------------------
    def connectors(self) -> List[pb.ConnectorInfo]:
        return list(self._stub.ListConnectors(pb.ListConnectorsRequest(), timeout=self._timeout).connectors)

    def datasets(self, connector_id: str) -> List[str]:
        resp = self._stub.ListDatasets(pb.ListDatasetsRequest(connector_id=connector_id), timeout=self._timeout)
        return [d.name for d in resp.datasets]

    def schema(self, connector_id: str, dataset: str) -> pb.GetSchemaResponse:
        return self._stub.GetSchema(
            pb.GetSchemaRequest(connector_id=connector_id, dataset=dataset), timeout=self._timeout
        )

    def decoder(self, connector_id: str, dataset: str) -> _schema.RowDecoder:
        """Fetch the schema once and cache the runtime-built message class."""
        key = (connector_id, dataset)
        if key not in self._decoders:
            s = self.schema(connector_id, dataset)
            self._decoders[key] = _schema.RowDecoder(s.descriptor_set, s.message_type)
        return self._decoders[key]

    def read(
        self,
        connector_id: str,
        dataset: str,
        columns: Optional[List[str]] = None,
        where: Optional[List[str]] = None,
        limit: int = 0,
    ) -> Iterator[dict]:
        """Streams rows as dicts. Rows arrive one by one (server streaming)."""
        decoder = self.decoder(connector_id, dataset)
        request = pb.ReadDataRequest(
            connector_id=connector_id,
            dataset=dataset,
            columns=columns or [],
            filters=[parse_filter(w) for w in (where or [])],
            limit=limit,
        )
        for record in self._stub.ReadData(request, timeout=self._timeout):
            yield decoder.decode_to_dict(record.payload)
