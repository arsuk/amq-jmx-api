package com.equensworldline.jolokia.client;

import java.util.Arrays;
import java.util.stream.Stream;

public enum DataType {
    DESTINATION("destination"),
    STATUS("status"),
    QUEUES("queues"),
    TOPICS("topics"),
    CONNECTORS("connectors"),
    CONNECTIONS("connections"),
    CONSUMERS("consumers"),
    MEMORY("memory"),
    STORAGE("storage"),
    THREADS("threads"),
    TEST("test");

    private String alias;

    DataType(String alias) {
        this.alias = alias;
    }

    public static DataType fromAlias(String alias) {
        for (DataType qt : DataType.values()) {
            if (qt.alias.equalsIgnoreCase(alias)) {
                return qt;
            }
        }
        throw new IllegalArgumentException(String.format("Invalid DataType alias '%s'; supported are %s ", alias, Arrays.deepToString(Stream.of(DataType.values()).map(DataType::alias).toArray())));
    }

    public String alias() {
        return this.alias;
    }

    @Override
    public String toString() {
        return this.alias;
    }
}
