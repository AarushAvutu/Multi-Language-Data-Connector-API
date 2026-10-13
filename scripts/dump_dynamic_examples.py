"""Writes generated-code/dynamic-examples/ from the GetSchema responses captured in
python/tests/fixtures/ (bytes produced by the Java server).

For each dataset it writes:
  <pkg>_<dataset>.descriptor.txt  the FileDescriptorSet the client receives, as text
  <pkg>_<dataset>.proto.txt       the equivalent .proto file that nobody ever wrote
"""
import os
import sys

from google.protobuf import descriptor_pb2 as d
from google.protobuf import text_format

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURES = os.path.join(ROOT, "python", "tests", "fixtures")
OUT = os.path.join(ROOT, "generated-code", "dynamic-examples")
sys.path.insert(0, os.path.join(ROOT, "generated-code", "python"))
from dataconnector.v1 import data_connector_pb2 as pb  # noqa: E402

TYPES = {
    d.FieldDescriptorProto.TYPE_INT64: "int64",
    d.FieldDescriptorProto.TYPE_STRING: "string",
    d.FieldDescriptorProto.TYPE_DOUBLE: "double",
    d.FieldDescriptorProto.TYPE_BOOL: "bool",
}
DATASETS = [("customers", "crm-files"), ("orders", "sales-db")]

os.makedirs(OUT, exist_ok=True)
for dataset, connector in DATASETS:
    with open(os.path.join(FIXTURES, f"{dataset}_schema.bin"), "rb") as fh:
        schema = pb.GetSchemaResponse.FromString(fh.read())
    file = schema.descriptor_set.file[0]
    msg = file.message_type[0]
    base = os.path.join(OUT, f"{file.package.split('.')[-1]}_{dataset}")

    with open(base + ".descriptor.txt", "w") as fh:
        fh.write(f'# FileDescriptorSet returned by GetSchema(connector_id="{connector}", dataset="{dataset}")\n')
        fh.write("# Built at runtime by DynamicSchemaBuilder — this is exactly what the client receives (as bytes).\n")
        fh.write(f"# message_type: {schema.message_type}\n\n")
        fh.write(text_format.MessageToString(schema.descriptor_set))

    tw = max(len(TYPES[f.type]) for f in msg.field)
    nw = max(len(f.name) for f in msg.field)
    lines = [
        "// The .proto file that NOBODY wrote: equivalent of the descriptor the server",
        f'// built at runtime for GetSchema("{connector}", "{dataset}"). For reading only — it is never compiled.',
        "",
        'syntax = "proto3";',
        "",
        f"package {file.package};",
        "",
        f"message {msg.name} {{",
    ]
    lines += [f"  optional {TYPES[f.type]:<{tw}} {f.name:<{nw}} = {f.number};" for f in msg.field]
    lines.append("}")
    with open(base + ".proto.txt", "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print("wrote", os.path.relpath(base, ROOT) + ".{descriptor,proto}.txt")
