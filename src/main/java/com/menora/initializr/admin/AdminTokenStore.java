package com.menora.initializr.admin;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AdminTokenStore {

    private final Set<String> tokens = ConcurrentHashMap.newKeySet();

    public void add(String token) {
        tokens.add(token);
    }

    public void remove(String token) {
        tokens.remove(token);
    }

    public boolean isValid(String token) {
        return token != null && tokens.contains(token);
    }
}
