package com.menora.initializr.design;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.menora.initializr.config.FullstackStarterController;
import com.menora.initializr.db.entity.FullstackExampleEntity;
import com.menora.initializr.db.entity.FullstackModelEntity;
import com.menora.initializr.db.repository.FullstackExampleRepository;
import com.menora.initializr.db.repository.FullstackModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Who generates with a kit: the fullstack examples and the team models, each with the version it
 * is pinned to (null = the kit's latest) and how it gets the kit — named ({@code pinned}), through a
 * frontend set that stands for it ({@code set}), or by naming none while it is the house kit
 * ({@code house}). The Studio shows it before a version is deprecated or a kit disabled.
 */
@Service
public class DesignKitUsage {

    private final DesignKitService kits;
    private final FullstackExampleRepository examples;
    private final FullstackModelRepository models;
    private final ObjectMapper mapper;

    public DesignKitUsage(DesignKitService kits, FullstackExampleRepository examples, FullstackModelRepository models,
                          ObjectMapper mapper) {
        this.kits = kits;
        this.examples = examples;
        this.models = models;
        this.mapper = mapper;
    }

    public record User(String id, String name, Integer version, String via) {
    }

    public record Usage(String key, boolean houseKit, List<User> examples, List<User> models) {
    }

    @Transactional(readOnly = true)
    public Usage of(String key) {
        DesignKitService.KitSummary kit = kits.get(key);
        List<User> ex = new ArrayList<>();
        for (FullstackExampleEntity e : examples.findAllByOrderBySortOrderAscIdAsc()) {
            JsonNode settings = parse(e.getSettings());
            User u = user(kit, e.getExampleId(), e.getName(), text(settings, "designKit"), null, text(settings, "frontendTemplateSet"));
            if (u != null) ex.add(u);
        }
        List<User> mo = new ArrayList<>();
        for (FullstackModelEntity m : models.findAll()) {
            JsonNode snapshot = parse(m.getSnapshot());
            JsonNode pin = snapshot == null ? null : snapshot.get("designKit");
            String pinKey = pin != null && pin.isObject() ? text(pin, "key") : null;
            Integer pinVersion = pin != null && pin.isObject() && pin.path("version").isInt() ? pin.get("version").asInt() : null;
            User u = user(kit, String.valueOf(m.getId()), m.getName(), pinKey, pinVersion, text(snapshot, "frontendSet"));
            if (u != null) mo.add(u);
        }
        return new Usage(key, kit.defaultKit(), ex, mo);
    }

    /** {@code ref} is {@code key}, {@code key@N} or null; {@code version} a separately pinned number. */
    private User user(DesignKitService.KitSummary kit, String id, String name, String ref, Integer version, String set) {
        if (ref != null && !ref.isBlank()) {
            int at = ref.indexOf('@');
            String refKey = at < 0 ? ref : ref.substring(0, at);
            if (!refKey.equals(kit.key())) return null;
            Integer v = version;
            if (v == null && at >= 0) {
                try {
                    v = Integer.valueOf(ref.substring(at + 1));
                } catch (NumberFormatException ignored) {
                    v = null;
                }
            }
            return new User(id, name, v, "pinned");
        }
        if (set != null && kit.aliases().contains(set)) return new User(id, name, null, "set");
        String generated = set == null || set.isBlank() ? FullstackStarterController.DEFAULT_FRONTEND_SET : set;
        if (kit.defaultKit() && kit.baseSetKey().equals(generated)) return new User(id, name, null, "house");
        return null;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v != null && v.isTextual() ? v.asText() : null;
    }
}
