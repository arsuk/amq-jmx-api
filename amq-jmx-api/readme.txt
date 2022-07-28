This application display statistics which are supplied ActiveMQ brokers. The information is mostly available from the broker management GUI but this requires manual refreshes and the layout requires scrolling sometimes. This application shows the information in a compact form in a desktop window and refreshes it periodically. The application can also log the same data in csv format.
Apache ActiveMQ and Apache Artemis are supported. You make the choice by selecting the appropriate main class as the first application argument. These are:
    DisplayData, LogData, DiaplayDataArtemis, LogDataArtemis
Each of these classes accept several arguments and they have a '-h' option.
The broker connection data is provided as environment variables so that they do not have to be specified on the command line every time. These are:
SET ACTIVEMQ_USER=admin
SET ACTIVEMQ_PASSWORD=admin
::Example url Artemis
SET ACTIVEMQ_HOST=http://localhost:8161/console/jolokia/
::Example url Activemq with two servers (two windows displayed)
SET ACTIVEMQ_HOST=http://localhost:8161/api/jolokia/,http://otherhost:8161/api/jolokia/  
