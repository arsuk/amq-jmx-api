package com.equensworldline.jolokia.client;

import java.util.Arrays;
import java.util.stream.Stream;

public enum DataTypeArtemis {
    DESTINATION("destination"),
    STATUS("status"),
    QUEUES("queues"),
    ADDRESSES("addresses"),
    ACCEPTORS("acceptors"),
    CONNECTIONS("connections"),
    SESSIONS("sessions"),
    CONSUMERS("consumers"),
    PRODUCERS("producers"),
    MEMORY("memory"),
    THREADS("threads");

    private String alias;

    DataTypeArtemis(String alias) {
        this.alias = alias;
    }

    public static DataTypeArtemis fromAlias(String alias) {
        for (DataTypeArtemis qt : DataTypeArtemis.values()) {
            if (qt.alias.equalsIgnoreCase(alias)) {
                return qt;
            }
        }
        throw new IllegalArgumentException(String.format("Invalid DataType alias '%s'; supported are %s ", alias, Arrays.deepToString(Stream.of(DataTypeArtemis.values()).map(x -> x.alias()).toArray())));
    }

    public String alias() {
        return this.alias;
    }

    @Override
    public String toString() {
        return this.alias;
    }
}
