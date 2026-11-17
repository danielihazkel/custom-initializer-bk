package com.menora.initializr.fullstack;

import com.menora.initializr.fullstack.expr.Expr;
import com.menora.initializr.fullstack.expr.ExprCheck;
import com.menora.initializr.fullstack.expr.ExprEval;
import com.menora.initializr.gen.Naming;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Values for the rows the generator makes itself — the API tests' rows and the form test's valid
 * row — that an entity's rules accept. The rules are run here ({@link ExprEval}) over a small set
 * of candidates for each field they read, and the first combination every rule holds for is
 * taken; the other fields keep the values they always had. The demo rows (which go to the
 * repository, past the rules) keep their varied values, a date the rules read shifted by the
 * chosen number of days so two dates keep the order a rule wants.
 *
 * <p>A workflow's status is not the row's to choose: the rules must hold in the state a row is
 * created in, in the state the generated test moves it to, and with no state at all (the form's
 * test row has none). Dates are counted from today, so a rule reading {@code today()} keeps
 * holding on the day the tests run.
 */
final class RuleRows {

    private RuleRows() {
    }

    /** Candidates tried, at most, before giving up. */
    private static final int MAX_STEPS = 200_000;

    /**
     * A value for one field: what the rules see, and how each place writes it — the API test's
     * JSON ({@code java}, null: left out), the form test's literal ({@code ts}, null: left out),
     * the demo row's setter argument ({@code seed}, null: the usual one).
     */
    record Pick(Object value, String java, String ts, String seed) {}

    /** The picks for the fields the rules read; {@code usesDays}: a TS value calls {@code day(k)}. */
    record Solution(Map<String, Pick> picks, boolean usesDays) {
        static final Solution NONE = new Solution(Map.of(), false);
    }

    /** No candidate combination passes the rules: which rule stopped the last one. */
    static final class Unsolvable extends Exception {
        final int rule;

        Unsolvable(int rule) {
            super(null, null, false, false);
            this.rule = rule;
        }
    }

    private record Checked(ExprCheck.Checked when, ExprCheck.Checked check, Set<String> refs) {}

    /** Every field {@code e}'s rules read. */
    static Set<String> read(EntityDefinition e) {
        Set<String> out = new LinkedHashSet<>();
        for (EntityDefinition.Rule r : e.rules()) {
            out.addAll(ExprCheck.check(r.check(), e.fields(), Expr.Type.BOOL).refs());
            if (r.when() != null) out.addAll(ExprCheck.check(r.when(), e.fields(), Expr.Type.BOOL).refs());
        }
        return out;
    }

    /** The workflow states a test row must pass the rules in (null: none at all). */
    static List<String> states(EntityDefinition e) {
        EntityDefinition.Workflow wf = e.workflow();
        List<String> out = new ArrayList<>();
        out.add(null);
        if (wf == null) return out;
        out.add(wf.initial());
        wf.transitions().stream().filter(t -> t.from().contains(wf.initial()) && t.requires().isEmpty())
                .findFirst().ifPresent(t -> out.add(t.to()));
        return out;
    }

    /** Values for the fields {@code e}'s rules read, among those {@code sent} (the rest are empty). */
    static Solution solve(EntityDefinition e, List<FieldDefinition> sent) throws Unsolvable {
        if (e.rules().isEmpty()) return Solution.NONE;
        List<Checked> rules = new ArrayList<>();
        Set<String> read = new LinkedHashSet<>();
        for (EntityDefinition.Rule r : e.rules()) {
            ExprCheck.Checked check = ExprCheck.check(r.check(), e.fields(), Expr.Type.BOOL);
            ExprCheck.Checked when = r.when() == null ? null : ExprCheck.check(r.when(), e.fields(), Expr.Type.BOOL);
            Set<String> refs = new LinkedHashSet<>(check.refs());
            if (when != null) refs.addAll(when.refs());
            rules.add(new Checked(when, check, refs));
            read.addAll(refs);
        }
        EntityDefinition.Workflow wf = e.workflow();
        String status = wf == null ? null : wf.statusField();
        String assignee = wf == null ? null : wf.assigneeField();
        Set<String> keyed = new LinkedHashSet<>();
        for (EntityDefinition.UniqueKey k : e.uniqueKeys()) keyed.addAll(k.fields());

        LocalDate today = LocalDate.now();
        List<String> vars = new ArrayList<>();
        Map<String, List<Pick>> options = new LinkedHashMap<>();
        Map<String, Pick> fixed = new LinkedHashMap<>();
        boolean[] days = {false};
        for (String name : read) {
            if (name.equals(status) || name.equals(assignee)) continue;
            FieldDefinition f = e.fields().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
            boolean isSent = sent.stream().anyMatch(x -> x.name().equals(name));
            if (!isSent || (f.primaryKey() && f.generated())) {
                fixed.put(name, new Pick(null, null, null, null));
            } else if (f.primaryKey() || f.unique() || keyed.contains(name) || f.pattern() != null
                    || ExprCheck.typeOf(f) == null) {
                // Its value differs by row, or is not the generator's to choose: the usual one.
                fixed.put(name, new Pick(usual(f, today), null, null, null));
            } else {
                vars.add(name);
                options.put(name, candidates(e, f, today, days));
            }
        }
        List<String> stateValues = new ArrayList<>();
        for (String s : states(e)) {
            if (status != null && read.contains(status)) stateValues.add(s);
            else if (stateValues.isEmpty()) stateValues.add(null);
        }

        Map<String, Pick> chosen = search(rules, vars, options, fixed, status, assignee, stateValues, today);
        if (chosen == null) {
            // Which rule: the first that the rules before it and it cannot all be passed.
            for (int i = 0; i < rules.size(); i++) {
                if (search(rules.subList(0, i + 1), vars, options, fixed, status, assignee, stateValues, today) == null) {
                    throw new Unsolvable(i);
                }
            }
            throw new Unsolvable(rules.size() - 1);
        }
        Map<String, Pick> picks = new LinkedHashMap<>();
        for (String v : vars) picks.put(v, chosen.get(v));
        return new Solution(picks, days[0]);
    }

