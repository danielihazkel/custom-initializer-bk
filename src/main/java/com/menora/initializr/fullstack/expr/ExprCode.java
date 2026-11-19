package com.menora.initializr.fullstack.expr;

import com.menora.initializr.fullstack.FieldDefinition;
import com.menora.initializr.fullstack.expr.Expr.Binary;
import com.menora.initializr.fullstack.expr.Expr.Call;
import com.menora.initializr.fullstack.expr.Expr.Lit;
import com.menora.initializr.fullstack.expr.Expr.Node;
import com.menora.initializr.fullstack.expr.Expr.Ref;
import com.menora.initializr.fullstack.expr.Expr.Type;
import com.menora.initializr.fullstack.expr.Expr.Unary;
import com.menora.initializr.fullstack.expr.ExprCheck.Checked;
import com.menora.initializr.gen.Naming;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes a checked expression as code: Java over a row of the generated entity (the runtime is
 * the generated {@code rules/Expr.java}) and TypeScript over the form's value (the runtime is
 * the generated {@code shared/lib/expr.ts}, imported as {@code rule}). Both runtimes carry the
 * same meaning: numbers as decimals, dates as dates, and an empty value making a comparison
 * unknown — and a rule that comes out unknown holds.
 */
public final class ExprCode {

    private ExprCode() {
    }

    /** Java over {@code row}, a generated entity. */
    public static String java(Checked c, String row) {
        return new Writer(c, row, true).write(c.root());
    }

    /** TypeScript over {@code value}, the form's {@code Partial<Entity>}. */
    public static String ts(Checked c, String value) {
        return new Writer(c, value, false).write(c.root());
    }

    private record Writer(Checked c, String subject, boolean java) {

        String write(Node node) {
            if (node instanceof Lit lit) return literal(lit);
            if (node instanceof Ref ref) return ref(ref);
            if (node instanceof Unary u) {
                return call(u.op().equals("-") ? "neg" : "not", write(u.operand()));
            }
            if (node instanceof Binary b) return binary(b);
            return function((Call) node);
        }

        private String literal(Lit lit) {
            Object v = c.literal(lit);
            if (v instanceof BigDecimal n) {
                return java ? "new java.math.BigDecimal(\"" + n.toPlainString() + "\")" : n.toPlainString();
            }
            if (v instanceof Boolean b) return java ? (b ? "Boolean.TRUE" : "Boolean.FALSE") : b.toString();
            String s = (String) v;
            return java ? "\"" + javaEscape(s) + "\"" : "'" + tsEscape(s) + "'";
        }

        private String ref(Ref ref) {
            if (ref.isPath()) return path(ref);
            FieldDefinition f = c.fields().get(ref.name());
            Type t = c.typeOf(ref);
            if (java) {
                String get = subject + ".get" + Naming.toPascalCase(f.name()) + "()";
                return switch (t) {
                    case NUMBER -> "Expr.num(" + get + ")";
                    case ENUM -> "Expr.name(" + get + ")";
                    default -> get;
                };
            }
            String get = subject + "." + f.name();
            return switch (t) {
                case NUMBER -> "rule.num(" + get + ")";
                case TEXT, ENUM -> "rule.text(" + get + ")";
                case BOOL -> "rule.bool(" + get + ")";
                case DATE -> "rule.date(" + get + ")";
                case DATETIME, INSTANT -> "rule.moment(" + get + ")";
                case TIME -> "rule.time(" + get + ")";
                case ANY -> get;
            };
        }

        /**
         * A linked row's field. Java: the row the service loaded for the link (null-safe). TS: the
         * row the form fetched for the picked id ({@code rule.linked}) — unknown until it has.
         */
        private String path(Ref ref) {
            ExprCheck.Path p = c.paths().get(ref.name());
            Type t = c.typeOf(ref);
            String rel = Naming.toPascalCase(p.relation());
            if (java) {
                String link = subject + ".get" + rel + "()";
                String get = "(" + link + " == null ? null : " + link + ".get" + Naming.toPascalCase(p.field().name()) + "())";
                return switch (t) {
                    case NUMBER -> "Expr.num(" + get + ")";
                    case ENUM -> "Expr.name(" + get + ")";
                    default -> get;
                };
            }
            String get = "linked.value('" + linkedPath(p.target().name()) + "', " + subject + "."
                    + Naming.toCamelCase(p.relation()) + "Id, '" + p.field().name() + "')";
            return switch (t) {
                case NUMBER -> "rule.num(" + get + ")";
                case TEXT, ENUM -> "rule.text(" + get + ")";
                case BOOL -> "rule.bool(" + get + ")";
                case DATE -> "rule.date(" + get + ")";
                case DATETIME, INSTANT -> "rule.moment(" + get + ")";
                case TIME -> "rule.time(" + get + ")";
                case ANY -> get;
            };
        }

        private String binary(Binary b) {
            String l = write(b.left());
            String r = write(b.right());
            Type lt = c.typeOf(b.left());
            Type rt = c.typeOf(b.right());
            return switch (b.op()) {
                case "and" -> call("and", l, r);
                case "or" -> call("or", l, r);
                case "==" -> call("eq", l, r);
                case "!=" -> call("ne", l, r);
                case "<" -> call("lt", l, r);
                case "<=" -> call("le", l, r);
                case ">" -> call("gt", l, r);
                case ">=" -> call("ge", l, r);
                case "+" -> lt == Type.TEXT ? call("concat", l, r)
                        : lt == Type.DATE ? call("plusDays", l, r)
                        : rt == Type.DATE ? call("plusDays", r, l)
                        : call("add", l, r);
                case "-" -> lt == Type.DATE ? call("plusDays", l, call("neg", r)) : call("sub", l, r);
                case "*" -> call("mul", l, r);
                default -> call("div", l, r);
            };
        }

        private String function(Call fn) {
            String name = ExprCheck.FUNCTIONS.stream().filter(n -> n.equalsIgnoreCase(fn.fn())).findFirst().orElseThrow();
            List<Node> args = fn.args();
            return switch (name) {
                case "date" -> {
                    LocalDate d = (LocalDate) c.literal(fn);
                    yield java ? "java.time.LocalDate.parse(\"" + d + "\")" : "'" + d + "'";
                }
                case "round" -> call("round", write(args.get(0)),
                        java ? String.valueOf(((BigDecimal) ((Lit) args.get(1)).value()).intValue())
                             : ((BigDecimal) ((Lit) args.get(1)).value()).toPlainString());
                case "daysBetween" -> !java && c.typeOf(args.get(0)) == Type.DATE
                        ? call("daysBetweenDates", write(args.get(0)), write(args.get(1)))
                        : call("daysBetween", write(args.get(0)), write(args.get(1)));
                default -> call(name, args.stream().map(this::write).toArray(String[]::new));
            };
        }

        private String call(String fn, String... args) {
            return (java ? "Expr." : "rule.") + fn + "(" + String.join(", ", args) + ")";
        }
    }

    /** Where a linked row is read by id: {@code /api/cost-centers}. */
    public static String linkedPath(String entity) {
        return "/api/" + Naming.pluralize(Naming.toKebabCase(entity));
    }

    static String javaEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String tsEscape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }
}
