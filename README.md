[![License](https://img.shields.io/:license-Apache2-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0)

# Keycloak Native Metrics SPI

Based on https://github.com/aerogear/keycloak-metrics-spi

Thanks a lot to all contributors.

This Event Listener provider adds new metrics to native Quarkus metrics (based on micrometer) : https://www.keycloak.org/server/configuration-metrics

Custom metrics are added to /metrics native endpoint in Keycloak (with flag --metrics-enabled=true)

Many metrics from the original plugin have been removed, Keycloak has now a support for all system metrics : JVM, HTTP request etc...

Only logins, tokens, user profiles and registrations are monitored from this plugin.

## License

See [LICENSE file](./LICENSE)

## Running the tests

```sh
$ ./gradlew test
```

## Build

There are two ways to build the project using:

* [Gradle](https://gradle.org/)
* [Maven](https://maven.apache.org/)

You can choose between the tools the most convenient for you. Read further how to use each of them.

### Gradle

The project is packaged as a jar file and bundles the prometheus client libraries.

```sh
$ ./gradlew jar
```

builds the jar and writes it to _build/libs_.

### Maven

To build the jar file using maven run the following command (will bundle the prometheus client libraries as well):

```sh
mvn package
```

It will build the project and write jar to the _./target_.

```##

### On Keycloak Quarkus Distribution

> We assume the home of keycloak is on the default `/opt/keycloak`

You will need to either copy the `jar` into the build step and run step, or copy it from the build stage. Following the [example docker instructions](https://www.keycloak.org/server/containers)
No need to add `.dodeploy`.

```

# On build stage

COPY keycloak-metrics-spi.jar /opt/keycloak/providers/

# On run stage

COPY keycloak-native-metrics-spi.jar /opt/keycloak/providers/

```
If not copied to both stages keycloak will complain

```

ERROR: Failed to start quarkus
ERROR: Failed to open /opt/keycloak/lib/../providers/keycloak-native-metrics-spi.jar

```
### Enable metrics-listener event

- To enable the event listener via the GUI interface, go to _Manage -> Events -> Config_. The _Event Listeners_ configuration should have an entry named `native-metrics-listener`.
- To enable the event listener via the Keycloak CLI, such as when building a Docker container, use these commands.

```c
$ /opt/jboss/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080/auth --realm master --user $KEYCLOAK_USER --password $KEYCLOAK_PASSWORD
$ /opt/jboss/keycloak/bin/kcadm.sh update events/config -s "eventsEnabled=true" -s "adminEventsEnabled=true" -s "eventsListeners+=native-metrics-listener"
$ /usr/bin/rm -f /opt/jboss/.keycloak/kcadm.config
```

## Metrics

For each metric, the endpoint returns 2 or more lines of information:

* **# HELP**: A small description provided by the SPI.
* **# TYPE**: The type of metric, namely _counter_ and _gauge_. More info about types at [prometheus.io/docs](https://prometheus.io/docs/concepts/metric_types/).
* Provided there were any values, the last one recorded. If no value has been recorded yet, no more lines will be given.
* In case the same metric have different labels, there is a different line for each one. By default all metrics are labeled by realm. More info about labels at [prometheus.io/docs](https://prometheus.io/docs/practices/naming/).

Example:

```c
# HELP keycloak_user_event_LOGOUT_total Generic KeyCloak User event
# TYPE keycloak_user_event_LOGOUT_total counter
keycloak_user_event_LOGOUT_total{realm="master",} 1.0
```

### Generic events

Every single internal Keycloak event is being shared through the endpoint, with the descriptions `Generic Keycloak User event` or `Generic Keycloak Admin event`. Most of these events are not likely useful for the majority users but are provided for good measure. A complete list of the events can be found at [Keycloak documentation](https://www.keycloak.org/docs-api/4.8/javadocs/org/keycloak/events/EventType.html).

```

```
