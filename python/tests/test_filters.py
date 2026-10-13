"""Unit tests for the CLI filter syntax (needs the generated stubs + grpcio)."""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402

grpc = pytest.importorskip("grpc")
from dataconnector_client.client import parse_filter  # noqa: E402
from dataconnector.v1 import data_connector_pb2 as pb  # noqa: E402


@pytest.mark.parametrize(
    "expr,column,op,value",
    [
        ("country=USA", "country", pb.Filter.EQUALS, "USA"),
        ("name!=Bob", "name", pb.Filter.NOT_EQUALS, "Bob"),
        ("price>10", "price", pb.Filter.GREATER_THAN, "10"),
        ("price<5.5", "price", pb.Filter.LESS_THAN, "5.5"),
        ("name~lap", "name", pb.Filter.CONTAINS, "lap"),
    ],
)
def test_parse_filter(expr, column, op, value):
    f = parse_filter(expr)
    assert (f.column, f.op, f.value) == (column, op, value)


def test_bad_filter():
    with pytest.raises(ValueError):
        parse_filter("nonsense")
