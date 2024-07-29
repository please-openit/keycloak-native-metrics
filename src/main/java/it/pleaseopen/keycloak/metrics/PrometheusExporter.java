package it.pleaseopen.keycloak.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.BasicAuthHttpConnectionFactory;
import io.prometheus.client.exporter.PushGateway;
import io.prometheus.client.exporter.common.TextFormat;
import io.prometheus.client.hotspot.DefaultExports;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

public final class PrometheusExporter {

    private final static String USER_EVENT_PREFIX = "keycloak_user_event_";
    private final static String ADMIN_EVENT_PREFIX = "keycloak_admin_event_";
    private final static String PROVIDER_KEYCLOAK_OPENID = "keycloak";

    private static PrometheusExporter INSTANCE;

    private final static Logger logger = Logger.getLogger(PrometheusExporter.class);

    // these fields are package private on purpose
    //final Map<String, Counter> counters = new HashMap<>();
    final Map<String, io.micrometer.core.instrument.Counter.Builder> counters = new HashMap<>();

    //final Counter totalRegistrations;
    final PushGateway PUSH_GATEWAY;
    private final MeterRegistry meterRegistry = Metrics.globalRegistry;

    String totalLogins = "keycloak_logins";
    String totalLoginsAttempts = "keycloak_login_attempts";
    String totalFailedLoginAttempts = "keycloak_failed_login_attempts";
    String totalRegistrations = "keycloak_registrations";
    String totalRegistrationsErrors = "keycloak_registrations_errors";
    String totalRefreshTokens = "keycloak_refresh_tokens";
    String totalRefreshTokensErrors = "keycloak_refresh_tokens_errors";
    String totalClientLogins = "keycloak_client_logins";
    String totalFailedClientLoginAttempts = "keycloak_failed_client_login_attempts";
    String totalCodeToTokens = "keycloak_code_to_tokens";
    String totalCodeToTokensErrors = "keycloak_code_to_tokens_errors";

    private PrometheusExporter() {
        // The metrics collector needs to be a singleton because requiring a
        // provider from the KeyCloak session (session#getProvider) will always
        // create a new instance. Not sure if this is a bug in the SPI implementation
        // or intentional but better to avoid this. The metrics object is single-instance
        // anyway and all the Gauges are suggested to be static (it does not really make
        // sense to record the same metric in multiple places)

        //this.meterRegistry = Metrics.globalRegistry;

        PUSH_GATEWAY = buildPushGateWay();

        // Counters for all user events
        for (EventType type : EventType.values()) {
            if (type.equals(EventType.LOGIN) || type.equals(EventType.LOGIN_ERROR) || type.equals(EventType.REGISTER)) {
                continue;
            }
            final String counterName = buildCounterName(type);
            counters.put(counterName, createCounter(counterName, false));
        }

        // Counters for all admin events
        for (OperationType type : OperationType.values()) {
            final String counterName = buildCounterName(type);
            counters.put(counterName, createCounter(counterName, true));
        }

        // Initialize the default metrics for the hotspot VM
        DefaultExports.initialize();
    }

    public static synchronized PrometheusExporter instance() {
        if (INSTANCE == null) {
            INSTANCE = new PrometheusExporter();
        }
        return INSTANCE;
    }

    /**
     * Creates a counter based on a event name
     */
    private static io.micrometer.core.instrument.Counter.Builder createCounter(final String name, boolean isAdmin) {
        //final Counter.Builder counter = Counter.build().name(name);

        final io.micrometer.core.instrument.Counter.Builder counter = io.micrometer.core.instrument.Counter.builder(name);

        if (isAdmin) {
            counter.description("Generic KeyCloak Admin event");
            //counter.labelNames("realm", "resource").help("Generic KeyCloak Admin event");
        } else {
            counter.description("Generic KeyCloak User event");
            //counter.labelNames("realm").help("Generic KeyCloak User event");
        }

        return counter;
    }

    /**
     * Count generic user event
     *
     * @param event         User event
     * @param realmProvider
     */
    public void recordGenericEvent(final Event event, RealmProvider realmProvider) {
        final String counterName = buildCounterName(event.getType());
        if (counters.get(counterName) == null) {
            logger.warnf("Counter for event type %s does not exist. Realm: %s", event.getType().name(), nullToEmpty(getRealmName(event.getRealmId(), realmProvider)));
            return;
        }
        counters.get(counterName).tags("realm",nullToEmpty(getRealmName(event.getRealmId(), realmProvider))).register(meterRegistry).increment();
        //pushAsync();
    }

    /**
     * Count generic admin event
     *
     * @param event         Admin event
     * @param realmProvider
     */
    public void recordGenericAdminEvent(final AdminEvent event, RealmProvider realmProvider) {
        final String counterName = buildCounterName(event.getOperationType());
        if (counters.get(counterName) == null) {
            logger.warnf("Counter for admin event operation type %s does not exist. Resource type: %s, realm: %s", event.getOperationType().name(), event.getResourceType().name(), event.getRealmId());
            return;
        }
        counters.get(counterName).tags("realm",nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "resource", event.getResourceType().name()).register(meterRegistry).increment();

        //pushAsync();
    }

