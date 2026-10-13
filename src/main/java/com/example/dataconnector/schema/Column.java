package com.example.dataconnector.schema;

import com.example.dataconnector.v1.ColumnType;

/**
 * One column of a dataset, as reported by a connector.
 *
 * <p>Example: a CSV header "lifetime_value" whose values look like 1520.75 becomes
 * {@code new Column("lifetime_value", ColumnType.COLUMN_TYPE_DOUBLE, false)}.
 */
public record Column(String name, ColumnType type, boolean nullable) {}
