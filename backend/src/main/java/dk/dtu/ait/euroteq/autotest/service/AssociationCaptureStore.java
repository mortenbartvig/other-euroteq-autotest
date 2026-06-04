package dk.dtu.ait.euroteq.autotest.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store that captures the associationId returned by the home server during broker
 * enrollment flows. The proxy endpoint writes to this store; the test execution service reads
 * and cleans up after each test.
 *
 * Key format: "{homeServerId}:{proxySessionId}"
 */
@Component
public class AssociationCaptureStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    public void capture(String key, String associationId) {
        store.put(key, associationId);
    }

    public String lookup(String key) {
        return store.get(key);
    }

    public void remove(String key) {
        store.remove(key);
    }
}