    /**
     * Increase the number of currently logged in users
     *
     * @param event         Login event
     * @param realmProvider
     */
    public void recordLogin(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalLoginsAttempts, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId())).increment();
        meterRegistry.counter(totalLogins, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId())).increment();
        //pushAsync();
    }

    /**
     * Increase the number registered users
     *
     * @param event         Register event
     * @param realmProvider
     */
    public void recordRegistration(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalRegistrations, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId())).increment();
        //pushAsync();
    }

    /**
     * Increase the number of failed registered users attemps
     *
     * @param event         RegisterError event
     * @param realmProvider
     */
    public void recordRegistrationError(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalRegistrationsErrors, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId()), "error", nullToEmpty(event.getError())).increment();
        //pushAsync();
    }

    /**
     * Increase the number of failed login attempts
     *
     * @param event         LoginError event
     * @param realmProvider
     */
    public void recordLoginError(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);

        meterRegistry.counter(totalLoginsAttempts, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId())).increment();
        meterRegistry.counter(totalFailedLoginAttempts, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId()), "error", nullToEmpty(event.getError())).increment();
        //pushAsync();
    }

    /**
     * Increase the number of currently client logged
     *
     * @param event         ClientLogin event
     * @param realmProvider
     */
    public void recordClientLogin(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalClientLogins, "realm",nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId())).increment();
        //pushAsync();
    }

    /**
     * Increase the number of failed login attempts
     *
     * @param event         ClientLoginError event
     * @param realmProvider
     */
    public void recordClientLoginError(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalFailedClientLoginAttempts, "realm",nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId()), "error", nullToEmpty(event.getError())).increment();
        //pushAsync();
    }

    /**
     * Increase the number of refreshes tokens
     *
     * @param event         RefreshToken event
     * @param realmProvider
     */
    public void recordRefreshToken(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalRefreshTokens, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId())).increment();
        //pushAsync();
    }

    /**
     * Increase the number of failed refreshes tokens attempts
     *
     * @param event         RefreshTokenError event
     * @param realmProvider
     */
    public void recordRefreshTokenError(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalRefreshTokensErrors, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId()),  "error", nullToEmpty(event.getError())).increment();
        //pushAsync();
    }

    /**
     * Increase the number of code to tokens
     *
     * @param event         CodeToToken event
     * @param realmProvider
     */
    public void recordCodeToToken(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalCodeToTokens, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId())).increment();
        //pushAsync();
    }

    /**
     * Increase the number of failed code to tokens attempts
     *
     * @param event         CodeToTokenError event
     * @param realmProvider
     */
    public void recordCodeToTokenError(final Event event, RealmProvider realmProvider) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(totalCodeToTokensErrors, "realm", nullToEmpty(getRealmName(event.getRealmId(), realmProvider)), "provider", provider, "client_id", nullToEmpty(event.getClientId()), "error", nullToEmpty(event.getError())).increment();
        //pushAsync();
    }

    /**
     * Retrieve the identity prodiver name from event details or
     * default to {@value #PROVIDER_KEYCLOAK_OPENID}.
     *
     * @param event User event
     * @return Identity provider name
     */
    private String getIdentityProvider(Event event) {
        String identityProvider = null;
        if (event.getDetails() != null) {
            identityProvider = event.getDetails().get("identity_provider");
        }
        if (identityProvider == null) {
            identityProvider = PROVIDER_KEYCLOAK_OPENID;
        }
        return identityProvider;
    }

    /**
     * Retrieve the real realm name in the event by id from the RealmProvider.
     *
     * @param realmId Id of Realm
     * @param realmProvider RealmProvider instance
     * @return Realm name
     */
    private String getRealmName(String realmId, RealmProvider realmProvider) {
        RealmModel realm = null;
        if (realmId != null) {
             realm = realmProvider.getRealm(realmId);
        }
        if (realm != null) {
            return realm.getName();
        }
        return null;
    }
    /**
     * Write the Prometheus formatted values of all counters and
     * gauges to the stream
     *
     * @param stream Output stream
     * @throws IOException
     */
    public void export(final OutputStream stream) throws IOException {
        final Writer writer = new BufferedWriter(new OutputStreamWriter(stream));
        TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        writer.flush();
    }

    /**
     * Build a prometheus pushgateway if an address is defined in environment.
     *
     * @return PushGateway
     */
    private PushGateway buildPushGateWay() {
        // host:port or ip:port of the Pushgateway.
        PushGateway pg = null;
        String host = System.getenv("PROMETHEUS_PUSHGATEWAY_ADDRESS");
        if (host != null) {
            // if protocoll is missing in host, we assume http
            if (!host.toLowerCase().startsWith("http://") && !host.startsWith("https://")) {
                host = "http://" + host;
            }
            try {
                pg = new PushGateway(new URL(host));
                logger.info("Pushgateway created with url " + host + ".");
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
            String basic_auth_username = System.getenv("PROMETHEUS_PUSHGATEWAY_BASIC_AUTH_USERNAME");
            String basic_auth_password = System.getenv("PROMETHEUS_PUSHGATEWAY_BASIC_AUTH_PASSWORD");
            if (basic_auth_username != null && basic_auth_password != null) {
                logger.info("Enabled basic auth for pushgateway.");
                pg.setConnectionFactory(new BasicAuthHttpConnectionFactory(basic_auth_username, basic_auth_password));
            }
        }
        return pg;
    }

/*    public void pushAsync() {
        CompletableFuture.runAsync(() -> push());
    }*/

    private static String instanceIp() throws UnknownHostException {
        return InetAddress.getLocalHost().getHostAddress();
    }

    private String buildCounterName(OperationType type) {
        return ADMIN_EVENT_PREFIX + type.name();
    }

    private String buildCounterName(EventType type) {
        return USER_EVENT_PREFIX + type.name();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
