package it.pleaseopen.keycloak.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.*;
import uk.org.webcompere.systemstubs.rules.EnvironmentVariablesRule;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;

import static org.hamcrest.CoreMatchers.is;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class MetricsEventListerTest {

    private static final String DEFAULT_REALM_ID = "2af8c4d4-4d58-4d74-9ad7-eef9aac06a90";
    private static final String DEFAULT_REALM_NAME = "myrealm";

    private static final RealmProvider realmProvider = mock(RealmProvider.class);
    private static final ClientProvider clientProvider = mock(ClientProvider.class);
    private static final ClientModel client = mock(ClientModel.class);

    MeterRegistry meterRegistry;
    MetricsEventListener metricsEventListener;
    KeycloakSession keycloakSessionMock;

    @Before
    public void setupRealmProvider() {
        RealmModel realm = mock(RealmModel.class);
        client.setClientId("THE_CLIENT_ID");
        when(realm.getName()).thenReturn(DEFAULT_REALM_NAME);
        when(realmProvider.getRealm(DEFAULT_REALM_ID)).thenReturn(realm);
        RealmModel otherRealm = mock(RealmModel.class);
        keycloakSessionMock = mock(KeycloakSession.class);
        when(keycloakSessionMock.realms()).thenReturn(realmProvider);
        when(keycloakSessionMock.realms().getRealm(DEFAULT_REALM_ID)).thenReturn(realm);
        when(keycloakSessionMock.clients()).thenReturn(clientProvider);
        when(keycloakSessionMock.clients().getClientByClientId(realm,"THE_CLIENT_ID")).thenReturn(client);
        when(keycloakSessionMock.sessions()).thenReturn(mock(UserSessionProvider.class));
        when(keycloakSessionMock.sessions().getOfflineSessionsCount(realm, client )).thenReturn(1L);
        when(keycloakSessionMock.sessions().getActiveUserSessions(realm, client )).thenReturn(1L);
        when(otherRealm.getName()).thenReturn("OTHER_REALM");
        when(realmProvider.getRealm("OTHER_REALM_ID")).thenReturn(otherRealm);
        meterRegistry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meterRegistry);
    }

    @Rule
    public final EnvironmentVariablesRule environmentVariables = new EnvironmentVariablesRule();

    @Before
    public void resetSingleton() throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        metricsEventListener = MetricsEventListener.getInstance();
        metricsEventListener.setSession(keycloakSessionMock);
    }

    @Test
    public void shouldCorrectlyRecordGenericEvents() throws IOException {
        final Event event1 = createEvent(EventType.UPDATE_EMAIL);
        metricsEventListener.onEvent(event1);
        assertMetric("keycloak_user_event_UPDATE_EMAIL", 1, "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        metricsEventListener.onEvent(event1);
        assertMetric("keycloak_user_event_UPDATE_EMAIL", 2, "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        final Event event2 = createEvent(EventType.REVOKE_GRANT);
        metricsEventListener.onEvent(event2);
        assertMetric("keycloak_user_event_REVOKE_GRANT", 1,"client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_user_event_UPDATE_EMAIL", 2, "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyRecordGenericAdminEvents() throws IOException {
        final AdminEvent event1 = new AdminEvent();
        event1.setOperationType(OperationType.ACTION);
        event1.setResourceType(ResourceType.AUTHORIZATION_SCOPE);
        event1.setRealmId(DEFAULT_REALM_ID);
        metricsEventListener.onEvent(event1, false);
        assertMetric("keycloak_admin_event_ACTION", 1,  "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);
        metricsEventListener.onEvent(event1, false);
        assertMetric("keycloak_admin_event_ACTION", 2,  "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);


        final AdminEvent event2 = new AdminEvent();
        event2.setOperationType(OperationType.UPDATE);
        event2.setResourceType(ResourceType.CLIENT);
        event2.setRealmId(DEFAULT_REALM_ID);
        metricsEventListener.onEvent(event2, false);
        assertMetric("keycloak_admin_event_UPDATE", 1,  "resource", "CLIENT", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_admin_event_ACTION", 2,  "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldTolerateNullLabels() throws IOException {
        final Event event1 = createEvent(EventType.UPDATE_EMAIL);
        metricsEventListener.onEvent(event1);
        assertMetric("keycloak_user_event_UPDATE_EMAIL", 1, "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        metricsEventListener.onEvent(event1);
        assertMetric("keycloak_user_event_UPDATE_EMAIL", 2, "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        final Event nullEvent = new Event();
        nullEvent.setClientId(null);
        nullEvent.setError(null);
        nullEvent.setRealmId(null);
        metricsEventListener.onEvent(nullEvent);

        assertMetric("keycloak_user_event_UPDATE_EMAIL", 2, "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    private void assertGenericMetric(String metricName, double metricValue, String ... tags) throws IOException {
        MatcherAssert.assertThat("Metric value match", meterRegistry.counter(metricName, tags).count() == metricValue);
    }

    private void assertMetric(String metricName, double metricValue, String ... tags) throws IOException {

        this.assertGenericMetric(metricName, metricValue, tags);
    }

    private Event createEvent(EventType type, String realm, String clientId, String error, Tuple<String, String>... tuples) {
        final Event event = new Event();
        event.setType(type);
        event.setRealmId(realm);
        event.setClientId(clientId);
        if (tuples != null) {
            event.setDetails(new HashMap<>());
            for (Tuple<String, String> tuple : tuples) {
                event.getDetails().put(tuple.left, tuple.right);
            }
        } else {
            event.setDetails(Collections.emptyMap());
        }

        if (error != null) {
            event.setError(error);
        }
        return event;
    }

    private Event createEvent(EventType type, String realm, String clientId, Tuple<String, String>... tuples) {
        return this.createEvent(type, realm, clientId, (String) null, tuples);
    }

    private Event createEvent(EventType type) {
        return createEvent(type, DEFAULT_REALM_ID, "THE_CLIENT_ID",(String) null);
    }

    private static <L, R> Tuple<L, R> tuple(L left, R right) {
        return new Tuple<>(left, right);
    }

    private static final class Tuple<L, R> {
        final L left;
        final R right;

        private Tuple(L left, R right) {
            this.left = left;
            this.right = right;
        }
    }

}
