package com.menora.initializr.fullstack.expr;

import com.menora.initializr.fullstack.expr.Expr.Binary;
import com.menora.initializr.fullstack.expr.Expr.Call;
import com.menora.initializr.fullstack.expr.Expr.Lit;
import com.menora.initializr.fullstack.expr.Expr.Node;
import com.menora.initializr.fullstack.expr.Expr.Ref;
import com.menora.initializr.fullstack.expr.Expr.Unary;
import com.menora.initializr.fullstack.expr.ExprCheck.Checked;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.Map;
import java.util.Objects;

/**
 * Runs a checked expression in the generator, with the meaning the generated runtimes give it:
 * an empty value (null) makes an operation empty, a comparison with one is unknown (null), and
 * {@code and}/{@code or} follow three-valued logic. Values: BigDecimal, String (an enum's
 * constant too), Boolean, LocalDate, LocalDateTime, Instant, LocalTime.
 */
public final class ExprEval {

    private ExprEval() {
    }

    /** The value of {@code c} for a row whose fields are {@code values} (a missing name is empty). */
    public static Object eval(Checked c, Map<String, Object> values, LocalDate today) {
        return new Runner(c, values, today).run(c.root());
    }

    /** A rule's check: it fails only on false — empty (unknown) holds. */
    public static boolean holds(Checked check, Checked when, Map<String, Object> values, LocalDate today) {
        if (when != null && !Boolean.TRUE.equals(eval(when, values, today))) return true;
        return !Boolean.FALSE.equals(eval(check, values, today));
    }

    private record Runner(Checked c, Map<String, Object> values, LocalDate today) {

        Object run(Node node) {
            if (node instanceof Lit lit) return c.literal(lit);
            if (node instanceof Ref ref) return values.get(ref.name());
            if (node instanceof Unary u) {
                Object v = run(u.operand());
                if (v == null) return null;
                return u.op().equals("-") ? ((BigDecimal) v).negate() : !((Boolean) v);
            }
            if (node instanceof Binary b) return binary(b);
            return call((Call) node);
        }

        private Object binary(Binary b) {
            if (b.op().equals("and") || b.op().equals("or")) {
                Boolean l = (Boolean) run(b.left());
                Boolean r = (Boolean) run(b.right());
                boolean and = b.op().equals("and");
                if (and) {
                    if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) return false;
                    return l == null || r == null ? null : true;
                }
                if (Boolean.TRUE.equals(l) || Boolean.TRUE.equals(r)) return true;
                return l == null || r == null ? null : false;
            }
            Object l = run(b.left());
            Object r = run(b.right());
            if (l == null || r == null) return null;
            return switch (b.op()) {
                case "==" -> compare(l, r) == 0;
                case "!=" -> compare(l, r) != 0;
                case "<" -> compare(l, r) < 0;
                case "<=" -> compare(l, r) <= 0;
                case ">" -> compare(l, r) > 0;
                case ">=" -> compare(l, r) >= 0;
                case "+" -> l instanceof String ls ? ls + r
                        : l instanceof LocalDate d ? d.plusDays(((BigDecimal) r).longValue())
                        : r instanceof LocalDate d ? d.plusDays(((BigDecimal) l).longValue())
                        : ((BigDecimal) l).add((BigDecimal) r);
                case "-" -> l instanceof LocalDate d ? d.minusDays(((BigDecimal) r).longValue())
                        : ((BigDecimal) l).subtract((BigDecimal) r);
                case "*" -> ((BigDecimal) l).multiply((BigDecimal) r);
                default -> ((BigDecimal) r).signum() == 0 ? null : ((BigDecimal) l).divide((BigDecimal) r, MathContext.DECIMAL64);
            };
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static int compare(Object l, Object r) {
            if (l instanceof Boolean || l instanceof String && !(r instanceof String)) return Objects.equals(l, r) ? 0 : 1;
            return ((Comparable) l).compareTo(r);
        }

        private Object call(Call fn) {
            String name = ExprCheck.FUNCTIONS.stream().filter(n -> n.equalsIgnoreCase(fn.fn())).findFirst().orElseThrow();
            return switch (name) {
                case "isEmpty" -> {
                    Object v = run(fn.args().get(0));
                    yield v == null || v instanceof String s && s.isBlank();
                }
                case "today" -> today;
                case "now" -> today.atTime(10, 0);
                case "date" -> c.literal(fn);
                case "daysBetween" -> {
                    Object a = run(fn.args().get(0));
                    Object b = run(fn.args().get(1));
                    yield a == null || b == null ? null : BigDecimal.valueOf(ChronoUnit.DAYS.between((Temporal) a, (Temporal) b));
                }
                case "length" -> {
                    Object v = run(fn.args().get(0));
                    yield v == null ? null : BigDecimal.valueOf(((String) v).length());
                }
                case "abs" -> {
                    Object v = run(fn.args().get(0));
                    yield v == null ? null : ((BigDecimal) v).abs();
                }
                default -> {
                    Object v = run(fn.args().get(0));
                    int places = ((BigDecimal) ((Lit) fn.args().get(1)).value()).intValue();
                    yield v == null ? null : ((BigDecimal) v).setScale(places, RoundingMode.HALF_UP);
                }
            };
        }
    }

    /** {@code now()} as the generator sees it: today at ten. */
    public static LocalDateTime now(LocalDate today) {
        return today.atTime(10, 0);
    }
}
