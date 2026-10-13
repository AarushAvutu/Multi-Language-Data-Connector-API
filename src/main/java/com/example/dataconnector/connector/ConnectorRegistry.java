package com.example.dataconnector.connector;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Holds every configured connector, looked up by id. Thread-safe after construction. */
public final class ConnectorRegistry implements AutoCloseable {

  private final Map<String, DataConnector> connectors = new LinkedHashMap<>();

  public ConnectorRegistry register(DataConnector connector) {
    if (connectors.putIfAbsent(connector.id(), connector) != null) {
      throw new IllegalArgumentException("Duplicate connector id: " + connector.id());
    }
    return this;
  }

  public Optional<DataConnector> find(String id) {
    return Optional.ofNullable(connectors.get(id));
  }

  public Collection<DataConnector> all() {
    return connectors.values();
  }

  @Override
  public void close() {
    connectors.values().forEach(DataConnector::close);
  }
}