    /** A choice for every variable that the rules hold for, or null. */
    private static Map<String, Pick> search(List<Checked> rules, List<String> vars, Map<String, List<Pick>> options,
                                            Map<String, Pick> fixed, String status, String assignee,
                                            List<String> states, LocalDate today) {
        Map<String, Object> env = new HashMap<>();
        fixed.forEach((n, p) -> env.put(n, p.value()));
        Map<String, Pick> chosen = new LinkedHashMap<>(fixed);
        int[] steps = {0};
        int[] lastBroken = {0};
        return assign(0, vars, options, rules, env, chosen, status, assignee, states, today, steps, lastBroken) ? chosen : null;
    }

    private static boolean assign(int at, List<String> vars, Map<String, List<Pick>> options, List<Checked> rules,
                                  Map<String, Object> env, Map<String, Pick> chosen, String status, String assignee,
                                  List<String> states, LocalDate today, int[] steps, int[] lastBroken) {
        // Everything chosen: every rule must hold — those over fixed values only too.
        if (at == vars.size()) {
            return holds(rules, env, new LinkedHashSet<>(vars), vars, status, assignee, states, today, lastBroken);
        }
        String name = vars.get(at);
        Set<String> assigned = new LinkedHashSet<>(vars.subList(0, at + 1));
        for (Pick pick : options.get(name)) {
            if (++steps[0] > MAX_STEPS) return false;
            env.put(name, pick.value());
            chosen.put(name, pick);
            if (holds(rules, env, assigned, vars, status, assignee, states, today, lastBroken)
                    && assign(at + 1, vars, options, rules, env, chosen, status, assignee, states, today, steps, lastBroken)) {
                return true;
            }
        }
        env.remove(name);
        chosen.remove(name);
        return false;
    }

    /** Every rule whose fields are all chosen holds, in every state the row must pass in. */
    private static boolean holds(List<Checked> rules, Map<String, Object> env, Set<String> assigned, List<String> vars,
                                 String status, String assignee, List<String> states, LocalDate today, int[] lastBroken) {
        for (int i = 0; i < rules.size(); i++) {
            Checked r = rules.get(i);
            boolean ready = r.refs().stream().noneMatch(ref -> vars.contains(ref) && !assigned.contains(ref));
            if (!ready) continue;
            for (String state : states) {
                Map<String, Object> scenario = new HashMap<>(env);
                if (status != null) scenario.put(status, state);
                if (assignee != null) scenario.put(assignee, null);
                if (!ExprEval.holds(r.check(), r.when(), scenario, today)) {
                    lastBroken[0] = i;
                    return false;
                }
            }
        }
        return true;
    }

    // ── Candidates ─────────────────────────────────────────────────────────────

    private static final int[] DAY_OFFSETS = {0, -1, 1, -7, 7, -30, 30, -365, 365};

