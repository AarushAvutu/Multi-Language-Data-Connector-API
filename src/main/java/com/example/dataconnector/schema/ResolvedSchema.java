package com.example.dataconnector.schema;

import com.example.dataconnector.v1.FieldInfo;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.List;
import java.util.Map;

/**
 * The result of turning a {@link TableSchema} into Protobuf.
 *
 * @param source         the original connector-neutral schema
 * @param descriptor     live Protobuf descriptor used by the server to build rows
 * @param descriptorSet  serializable form of the descriptor that is shipped to clients
 * @param fields         human friendly field list (also shipped to clients)
 * @param columnToField  maps the original column name to its protobuf field
 */
public record ResolvedSchema(
    TableSchema source,
    Descriptor descriptor,
    FileDescriptorSet descriptorSet,
    List<FieldInfo> fields,
    Map<String, FieldDescriptor> columnToField) {

  public String messageType() {
    return descriptor.getFullName();
  }
}
