package com.equensworldline.jolokia.client;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JolokiaRequestBuilder {
    private static final Logger logger = LoggerFactory.getLogger(JolokiaRequestBuilder.class);
    private DataType dataType = DataType.STATUS;
    private String filter = "";
    private String topicName = "";
    private String queueName = "";
    private String consumerFilter = "";
    // The broker to use for purge and resetStatistics queries
    private String broker;
    private String consumerId;
    private String clientId;

    public DataType getDataType() {
        return dataType;
    }

    public JolokiaRequestBuilder filter(String filter) {
        this.filter = filter;
        return this;
    }

    public JolokiaRequestBuilder copy() {
        return new JolokiaRequestBuilder().broker(this.broker).dataType(this.dataType).queue(this.queueName).topic(this.topicName).filter(this.filter);
    }

    /**
     * Sets the broker that is to be used for purge and resetStatistics queries
     *
     * @param broker The brokerName as known to A-MQ
     * @return This requestbuilder updated for the broker
     */
    public JolokiaRequestBuilder broker(String broker) {
        this.broker = broker;
        return this;
    }

    public JolokiaRequestBuilder consumer(String consumerId) {
        this.consumerId = consumerId;
        return this;
    }

    public JolokiaRequestBuilder consumerFilter(String consumerFilter) {
        this.consumerFilter = consumerFilter;
        return this;
    }

    public JolokiaRequestBuilder clientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    public JolokiaRequestBuilder dataType(DataType dataType) {
        this.dataType = dataType;
        return this;
    }

    public JolokiaRequestBuilder dataType(String dataTypeAlias) {
        return this.dataType(DataType.fromAlias(dataTypeAlias));
    }

    public JolokiaRequestBuilder topic(String topicName) {
        this.topicName = topicName;
        return this;
    }

    public JolokiaRequestBuilder queue(String queueName) {
        this.queueName = queueName;
        return this;
    }

    public String buildQueryRequest() {
        String queryJSON = "";
        String filteredWildcard;
        if (StringUtils.isEmpty(filter)) {
            filteredWildcard = "*";
        } else if (filter.contains("*")) {
            filteredWildcard = filter;
        } else {
            filteredWildcard = "*" + filter + "*";
        }

        switch (dataType) {
        case STATUS:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=*\"}";
            break;
        case QUEUES:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Queue,destinationName=" + filteredWildcard + "\"}";
            break;
        case TOPICS:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Topic,destinationName=" + filteredWildcard + "\"}";
            break;
        case DESTINATION:
            //NOTE: this currently needs a broker wildcard, because if queue/topic does not contain a wildcard
            // the query will return a single destination rather than a list of (1) matching destinations
            // which would make the rendering logic more complex as it needs to handle both 'single object' and 'list of objects' as
            if ("".equals(topicName)) {
                queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=*,destinationType=Queue,destinationName=" + queueName + "\"}";
            } else {
                queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=*,destinationType=Topic,destinationName=" + topicName + "\"}";
            }
            break;
        case CONNECTORS:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=*\",\"attribute\":\"TransportConnectors\"}";
            break;
        case CONNECTIONS:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=*,connector=clientConnectors,connectorName=" + filteredWildcard + ",connectionViewType=clientId,connectionName=*\"}";
            break;
        case CONSUMERS:
            String consumerIdWildcard;
            if (StringUtils.isEmpty(consumerFilter)) {
                consumerIdWildcard = "*";
            } else if (consumerFilter.contains("*")) {
                consumerIdWildcard = consumerFilter;
            } else {
                consumerIdWildcard = "*" + consumerFilter + "*";
            }
            queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=*,endpoint=Consumer,clientId=*,consumerId=" + consumerIdWildcard + ",destinationName=" + filteredWildcard + "\"}";
            break;
        case MEMORY:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"java.lang:type=Memory\",\"attribute\":\"HeapMemoryUsage\"}";
            break;
        case THREADS:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"java.lang:type=Threading\"}";
            break;
        case STORAGE:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=*,service=PersistenceAdapter,instanceName=" + filteredWildcard + "\"}";
            break;
        case TEST:
            queryJSON = "{\"type\":\"read\",\"mbean\":\"org.apache.activemq:*\"}";
            break;
        default:
            logger.error("Unknown dataType {}", dataType);
            System.exit(1);
            break;
        }
        return queryJSON;
    }

    /**
     * Build the JSON for the purge action.
     * May be called only when the active datatype is {@link DataType#QUEUES}, {@link DataType#TOPICS} or {@link DataType#DESTINATION}.<br>
     *
     * @return The JSON command structure to order a purge via Jolokia JMX
     * @throws IllegalStateException If called with an empty or wildcard-based filter as purges should be done on individual queues/topics
     */
    public String buildPurgeRequest() {
        String purgeJSON = "";
        if (StringUtils.isEmpty(filter) || filter.contains("*")) {
            // should never happen as our code should request purges for individual selected items, but as precaution throw an exception
            throw new IllegalStateException("Purging with wildcards is not allowed to avoid accidental massive purge");
        }
        switch (dataType) {
        case QUEUES:
            purgeJSON = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Queue,destinationName=" + filter + "\",\"operation\":\"purge\"}";
            break;
        case TOPICS:
            purgeJSON = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Topic,destinationName=" + filter + "\",\"operation\":\"purge\"}";
            break;
        case DESTINATION:
            if ("".equals(topicName)) {
                purgeJSON = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Queue,destinationName=" + filter + "\",\"operation\":\"purge\"}";
            } else {
                purgeJSON = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Topic,destinationName=" + filter + "\",\"operation\":\"purge\"}";
            }
            break;
        default:
            logger.error("Invalid query for purge: {}", dataType);
            break;
        }
        return purgeJSON;
    }

    /**
     * Build the JSON for the resetStatistics action.
     * May be called only when the active query is {@link DataType#STATUS}, {@link DataType#QUEUES}, {@link DataType#TOPICS} or {@link DataType#DESTINATION}.<br>
     *
     * @return The JSON command structure to order a statistics reset via Jolokia JMX
     */
    public String buildResetItemStatisticsRequest() {
        if (StringUtils.isEmpty(broker)) {
            throw new IllegalStateException("Building a ResetItemStatistics request requires the broker to be set on the requestBuilder");
        }
        String input = "";

        switch (dataType) {
        case QUEUES:
            input = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Queue,destinationName=" + filter + "\",\"operation\":\"resetStatistics\"}";
            break;
        case TOPICS:
            input = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Topic,destinationName=" + filter + "\",\"operation\":\"resetStatistics\"}";
            break;
        case DESTINATION:
            if ("".equals(topicName)) {
                input = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Queue,destinationName=" + filter + "\",\"operation\":\"resetStatistics\"}";
            } else {
                input = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Topic,destinationName=" + filter + "\",\"operation\":\"resetStatistics\"}";
            }
            break;
        default:
            logger.error("Invalid query for reset statistics: {}", dataType);
            break;
        }
        return input;
    }

    /**
     * Build the JSON for the resetStatistics action for the broker.
     *
     * @return The JSON command structure to order a broker statistics reset via Jolokia JMX
     */
    public String buildResetBrokerStatisticsRequest() {
        if (StringUtils.isEmpty(broker)) {
            throw new IllegalStateException("Building a ResetBrokerStatistics request requires the brokerName to be set on the requestBuilder");
        } else {
            return "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + "\",\"operation\":\"resetStatistics\"}";
        }
    }

    public String buildResetConsumerStatisticsRequest() {
        if (StringUtils.isEmpty(broker)) {
            throw new IllegalStateException("Building a ResetBrokerStatistics request requires the brokerName to be set on the requestBuilder");
        }
        String input = "";
        clientId = clientId.replaceAll(":", "_");
        if ("".equals(topicName)) {
            input = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Queue,endpoint=Consumer,clientId=" + clientId + ",consumerId=" + consumerId + ",destinationName=" + filter + "\",\"operation\":\"resetStatistics\"}";
        } else {
            input = "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Topic,endpoint=Consumer,clientId=" + clientId + ",consumerId=" + consumerId + ",destinationName=" + filter + "\",\"operation\":\"resetStatistics\"}";
        }

        return input;
    }
}
