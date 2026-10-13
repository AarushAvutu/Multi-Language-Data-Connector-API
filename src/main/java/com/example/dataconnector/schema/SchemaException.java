package com.example.dataconnector.schema;

/** Thrown when a schema cannot be built or a descriptor set cannot be loaded. */
public class SchemaException extends RuntimeException {
  public SchemaException(String message) {
    super(message);
  }

  public SchemaException(String message, Throwable cause) {
    super(message, cause);
  }
}
