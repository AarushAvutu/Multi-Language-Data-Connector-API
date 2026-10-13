package com.example.dataconnector.schema;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Remembers descriptors we already built so every request doesn't rebuild them.
 *
 * <p>If the underlying dataset changes shape (someone adds a CSV column), the new {@link
 * TableSchema} no longer equals the cached one and a fresh descriptor is built automatically.
 */
public final class SchemaCache {

  private final ConcurrentMap<String, ResolvedSchema> cache = new ConcurrentHashMap<>();

  public ResolvedSchema resolve(TableSchema schema) {
    String key = schema.connectorId() + "/" + schema.dataset();
    return cache.compute(
        key,
        (k, cached) ->
            cached != null && cached.source().equals(schema)
                ? cached
                : DynamicSchemaBuilder.build(schema));
  }

  public int size() {
    return cache.size();
  }
}
