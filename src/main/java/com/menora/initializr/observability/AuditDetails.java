package com.menora.initializr.observability;

import io.spring.initializr.web.project.ProjectRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a generation request asked for, as the endpoint resolved it — for the audit row. The
 * {@link GenerationAuditFilter} reads a request's parameters, which is all a {@code GET} carries;
 * a wizard, fullstack or patch request sends JSON, so its controller says here what it was given
 * (the request after defaults, the wizards used, the entity count). Held as a request attribute,
 * so it lives exactly as long as the request; outside one, every call does nothing.
 */
public final class AuditDetails {

    static final String ATTRIBUTE = AuditDetails.class.getName();

    /** The resolved request, read when the response is done (dependencies added late count too). */
    ProjectRequest request;
    /** Every dependency of a project that is more than one request (a multi-module project's modules). */
    List<String> dependencies;
    String kind;
    String department;
    final Set<String> wizards = new LinkedHashSet<>();
    Integer entityCount;
    /** The project's snapshot ({@code config/GeneratedSnapshots}): its id and its files, model file aside. */
    String snapshotId;
    java.util.Map<String, byte[]> snapshotTree;

    /** The request's details, made on first use; null outside a request. */
    static AuditDetails current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object held = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (held instanceof AuditDetails details) return details;
        AuditDetails details = new AuditDetails();
        attributes.setAttribute(ATTRIBUTE, details, RequestAttributes.SCOPE_REQUEST);
        return details;
    }

    /** What the filter finds on the request when the response is done; null when nobody said anything. */
    static AuditDetails of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof AuditDetails details ? details : null;
    }

    /** The generation request as resolved. A later call wins (a patch's target side comes second). */
    public static void request(ProjectRequest request) {
        AuditDetails d = current();
        if (d == null || request == null) return;
        d.request = request;
        d.dependencies = null;
        d.wizards.clear();
        d.entityCount = null;
    }

    /** The dependencies of the whole project, where the request holds only part of them. */
    public static void dependencies(Collection<String> dependencies) {
        AuditDetails d = current();
        if (d != null) d.dependencies = List.copyOf(dependencies);
    }

    /** {@code backend}, {@code wizard}, {@code multimodule}, {@code fullstack} or {@code frontend}. */
    public static void kind(String kind) {
        AuditDetails d = current();
        if (d != null) d.kind = kind;
    }

    public static void department(String department) {
        AuditDetails d = current();
        if (d != null && department != null && !department.isBlank()) d.department = department;
    }

    /** A wizard given a document: {@code sql}, {@code openapi}, {@code soap}, {@code events}. */
    public static void wizards(Collection<String> names) {
        AuditDetails d = current();
        if (d != null) d.wizards.addAll(names);
    }

    public static void entityCount(int count) {
        AuditDetails d = current();
        if (d != null) d.entityCount = count;
    }

    /**
     * What the request generated, by its snapshot id. A later call wins: an update generates its
     * base side first and its result last, and the result is what the project becomes.
     */
    public static void snapshot(String id, java.util.Map<String, byte[]> tree) {
        AuditDetails d = current();
        if (d == null) return;
        d.snapshotId = id;
        d.snapshotTree = tree;
    }

    /** The snapshot id this request has generated so far, or null. */
    public static String snapshotId() {
        AuditDetails d = currentIfAny();
        return d == null ? null : d.snapshotId;
    }

    /** The request's details when somebody has said something; null otherwise or outside a request. */
    static AuditDetails currentOrNull() {
        return currentIfAny();
    }

    private static AuditDetails currentIfAny() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        return attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof AuditDetails d ? d : null;
    }
}
