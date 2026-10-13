"""Cross-language test: bytes produced by the JAVA server, decoded by PYTHON.

The fixture files were captured from the Java service (GetSchema response + a stream of
length-delimited DataRecord messages). If this passes, a Python consumer can read data
that a Java server encoded with a schema built at runtime — the core promise of the project.

Run:  ./generate.sh && python -m pytest tests
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import dataconnector_client  # noqa: E402,F401  (adds python/generated to sys.path)
from dataconnector.v1 import data_connector_pb2 as pb  # noqa: E402
from google.protobuf import any_pb2  # noqa: E402
from google.protobuf.internal.decoder import _DecodeVarint32  # noqa: E402

from dataconnector_client.schema import RowDecoder  # noqa: E402

FIXTURES = os.path.join(HERE, "fixtures")


def _load(name):
    with open(os.path.join(FIXTURES, name), "rb") as f:
        return f.read()


def _records(name):
    data, pos, out = _load(name), 0, []
    while pos < len(data):
        size, pos = _DecodeVarint32(data, pos)
        out.append(pb.DataRecord.FromString(data[pos:pos + size]))
        pos += size
    return out


def _decoder(dataset):
    schema = pb.GetSchemaResponse.FromString(_load(f"{dataset}_schema.bin"))
    return schema, RowDecoder(schema.descriptor_set, schema.message_type)


def test_csv_rows_written_by_java_decode_in_python():
    schema, dec = _decoder("customers")
    assert schema.message_type == "dataconnector.dynamic.crm_files.Customers"
    assert dec.field_names[:3] == ["customer_id", "name", "email"]
    rows = [dec.decode_to_dict(r.payload) for r in _records("customers_records.bin")]
    assert len(rows) == 10
    assert rows[0] == {
        "customer_id": 1, "name": "Ava Johnson", "email": "ava.johnson@example.com",
        "country": "USA", "signup_year": 2019, "lifetime_value": 1520.75, "is_active": True,
    }
    assert isinstance(rows[0]["customer_id"], int)       # int64 stays a real int
    assert "lifetime_value" not in rows[5]                 # NULL -> field not set
    assert "email" not in rows[8]


def test_sql_rows_and_types():
    _, dec = _decoder("orders")
    rows = [dec.decode_to_dict(r.payload) for r in _records("orders_records.bin")]
    assert len(rows) == 8
    assert rows[0]["amount"] == 120.5 and rows[0]["is_shipped"] is True
    assert rows[0]["order_date"] == "2026-01-15"
    assert "coupon_code" not in rows[1]


def test_wrong_schema_is_rejected():
    _, customers = _decoder("customers")
    order_record = _records("orders_records.bin")[0]
    try:
        customers.decode(order_record.payload)
    except ValueError:
        return
    raise AssertionError("decoding an Orders row with the Customers schema must fail")


def test_type_url_names_the_runtime_message():
    rec = _records("customers_records.bin")[0]
    assert isinstance(rec.payload, any_pb2.Any)
    assert rec.payload.type_url == "type.googleapis.com/dataconnector.dynamic.crm_files.Customers"
