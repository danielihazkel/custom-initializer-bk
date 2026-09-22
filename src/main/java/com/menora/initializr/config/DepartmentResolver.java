package com.menora.initializr.config;

import com.menora.initializr.db.entity.DepartmentEntity;
import com.menora.initializr.db.repository.DepartmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the department a generation request asked for into the Mustache variables every
 * render context exposes ({@code department}, {@code departmentUpper}, {@code departmentName}).
 *
 * <p>Resolution: the requested id when it exists, else the {@code isDefault} row, else the
 * {@code lts} sentinel (the value the templates hardcoded before departments were selectable).
 * An unknown id falls back with a warning rather than failing, like palette resolution.
 */
@Service
public class DepartmentResolver {

    public static final String FALLBACK_ID = "lts";

    private static final Logger log = LoggerFactory.getLogger(DepartmentResolver.class);

    private final DepartmentRepository departmentRepo;

    public DepartmentResolver(DepartmentRepository departmentRepo) {
        this.departmentRepo = departmentRepo;
    }

    public record Department(String id, String name) {
        public String upper() {
            return id.toUpperCase(Locale.ROOT);
        }
    }

    public Department resolve(String requestedId) {
        if (requestedId != null && !requestedId.isBlank()) {
            Optional<DepartmentEntity> explicit = departmentRepo.findByDepartmentId(requestedId.trim());
            if (explicit.isPresent()) return toDepartment(explicit.get());
            log.warn("Unknown department '{}' — falling back to the default", requestedId);
        }
        return departmentRepo.findFirstByIsDefaultTrueOrderBySortOrderAsc()
                .or(() -> departmentRepo.findAllByOrderBySortOrderAsc().stream().findFirst())
                .map(DepartmentResolver::toDepartment)
                .orElse(new Department(FALLBACK_ID, FALLBACK_ID.toUpperCase(Locale.ROOT)));
    }

    /** Resolves {@code requestedId} and puts the department variables into {@code ctx}. */
    public void putVars(Map<String, Object> ctx, String requestedId) {
        Department d = resolve(requestedId);
        ctx.put("department", d.id());
        ctx.put("departmentUpper", d.upper());
        ctx.put("departmentName", d.name());
    }

    private static Department toDepartment(DepartmentEntity e) {
        return new Department(e.getDepartmentId(), e.getName());
    }
}
