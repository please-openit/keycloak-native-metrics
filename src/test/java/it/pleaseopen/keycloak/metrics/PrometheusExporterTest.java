package it.pleaseopen.keycloak.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.prometheus.client.CollectorRegistry;
import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import uk.org.webcompere.systemstubs.rules.EnvironmentVariablesRule;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;

import static org.hamcrest.CoreMatchers.is;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
public class PrometheusExporterTest {

    private static final String DEFAULT_REALM_NAME = "myrealm";

    MeterRegistry meterRegistry;

    @Before
    public void setupRegistry() {
        meterRegistry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meterRegistry);
    }

    @Rule
    public final EnvironmentVariablesRule environmentVariables = new EnvironmentVariablesRule();

    @Before
    public void resetSingleton() throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
        Field instance = PrometheusExporter.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
        CollectorRegistry.defaultRegistry.clear();
    }

    @Test
    public void shouldRegisterCountersForAllKeycloakEvents() {
        int userEvents = EventType.values().length;
        int adminEvents = OperationType.values().length;

        MatcherAssert.assertThat(
            "All events registered",
            userEvents + adminEvents - 3,                             // -3 comes from the events that
            is(PrometheusExporter.instance().counters.size()));       // have their own counters outside the counter map

    }

    @Test
    public void shouldCorrectlyCountLoginAttemptsForSuccessfulAndFailedAttempts() throws IOException {
        // with LOGIN event
        final Event login1 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
        PrometheusExporter.instance().recordLogin(login1);
        assertMetric("keycloak_login_attempts", 1, "provider", "keycloak","client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_logins", 1, "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // with LOGIN_ERROR event
        final Event event2 = createEvent(EventType.LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
        PrometheusExporter.instance().recordLoginError(event2);
        assertMetric("keycloak_login_attempts", 2, "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_failed_login_attempts", 1, "provider", "keycloak", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountLoginWhenIdentityProviderIsDefined() throws IOException {
        final Event login1 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordLogin(login1);
        assertMetric("keycloak_logins", 1,  "provider", "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        final Event login2 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordLogin(login2);
        assertMetric("keycloak_logins", 2,  "provider", "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountLoginWhenIdentityProviderIsNotDefined() throws IOException {
        final Event login1 = createEvent(EventType.LOGIN);
        PrometheusExporter.instance().recordLogin(login1);
        assertMetric("keycloak_logins", 1,  "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        final Event login2 = createEvent(EventType.LOGIN);
        PrometheusExporter.instance().recordLogin(login2);
        assertMetric("keycloak_logins", 2,  "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountLoginsFromDifferentProviders() throws IOException {
        // with id provider defined
        final Event login1 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordLogin(login1);
        assertMetric("keycloak_logins", 1,  "provider", "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event login2 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
        PrometheusExporter.instance().recordLogin(login2);
        assertMetric("keycloak_logins", 1,  "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_logins", 1,  "provider", "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldRecordLoginsPerRealm() throws IOException {
        // realm 1
        final Event login1 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordLogin(login1);

        // realm 2
        final Event login2 = createEvent(EventType.LOGIN, "OTHER_REALM", "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordLogin(login2);

        assertMetric("keycloak_logins", 1, "provider", "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_logins", 1,  "provider", "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", "OTHER_REALM");
    }

    @Test
    public void shouldCorrectlyCountLoginError() throws IOException {
        // with id provider defined
        final Event event1 = createEvent(EventType.LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordLoginError(event1);
        assertMetric("keycloak_failed_login_attempts", 1,  "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event event2 = createEvent(EventType.LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
        PrometheusExporter.instance().recordLoginError(event2);
        assertMetric("keycloak_failed_login_attempts", 1,  "provider", "keycloak", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_failed_login_attempts", 1,  "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountRegister() throws IOException {
        // with id provider defined
        final Event event1 = createEvent(EventType.REGISTER, DEFAULT_REALM_NAME, "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordRegistration(event1);
        assertMetric("keycloak_registrations", 1,  "provider", "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event event2 = createEvent(EventType.REGISTER, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
        PrometheusExporter.instance().recordRegistration(event2);
        assertMetric("keycloak_registrations", 1,  "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_registrations", 1,  "provider", "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountRefreshTokens() throws IOException {
        // with id provider defined
        final Event event1 = createEvent(EventType.REFRESH_TOKEN, DEFAULT_REALM_NAME, "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordRefreshToken(event1);
        assertMetric("keycloak_refresh_tokens", 1,  "provider",  "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event event2 = createEvent(EventType.REFRESH_TOKEN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
        PrometheusExporter.instance().recordRefreshToken(event2);
        assertMetric("keycloak_refresh_tokens", 1,  "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_refresh_tokens", 1,  "provider",  "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountRefreshTokensErrors() throws IOException {
        // with id provider defined
        final Event event1 = createEvent(EventType.REFRESH_TOKEN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordRefreshTokenError(event1);
        assertMetric("keycloak_refresh_tokens_errors", 1,  "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event event2 = createEvent(EventType.REFRESH_TOKEN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
        PrometheusExporter.instance().recordRefreshTokenError(event2);
        assertMetric("keycloak_refresh_tokens_errors", 1,  "provider", "keycloak", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_refresh_tokens_errors", 1,  "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountClientLogins() throws IOException {
        // with id provider defined
        final Event event1 = createEvent(EventType.CLIENT_LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordClientLogin(event1);
        assertMetric("keycloak_client_logins", 1,  "provider",  "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event event2 = createEvent(EventType.CLIENT_LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
        PrometheusExporter.instance().recordClientLogin(event2);
        assertMetric("keycloak_client_logins", 1,  "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_client_logins", 1,  "provider",  "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountClientLoginAttempts() throws IOException {
        // with id provider defined
        final Event event1 = createEvent(EventType.CLIENT_LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found", tuple( "identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordClientLoginError(event1);
        assertMetric("keycloak_failed_client_login_attempts", 1,  "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event event2 = createEvent(EventType.CLIENT_LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
        PrometheusExporter.instance().recordClientLoginError(event2);
        assertMetric("keycloak_failed_client_login_attempts", 1,  "provider", "keycloak", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_failed_client_login_attempts", 1,  "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountCodeToTokens() throws IOException {
        // with id provider defined
        final Event event1 = createEvent(EventType.CODE_TO_TOKEN, DEFAULT_REALM_NAME, "THE_CLIENT_ID",  tuple("identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordCodeToToken(event1);
        assertMetric("keycloak_code_to_tokens", 1,  "provider",  "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event event2 = createEvent(EventType.CODE_TO_TOKEN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
        PrometheusExporter.instance().recordCodeToToken(event2);
        assertMetric("keycloak_code_to_tokens", 1,  "provider", "keycloak", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_code_to_tokens", 1,  "provider",  "THE_ID_PROVIDER", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountCodeToTokensErrors() throws IOException {
        // with id provider defined
        final Event event1 = createEvent(EventType.CODE_TO_TOKEN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found", tuple( "identity_provider", "THE_ID_PROVIDER"));
        PrometheusExporter.instance().recordCodeToTokenError(event1);
        assertMetric("keycloak_code_to_tokens_errors", 1,  "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        // without id provider defined
        final Event event2 = createEvent(EventType.CODE_TO_TOKEN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
        PrometheusExporter.instance().recordCodeToTokenError(event2);
        assertMetric("keycloak_code_to_tokens_errors", 1,  "provider", "keycloak", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_code_to_tokens_errors", 1,  "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client_id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyRecordGenericEvents() throws IOException {
        final Event event1 = createEvent(EventType.UPDATE_EMAIL);
        PrometheusExporter.instance().recordGenericEvent(event1);
        assertMetric("keycloak_user_event_UPDATE_EMAIL", 1, "realm", DEFAULT_REALM_NAME);
        PrometheusExporter.instance().recordGenericEvent(event1);
        assertMetric("keycloak_user_event_UPDATE_EMAIL", 2, "realm", DEFAULT_REALM_NAME);


        final Event event2 = createEvent(EventType.REVOKE_GRANT);
        PrometheusExporter.instance().recordGenericEvent(event2);
        assertMetric("keycloak_user_event_REVOKE_GRANT", 1, "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_user_event_UPDATE_EMAIL", 2, "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyRecordGenericAdminEvents() throws IOException {
        final AdminEvent event1 = new AdminEvent();
        event1.setOperationType(OperationType.ACTION);
        event1.setResourceType(ResourceType.AUTHORIZATION_SCOPE);
        event1.setRealmName(DEFAULT_REALM_NAME);
        PrometheusExporter.instance().recordGenericAdminEvent(event1);
        assertMetric("keycloak_admin_event_ACTION", 1,  "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);
        PrometheusExporter.instance().recordGenericAdminEvent(event1);
        assertMetric("keycloak_admin_event_ACTION", 2,  "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);


        final AdminEvent event2 = new AdminEvent();
        event2.setOperationType(OperationType.UPDATE);
        event2.setResourceType(ResourceType.CLIENT);
        event2.setRealmName(DEFAULT_REALM_NAME);
        PrometheusExporter.instance().recordGenericAdminEvent(event2);
        assertMetric("keycloak_admin_event_UPDATE", 1,  "resource", "CLIENT", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak_admin_event_ACTION", 2,  "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldTolerateNullLabels() throws IOException {
        final Event nullEvent = new Event();
        nullEvent.setClientId(null);
        nullEvent.setError(null);
        nullEvent.setRealmId(null);
        PrometheusExporter.instance().recordLoginError(nullEvent);
        assertMetric("keycloak_failed_login_attempts", 1,  "provider", "keycloak", "error", "", "client_id", "", "realm", "");
    }

    private void assertGenericMetric(String metricName, double metricValue, String ... tags) throws IOException {
        MatcherAssert.assertThat("Metric value match", meterRegistry.counter(metricName, tags).count() == metricValue);
    }

    private void assertMetric(String metricName, double metricValue, String ... tags) throws IOException {

        this.assertGenericMetric(metricName, metricValue, tags);
    }

    private Event createEvent(EventType type, String realmName, String clientId, String error, Tuple<String, String>... tuples) {
        final Event event = new Event();
        event.setType(type);
        event.setRealmName(realmName);
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

    private Event createEvent(EventType type, String realmName, String clientId, Tuple<String, String>... tuples) {
        return this.createEvent(type, realmName, clientId, (String) null, tuples);
    }

    private Event createEvent(EventType type) {
        return createEvent(type, DEFAULT_REALM_NAME, "THE_CLIENT_ID",(String) null);
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