    private static List<Pick> candidates(EntityDefinition e, FieldDefinition f, LocalDate today, boolean[] days) {
        List<Pick> out = new ArrayList<>();
        Expr.Type type = ExprCheck.typeOf(f);
        switch (type) {
            case NUMBER -> {
                Set<BigDecimal> values = new LinkedHashSet<>();
                for (String n : new String[] {"1", "0", "10", "100", "2", "5", "50", "1000", "-1"}) values.add(new BigDecimal(n));
                if (f.min() != null) values.add(f.min());
                if (f.max() != null) values.add(f.max());
                boolean whole = f.type() == FieldType.INTEGER || f.type() == FieldType.LONG;
                for (BigDecimal v : values) {
                    if (whole) v = v.setScale(0, RoundingMode.CEILING);
                    if (f.min() != null && v.compareTo(f.min()) < 0 || f.max() != null && v.compareTo(f.max()) > 0) continue;
                    String plain = v.stripTrailingZeros().scale() < 0 ? v.setScale(0).toPlainString() : v.stripTrailingZeros().toPlainString();
                    String javaNum = switch (f.type()) {
                        case INTEGER -> plain;
                        case LONG -> plain + "L";
                        default -> "new java.math.BigDecimal(\"" + plain + "\")";
                    };
                    out.add(new Pick(new BigDecimal(plain), javaNum, plain, null));
                }
            }
            case TEXT -> {
                String base = f.email() ? "user1@example.com" : Naming.toPascalCase(f.name()) + " 1";
                for (String full : new String[] {base, "A longer " + Naming.toPascalCase(f.name()).toLowerCase() + " text, for a rule over its length"}) {
                    String s = f.length() != null && full.length() > f.length() ? full.substring(0, Math.max(1, f.length())) : full;
                    if (out.stream().anyMatch(p -> p.value().equals(s))) continue;
                    String lit = s.replace("\\", "\\\\").replace("\"", "\\\"");
                    out.add(new Pick(s, "\"" + lit + "\"", "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'", null));
                }
            }
            case BOOL -> {
                out.add(new Pick(true, "true", "true", null));
                out.add(new Pick(false, "false", "false", null));
            }
            case ENUM -> {
                for (String c : f.enumValues()) out.add(new Pick(c, "\"" + c + "\"", "'" + c + "'", null));
            }
            case DATE -> {
                for (int k : DAY_OFFSETS) {
                    out.add(new Pick(today.plusDays(k), "java.time.LocalDate.now().plusDays(" + k + ").toString()",
                            "day(" + k + ")", "java.time.LocalDate.now().minusDays(i).plusDays(" + k + ")"));
                }
                days[0] = true;
            }
            case DATETIME -> {
                for (int k : DAY_OFFSETS) {
                    String java = "java.time.LocalDate.now().plusDays(" + k + ").atTime(10, 0)";
                    out.add(new Pick(today.plusDays(k).atTime(10, 0), java + ".toString()", "`${day(" + k + ")}T10:00`",
                            "java.time.LocalDateTime.now().minusHours(i).plusDays(" + k + ")"));
                }
                days[0] = true;
            }
            case INSTANT -> {
                for (int k : DAY_OFFSETS) {
                    String java = "java.time.LocalDate.now().plusDays(" + k + ").atTime(10, 0).toInstant(java.time.ZoneOffset.UTC)";
                    out.add(new Pick(today.plusDays(k).atTime(10, 0).toInstant(ZoneOffset.UTC), java + ".toString()",
                            "`${day(" + k + ")}T10:00:00Z`",
                            "java.time.Instant.now().minus(java.time.Duration.ofHours(i)).plus(java.time.Duration.ofDays(" + k + "))"));
                }
                days[0] = true;
            }
            case TIME -> {
                for (int h : new int[] {8, 12, 17, 0, 23}) {
                    LocalTime t = LocalTime.of(h, 0);
                    String s = String.format("%02d:00", h);
                    out.add(new Pick(t, "\"" + s + "\"", "'" + s + "'", null));
                }
            }
            default -> { }
        }
        // An empty value makes a rule hold — when the field may be empty.
        if (!f.required()) out.add(new Pick(null, null, null, null));
        return out;
    }

    /** What a field the generator does not choose holds in a test row, as the rules see it. */
    private static Object usual(FieldDefinition f, LocalDate today) {
        Expr.Type type = ExprCheck.typeOf(f);
        if (type == null) return "attachment";
        return switch (type) {
            case NUMBER -> BigDecimal.ONE;
            case TEXT -> f.email() ? "user1@example.com" : Naming.toPascalCase(f.name()) + " 1";
            case BOOL -> true;
            case ENUM -> f.enumValues().get(0);
            case DATE -> today;
            case DATETIME -> today.atTime(10, 0);
            case INSTANT -> today.atTime(10, 0).toInstant(ZoneOffset.UTC);
            case TIME -> LocalTime.of(8, 0);
            case ANY -> "value";
        };
    }
}
