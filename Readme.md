# ActiveMQ Jolokia monitor tool
This is our Jolokia monitoring tool for JBoss A-MQ Brokers.

## Running
To run this tool get it's latest release from Kazan Nexus () or clone this repository and build it.
The file to download from Nexus is amq-jmx-api-&lt;version&gt;-shaded.jar.

### Accessing a regular VM-hosted broker
The tool uses environment variables to configure its access to the A-MQ Jolokia interface:
- ACTIVEMQ_USER the userid for authentication; default value when absent: `admin`
- ACTIVEMQ_PASSWORD the password for authentication; default value when absent: `admin`
- ACTIVEMQ_HOST the URL to access the jolokia REST api on the broker; default value when absent: `http://localhost:8161/api/jolokia/`; supports a comma-separated list of URLs to connect the tool to more than one broker, e.g. `http://ipint1mb001:8181/hawtio/jolokia,http://ipint1mb002:8181/hawtio/jolokia`
- REFRESH_TIME the number of seconds before refreshing the displayed data / writing next log-entry
- DELIMITER (in case of LogData) the delimiter for the logfile

As taken from the output of java -jar amq-jmx-api-1.1-SNAPSHOT-shaded.jar -help
```
AMQ Tool version 1.1-SNAPSHOT
usage: java -jar amq-jmx-api-1.1-SNAPSHOT-shaded.jar [DisplayData|LogData] <query> [-help] [-trace] [-queue <qname>] [-topic <tname>] [-filter <filtertext>] [-consumerfilter <filtertext2>]
Queries are: status,connectors,connections,queues,topics,destination,consumers,memory,threads,storage
For destination and consumers you must specify a queue or topic
The -consumerfilter option is to filter the consumers further by consumerId (the -filter is applied to the DestinationName of the consumers)
Environment variables:
ACTIVEMQ_HOST: default=http://localhost:8161/api/jolokia/
ACTIVEMQ_USER: admin
ACTIVEMQ_PASSWORD: admin
DELIMITER: default='\t' (TAB)
REFRESH_TIME: default=5
```

With the proper environment variables in place use the following commandline to start the tool as a GUI connected to the broker(s):
```
java -jar amq-jmx-api-1.2-shaded.jar DisplayData status
```

### Accessing an OpenShift hosted broker based on the standard RedHat A-MQ Broker template:
The RedHat A-MQ Openshift image exposes its jolokia interface via a proxy on the 
master node that requires an authorized OpenShift user using an access-token.
For this access scenario the ACTIVEMQ_USER and ACTIVEMQ_PASSWORD variables are \
ignored.

The tool uses the Openshift CLI to obtain the token of the currently logged-in 
user (`oc whoami -t`). To run this tool against a broker hosted in OpenShift use 
the following pattern for the ACTIVEMQ_HOST URL:
`https://<openshift-master-console-host:port>/api/v1/namespaces/<openshift-namespace>/pods/https:<broker-pod-name>:8778/proxy/jolokia/`

As an example the URL for the broker that was running in the OpenShift QLF CaaS 
cluster within the instant-payments-poc environment at the time of writing these
 instructions:
```
ACTIVEMQ_HOST=https://admin.caas-qlf.svc.meshcore.net:443/api/v1/namespaces/instant-payments-poc/pods/https:broker-amq-1-zngqq:8778/proxy/jolokia/
```

When the openshift master is not configured with a dedicated trusted certificate you will have to configure Java to trust the 
self-signed openshift-signer Root CA. Obtain the RootCA public certificate (e.g. by inspecting the certificate chain in your 
webbrowser and saving the CA root certificate) and store it in a keystore. 

If a proxy is to be used for access that is configured with a Proxy-auto-configuration script you need to set your proxy explicitly 
for the tool. A common way of doing that is running the Fiddler proxy on your local system configured to use the "system proxy 
configuration".

A sample full command-line (for the instant-payments-poc broker that was running
at the time of writing this readme), with Fiddler running on localhost port 8888, 
and the keystore as stored in this project for the QLF CaaS cluster in France, 
assuming that you're already logged on to OpenShift with a user that has access
 to the instant-payments-poc project:

```shell script
SET ACTIVEMQ_HOST=https://admin.caas-qlf.svc.meshcore.net:443/api/v1/namespaces/instant-payments-poc/pods/https:broker-amq-1-zngqq:8778/proxy/jolokia/
java -Dhttps.proxyHost=localhost -Dhttps.proxyPort=8888 -Djavax.net.ssl.trustStorePassword=changeme -Djavax.net.ssl.trustStore=openshift-signer-ca.jks -jar amq-jmx-api-1.2-shaded.jar DisplayData status
```
## Building / Developing
Prerequisites for building this project:
* Maven 3.5.4 or newer installed
* Java JDK 1.8 or newer installed (1.8 recommended, as from Java 9 onward you may run into JPMS issues as we have not tested for compatibility with the Project Jigsaw updates)
* Connectivity to Maven central, or a local proxy repository that proxies at least Maven Central with the appropriate configuration in your maven settings.xml
* Connectivity to the NIST National Vulnerability Database and Sonatype OSS Index (for validating that no vulnerable libraries are contained). If connectivity is not available you can run a build without checking for vulnerable libraries by replacing `verify` with `package`.

With the environment in place building is as easy as cloning, checking out the desired branch (typically `develop` when experimenting with the latest snapshot sources) and running the maven build:
```shell script
git clone https://gitlab.kazan.atosworldline.com/ap-payments-nonfunctional-testing/amq-jmx-api.git
cd amqw-jmx-api
git checkout develop
mvn clean verify 
```
When the build was successful you can then directly run the tool from the target folder, e.g.
```shell script
java -jar target/amq-jmx-api-1.3-SNAPSHOT-shaded.jar DisplayData status
```

## Releasing
For releases a Jenkins job is provisioned in Kazan that will run a release based 
on the HEAD revision of the `develop` branch. Just run that build and the new 
release will be created  and published in Kazan Nexus with the release-version
corresponding to the current SNAPSHOT and the `develop` branch will be updated 
to a new minor version SNAPSHOT after release.

Don't forget to pull the changes to your local repository so that your local pom
 also receives the proper new-development-version update.