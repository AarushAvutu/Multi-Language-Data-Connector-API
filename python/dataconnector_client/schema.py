"""Runtime schema resolution — the Python twin of Java's DescriptorResolver.

The server does not know at compile time what columns a CSV file or SQL table has, so
it cannot ship a pre-compiled message class for each dataset. Instead GetSchema returns a
FileDescriptorSet (a schema written *as protobuf data*). Here we load that schema into a
DescriptorPool, ask protobuf to manufacture a real Python class from it, and use that
class to decode every row.

This module only needs the `protobuf` package (no gRPC), which makes it easy to test.
"""

from __future__ import annotations

from typing import Any as TypingAny, Dict, Iterable

from google.protobuf import any_pb2, descriptor_pb2, descriptor_pool, message_factory


class RowDecoder:
    """Decodes `google.protobuf.Any` row payloads for ONE dataset."""

    def __init__(self, descriptor_set: descriptor_pb2.FileDescriptorSet, message_type: str):
        # A private pool so schemas from different datasets/servers never collide.
        self._pool = descriptor_pool.DescriptorPool()
        _add_files_in_dependency_order(self._pool, descriptor_set.file)
        self.descriptor = self._pool.FindMessageTypeByName(message_type)
        # Build a real Python class (like the ones protoc generates) at runtime.
        self.message_class = message_factory.GetMessageClass(self.descriptor)

    @property
    def field_names(self) -> list[str]:
        return [f.name for f in self.descriptor.fields]

    def decode(self, payload: any_pb2.Any):
        """Any -> dynamic protobuf message. Raises ValueError on a type mismatch."""
        msg = self.message_class()
        if not payload.Unpack(msg):
            raise ValueError(
                f"payload type {payload.type_url!r} does not match schema {self.descriptor.full_name!r}"
            )
        return msg

    def to_dict(self, msg) -> Dict[str, TypingAny]:
        """Message -> plain dict. Unset (NULL) fields are omitted, int64 stays an int."""
        return {f.name: getattr(msg, f.name) for f in self.descriptor.fields if msg.HasField(f.name)}

    def decode_to_dict(self, payload: any_pb2.Any) -> Dict[str, TypingAny]:
        return self.to_dict(self.decode(payload))


def _add_files_in_dependency_order(pool, files: Iterable[descriptor_pb2.FileDescriptorProto]) -> None:
    pending = {f.name: f for f in files}
    added: set[str] = set()

    def add(name: str) -> None:
        if name in added:
            return
        fdp = pending.get(name)
        if fdp is None:  # e.g. a well-known type already known to protobuf
            return
        for dep in fdp.dependency:
            add(dep)
        pool.Add(fdp)
        added.add(name)

    for name in list(pending):
        add(name)
