package com.menora.initializr.extension.frontend;

import com.menora.initializr.db.entity.DependencyEntryEntity;
import io.spring.initializr.generator.version.Version;
import io.spring.initializr.generator.version.VersionParser;
import io.spring.initializr.generator.version.VersionRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Filters frontend dependency entries by their {@code compatibilityRange}
 * against the requested React version.
 *
 * <p>Reuses Spring Initializr's {@link VersionRange} parser so the syntax
 * matches the backend pipeline (e.g. {@code [18.0.0,19.0.0)}). React versions
 * arrive as bare majors ({@code "18"}, {@code "19"}); this helper coerces them
 * to full semantic versions before matching.
 *
 * <p>An entry with a blank or unparseable range is treated as compatible with
 * every React version, matching the v1 behavior before filtering existed.
 */
@Component
public class FrontendVersionRangeFilter {

    private static final Logger log = LoggerFactory.getLogger(FrontendVersionRangeFilter.class);
    private static final VersionParser PARSER = new VersionParser(Collections.emptyList());

    /** True when {@code entry} has no range, or its range matches {@code reactVersion}. */
    public boolean matches(DependencyEntryEntity entry, String reactVersion) {
        String range = entry.getCompatibilityRange();
        if (range == null || range.isBlank()) return true;
        Version v = parseVersion(reactVersion);
        if (v == null) return true;
        try {
            VersionRange parsed = PARSER.parseRange(range);
            return parsed.match(v);
        } catch (Exception e) {
            log.warn("Bad compatibilityRange '{}' on dep '{}' — treating as open", range, entry.getDepId());
            return true;
        }
    }

    /** Filters {@code depIds} down to those compatible with the requested React version. */
    public Set<String> filterCompatibleDepIds(Set<String> depIds,
                                              java.util.Map<String, DependencyEntryEntity> entriesById,
                                              String reactVersion) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : depIds) {
            DependencyEntryEntity entry = entriesById.get(id);
            if (entry == null || matches(entry, reactVersion)) {
                out.add(id);
            } else {
                log.info("Dropping incompatible dep '{}' for React {} (range {})",
                        id, reactVersion, entry.getCompatibilityRange());
            }
        }
        return out;
    }

    private static Version parseVersion(String reactVersion) {
        if (reactVersion == null || reactVersion.isBlank()) return null;
        String coerced = reactVersion.trim();
        if (!coerced.contains(".")) coerced = coerced + ".0.0";
        else if (coerced.chars().filter(c -> c == '.').count() == 1) coerced = coerced + ".0";
        try {
            return PARSER.parse(coerced);
        } catch (Exception e) {
            log.warn("Could not parse React version '{}' — skipping range filtering", reactVersion);
            return null;
        }
    }
}
