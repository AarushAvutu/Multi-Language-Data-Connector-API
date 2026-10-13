package com.example.dataconnector.connector;

/** The underlying source failed (file unreadable, DB down...). Maps to gRPC UNAVAILABLE. */
public class ConnectorException extends RuntimeException {
  public ConnectorException(String message, Throwable cause) {
    super(message, cause);
  }
}
