package dk.dtu.ait.euroteq.autotest.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealTokenStore {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    public void store(String sessionId, String token) {
        store.put(sessionId, token);
    }

    public String lookup(String sessionId) {
        return store.get(sessionId);
    }

    public void remove(String sessionId) {
        store.remove(sessionId);
    }
}
