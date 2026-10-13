from google.protobuf import any_pb2 as _any_pb2
from google.protobuf import descriptor_pb2 as _descriptor_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Iterable as _Iterable, Mapping as _Mapping, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ColumnType(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    COLUMN_TYPE_UNSPECIFIED: _ClassVar[ColumnType]
    COLUMN_TYPE_STRING: _ClassVar[ColumnType]
    COLUMN_TYPE_INT64: _ClassVar[ColumnType]
    COLUMN_TYPE_DOUBLE: _ClassVar[ColumnType]
    COLUMN_TYPE_BOOL: _ClassVar[ColumnType]
COLUMN_TYPE_UNSPECIFIED: ColumnType
COLUMN_TYPE_STRING: ColumnType
COLUMN_TYPE_INT64: ColumnType
COLUMN_TYPE_DOUBLE: ColumnType
COLUMN_TYPE_BOOL: ColumnType

class ListConnectorsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ListConnectorsResponse(_message.Message):
    __slots__ = ("connectors",)
    CONNECTORS_FIELD_NUMBER: _ClassVar[int]
    connectors: _containers.RepeatedCompositeFieldContainer[ConnectorInfo]
    def __init__(self, connectors: _Optional[_Iterable[_Union[ConnectorInfo, _Mapping]]] = ...) -> None: ...

class ConnectorInfo(_message.Message):
    __slots__ = ("id", "type", "description")
    ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    id: str
    type: str
    description: str
    def __init__(self, id: _Optional[str] = ..., type: _Optional[str] = ..., description: _Optional[str] = ...) -> None: ...

class ListDatasetsRequest(_message.Message):
    __slots__ = ("connector_id",)
    CONNECTOR_ID_FIELD_NUMBER: _ClassVar[int]
    connector_id: str
    def __init__(self, connector_id: _Optional[str] = ...) -> None: ...

class ListDatasetsResponse(_message.Message):
    __slots__ = ("datasets",)
    DATASETS_FIELD_NUMBER: _ClassVar[int]
    datasets: _containers.RepeatedCompositeFieldContainer[DatasetInfo]
    def __init__(self, datasets: _Optional[_Iterable[_Union[DatasetInfo, _Mapping]]] = ...) -> None: ...

class DatasetInfo(_message.Message):
    __slots__ = ("connector_id", "name")
    CONNECTOR_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    connector_id: str
    name: str
    def __init__(self, connector_id: _Optional[str] = ..., name: _Optional[str] = ...) -> None: ...

class GetSchemaRequest(_message.Message):
    __slots__ = ("connector_id", "dataset")
    CONNECTOR_ID_FIELD_NUMBER: _ClassVar[int]
    DATASET_FIELD_NUMBER: _ClassVar[int]
    connector_id: str
    dataset: str
    def __init__(self, connector_id: _Optional[str] = ..., dataset: _Optional[str] = ...) -> None: ...

class GetSchemaResponse(_message.Message):
    __slots__ = ("message_type", "descriptor_set", "fields")
    MESSAGE_TYPE_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTOR_SET_FIELD_NUMBER: _ClassVar[int]
    FIELDS_FIELD_NUMBER: _ClassVar[int]
    message_type: str
    descriptor_set: _descriptor_pb2.FileDescriptorSet
    fields: _containers.RepeatedCompositeFieldContainer[FieldInfo]
    def __init__(self, message_type: _Optional[str] = ..., descriptor_set: _Optional[_Union[_descriptor_pb2.FileDescriptorSet, _Mapping]] = ..., fields: _Optional[_Iterable[_Union[FieldInfo, _Mapping]]] = ...) -> None: ...

class FieldInfo(_message.Message):
    __slots__ = ("name", "number", "type", "nullable")
    NAME_FIELD_NUMBER: _ClassVar[int]
    NUMBER_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    NULLABLE_FIELD_NUMBER: _ClassVar[int]
    name: str
    number: int
    type: ColumnType
    nullable: bool
    def __init__(self, name: _Optional[str] = ..., number: _Optional[int] = ..., type: _Optional[_Union[ColumnType, str]] = ..., nullable: bool = ...) -> None: ...

class ReadDataRequest(_message.Message):
    __slots__ = ("connector_id", "dataset", "columns", "filters", "limit")
    CONNECTOR_ID_FIELD_NUMBER: _ClassVar[int]
    DATASET_FIELD_NUMBER: _ClassVar[int]
    COLUMNS_FIELD_NUMBER: _ClassVar[int]
    FILTERS_FIELD_NUMBER: _ClassVar[int]
    LIMIT_FIELD_NUMBER: _ClassVar[int]
    connector_id: str
    dataset: str
    columns: _containers.RepeatedScalarFieldContainer[str]
    filters: _containers.RepeatedCompositeFieldContainer[Filter]
    limit: int
    def __init__(self, connector_id: _Optional[str] = ..., dataset: _Optional[str] = ..., columns: _Optional[_Iterable[str]] = ..., filters: _Optional[_Iterable[_Union[Filter, _Mapping]]] = ..., limit: _Optional[int] = ...) -> None: ...

class Filter(_message.Message):
    __slots__ = ("column", "op", "value")
    class Operator(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        OPERATOR_UNSPECIFIED: _ClassVar[Filter.Operator]
        EQUALS: _ClassVar[Filter.Operator]
        NOT_EQUALS: _ClassVar[Filter.Operator]
        GREATER_THAN: _ClassVar[Filter.Operator]
        LESS_THAN: _ClassVar[Filter.Operator]
        CONTAINS: _ClassVar[Filter.Operator]
    OPERATOR_UNSPECIFIED: Filter.Operator
    EQUALS: Filter.Operator
    NOT_EQUALS: Filter.Operator
    GREATER_THAN: Filter.Operator
    LESS_THAN: Filter.Operator
    CONTAINS: Filter.Operator
    COLUMN_FIELD_NUMBER: _ClassVar[int]
    OP_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    column: str
    op: Filter.Operator
    value: str
    def __init__(self, column: _Optional[str] = ..., op: _Optional[_Union[Filter.Operator, str]] = ..., value: _Optional[str] = ...) -> None: ...

class DataRecord(_message.Message):
    __slots__ = ("row_number", "payload")
    ROW_NUMBER_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    row_number: int
    payload: _any_pb2.Any
    def __init__(self, row_number: _Optional[int] = ..., payload: _Optional[_Union[_any_pb2.Any, _Mapping]] = ...) -> None: ...
