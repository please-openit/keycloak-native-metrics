package it.pleaseopen.keycloak.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MetricsEventListener implements EventListenerProvider {
    public final static String ID = "native-metrics-listener";
    private final static String USER_EVENT_PREFIX = "keycloak_user_event_";
    private final static String ADMIN_EVENT_PREFIX = "keycloak_admin_event_";
    private KeycloakSession session;
    private final MeterRegistry meterRegistry = Metrics.globalRegistry;
    private final static String activeSessions = "keycloak_user_active_sessions_by_client";
    private final static String activeOfflineSessions = "keycloak_user_active_offline_sessions_by_client";
    final Map<String, Counter.Builder> counters = new HashMap<>();

    private static MetricsEventListener _instance = new MetricsEventListener();

    public static MetricsEventListener getInstance(){
        return _instance;
    }
    public void setSession(KeycloakSession session){
        if(this.session == null){
            this.session = session;
        }
    }

    private MetricsEventListener() {
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
    }



    public void countSessions(final Event event){
        if(event.getClientId() == null){
            return;
        }
        RealmModel realm = session.realms().getRealm(event.getRealmId());
        ClientModel client = session.clients().getClientByClientId(realm, event.getClientId());
        List<Tag> tags = new ArrayList<>();
        tags.add(Tag.of("realm", nullToEmpty(getRealmName(event.getRealmId(), session.realms() )) ) );
        tags.add(Tag.of("client_id", nullToEmpty(event.getClientId()) ));
        io.micrometer.core.instrument.Gauge.builder(activeSessions, session, s -> s.sessions().getActiveUserSessions(realm, client))
            .strongReference(true)//Add strong reference
            .tags(tags)
            .register(meterRegistry);

        io.micrometer.core.instrument.Gauge.builder(activeOfflineSessions, session, s -> s.sessions().getOfflineSessionsCount(realm, client))
            .strongReference(true)//Add strong reference
            .tags(tags)
            .register(meterRegistry);
    }

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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void onEvent(Event event) {
        if(event.getType() == null)
            return;
        countSessions(event);
        final String counterName = buildCounterName(event.getType());
        if (counters.get(counterName) == null) {
            counters.put(counterName, createCounter(counterName, false));
        }
        counters.get(counterName).tags("realm",nullToEmpty(getRealmName(event.getRealmId(), session.realms())), "client_id", nullToEmpty(event.getClientId())).register(meterRegistry).increment();
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        final String counterName = buildCounterName(event.getOperationType());
        if (counters.get(counterName) == null) {
            counters.put(counterName, createCounter(counterName, true));
        }
        counters.get(counterName).tags("realm",nullToEmpty(getRealmName(event.getRealmId(), session.realms())), "resource", event.getResourceType().name()).register(meterRegistry).increment();
    }

    private String buildCounterName(OperationType type) {
        return ADMIN_EVENT_PREFIX + type.name();
    }

    private String buildCounterName(EventType type) {
        return USER_EVENT_PREFIX + type.name();
    }

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

    @Override
    public void close() {
        // unused
    }
}
