package com.example.dataconnector.schema;

import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side helper: turns a {@link FileDescriptorSet} received over the wire back into a
 * usable {@link Descriptor}, and decodes {@link Any} payloads with it.
 *
 * <p>This is the "dynamic schema resolution" half that lives in the consumer. The Python
 * client does the same thing with {@code descriptor_pool.DescriptorPool} and {@code
 * message_factory.GetMessageClass}.
 */
public final class DescriptorResolver {

  private static final String TYPE_URL_PREFIX = "type.googleapis.com/";

  private DescriptorResolver() {}

  /** Finds {@code fullMessageName} (e.g. "dataconnector.dynamic.crm_files.Customers"). */
  public static Descriptor resolve(FileDescriptorSet set, String fullMessageName) {
    Map<String, FileDescriptorProto> byName = new HashMap<>();
    for (FileDescriptorProto f : set.getFileList()) {
      byName.put(f.getName(), f);
    }
    Map<String, FileDescriptor> built = new HashMap<>();
    for (FileDescriptorProto f : set.getFileList()) {
      FileDescriptor fd = buildFile(f.getName(), byName, built);
      Descriptor d = findMessage(fd, fullMessageName);
      if (d != null) {
        return d;
      }
    }
    throw new SchemaException("Message type '" + fullMessageName + "' not found in descriptor set");
  }

  /** Decodes one row. Verifies the payload's type_url matches the descriptor. */
  public static DynamicMessage unpack(Any payload, Descriptor descriptor) {
    String expected = TYPE_URL_PREFIX + descriptor.getFullName();
    if (!payload.getTypeUrl().equals(expected)) {
      throw new SchemaException(
          "Payload type " + payload.getTypeUrl() + " does not match schema " + expected);
    }
    try {
      return DynamicMessage.parseFrom(descriptor, payload.getValue());
    } catch (InvalidProtocolBufferException e) {
      throw new SchemaException("Corrupt payload: " + e.getMessage(), e);
    }
  }

  // Dependencies must be built before the files that import them, so recurse.
  private static FileDescriptor buildFile(
      String name, Map<String, FileDescriptorProto> byName, Map<String, FileDescriptor> built) {
    FileDescriptor existing = built.get(name);
    if (existing != null) {
      return existing;
    }
    FileDescriptorProto proto = byName.get(name);
    if (proto == null) {
      throw new SchemaException("Descriptor set is missing dependency " + name);
    }
    FileDescriptor[] deps = new FileDescriptor[proto.getDependencyCount()];
    for (int i = 0; i < deps.length; i++) {
      deps[i] = buildFile(proto.getDependency(i), byName, built);
    }
    try {
      FileDescriptor fd = FileDescriptor.buildFrom(proto, deps);
      built.put(name, fd);
      return fd;
    } catch (DescriptorValidationException e) {
      throw new SchemaException("Invalid descriptor " + name + ": " + e.getMessage(), e);
    }
  }

  private static Descriptor findMessage(FileDescriptor fd, String fullName) {
    for (Descriptor d : fd.getMessageTypes()) {
      if (d.getFullName().equals(fullName)) {
        return d;
      }
    }
    return null;
  }
}
