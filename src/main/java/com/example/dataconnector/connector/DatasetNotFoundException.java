package com.example.dataconnector.connector;

/** A client asked for a dataset that the connector does not have. Maps to gRPC NOT_FOUND. */
public class DatasetNotFoundException extends RuntimeException {
  public DatasetNotFoundException(String connectorId, String dataset) {
    super("Dataset '" + dataset + "' not found in connector '" + connectorId + "'");
  }
}
