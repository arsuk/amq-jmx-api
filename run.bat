@SETLOCAL
SET ACTIVEMQ_USER=admin
SET ACTIVEMQ_PASSWORD=admin
::Example url Artemis
::SET ACTIVEMQ_HOST=http://localhost:8161/console/jolokia/
::Example url Activemq with two servers (two windows displayed)
::SET ACTIVEMQ_HOST=http://localhost:8161/api/jolokia/,http://otherhost:8161/api/jolokia/
@if [%1]==[] goto else 
@SET PARAMS=%*
goto run
:else
@SET PARAMS=DisplayDataArtemis
@echo Commands are DisplayData (Activemq), DisplayDateARtemis, LogData (Activemq), LogData
@echo Running %PARAMS% 
:run
java -jar target/amq-jmx-api-1.1-SNAPSHOT-shaded.jar %PARAMS%
