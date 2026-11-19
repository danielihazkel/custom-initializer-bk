package com.menora.initializr.fullstack.expr;

import com.menora.initializr.fullstack.EntityDefinition;
import com.menora.initializr.fullstack.FieldDefinition;
import com.menora.initializr.fullstack.FieldType;
import com.menora.initializr.fullstack.expr.Expr.Binary;
import com.menora.initializr.fullstack.expr.Expr.Call;
import com.menora.initializr.fullstack.expr.Expr.ExprException;
import com.menora.initializr.fullstack.expr.Expr.Lit;
import com.menora.initializr.fullstack.expr.Expr.Node;
import com.menora.initializr.fullstack.expr.Expr.Ref;
import com.menora.initializr.fullstack.expr.Expr.Type;
import com.menora.initializr.fullstack.expr.Expr.Unary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks an expression against an entity's fields: every name is one of them, every operator
 * gets what it takes, an enum is compared with one of its own values. What it learns — each
 * node's type, the enum literals spelled as the constants are, the fields read — is what the
 * code writers ({@link ExprCode}) and the interpreter ({@link ExprEval}) need.
 */
public final class ExprCheck {

    private ExprCheck() {
    }

    /** The functions, as they are spelled. */
    public static final List<String> FUNCTIONS =
            List.of("isEmpty", "today", "now", "daysBetween", "length", "abs", "round", "date",
                    "sum", "count", "min", "max", "avg");

    /** The functions over the rows saved with a record (a composition): {@code sum(lines.total)}, {@code count(lines)}. */
    public static final List<String> AGGREGATES = List.of("sum", "count", "min", "max", "avg");

    /**
     * A checked expression.
     *
     * @param refs  the row's own fields it reads, in the order it first reads them
     * @param paths the fields of linked rows it reads ({@code costCenter.budget}), by how it spells them
     * @param rows  what each function over the record's rows reads ({@code sum(lines.total)}), by its call
     * @param lists the lists of rows it reads, in the order it first reads them
     */
    public record Checked(String text, Node root, Type type, Map<Node, Type> types, Map<Node, Object> literals,
                          Set<String> refs, Map<String, FieldDefinition> fields, Map<String, Path> paths,
                          Map<Node, Rows> rows, Set<String> lists) {

        public Type typeOf(Node node) {
            return types.get(node);
        }

        /** A literal's value as the code uses it: an enum literal spelled as its constant, a date literal a LocalDate. */
        public Object literal(Node node) {
            return literals.get(node);
        }
    }

    /**
     * A field of a linked row: the MANY_TO_ONE ({@code relation}, as the entity names it), what it
     * leads to, and the field read there.
     */
    public record Path(String relation, EntityDefinition.LinkedEntity target, FieldDefinition field) {}

    /**
     * A function over the rows saved with the record: which one ({@code fn}, as {@link #AGGREGATES}
     * spells it), the list, what its rows are, and the field read in each ({@code null} for count).
     */
    public record Rows(String fn, String list, EntityDefinition.LinkedEntity child, FieldDefinition field) {}

    /** Parses and checks {@code text} over {@code fields}; it must come out as {@code expected} (null: anything). */
    public static Checked check(String text, List<FieldDefinition> fields, Type expected) {
        return check(text, fields, Map.of(), expected);
    }

    /** {@link #check} over an entity: its fields and the rows its links point at. */
    public static Checked check(String text, EntityDefinition entity, Type expected) {
        return check(text, entity.fields(), entity.links(), expected);
    }

    /** {@link #check} over {@code fields} and, through {@code links} (by relation name), the fields of linked rows. */
    public static Checked check(String text, List<FieldDefinition> fields, Map<String, EntityDefinition.LinkedEntity> links,
                                Type expected) {
        Node root = Expr.parse(text);
        Map<String, FieldDefinition> byName = new LinkedHashMap<>();
        for (FieldDefinition f : fields) byName.put(f.name(), f);
        Checker c = new Checker(byName, links == null ? Map.of() : links);
        Type type = c.type(root, false);
        if (expected != null && type != expected) {
            throw new ExprException("This must be " + describe(expected) + ", but it is " + describe(type), root.at());
        }
        return new Checked(text, root, type, c.types, c.literals, c.refs, byName, c.paths, c.rows, c.lists);
    }

    /** The expression type of a field, or null for one an expression cannot read (only test with isEmpty). */
    public static Type typeOf(FieldDefinition f) {
        return switch (f.type()) {
            case INTEGER, LONG, BIG_DECIMAL, MONEY -> Type.NUMBER;
            case STRING, TEXT, MARKDOWN -> Type.TEXT;
            case BOOLEAN -> Type.BOOL;
            case LOCAL_DATE -> Type.DATE;
            case LOCAL_DATE_TIME -> Type.DATETIME;
            case INSTANT -> Type.INSTANT;
            case LOCAL_TIME -> Type.TIME;
            case ENUM -> Type.ENUM;
            case UUID, FILE, IMAGE -> null;
        };
    }

    public static String describe(Type type) {
        return switch (type) {
            case NUMBER -> "a number";
            case TEXT -> "a text";
            case BOOL -> "a yes/no condition";
            case DATE -> "a date";
            case DATETIME -> "a date and time";
            case INSTANT -> "an instant";
            case TIME -> "a time of day";
            case ENUM -> "an enum value";
            case ANY -> "a value";
        };
    }

    private static final class Checker {
        private final Map<String, FieldDefinition> fields;
        private final Map<String, EntityDefinition.LinkedEntity> links;
        final Map<Node, Type> types = new IdentityHashMap<>();
        final Map<Node, Object> literals = new IdentityHashMap<>();
        final Set<String> refs = new LinkedHashSet<>();
        final Map<String, Path> paths = new LinkedHashMap<>();
        final Map<Node, Rows> rows = new IdentityHashMap<>();
        final Set<String> lists = new LinkedHashSet<>();

        Checker(Map<String, FieldDefinition> fields, Map<String, EntityDefinition.LinkedEntity> links) {
            this.fields = fields;
            this.links = links;
        }

        /** The field a name reads: the row's own, or a linked row's. */
        FieldDefinition fieldOf(Ref ref) {
            return ref.isPath() ? paths.get(ref.name()).field() : fields.get(ref.name());
        }

        private Type path(Ref ref) {
            int dot = ref.name().indexOf('.');
            String relation = ref.name().substring(0, dot);
            String name = ref.name().substring(dot + 1);
            EntityDefinition.LinkedEntity target = links.get(relation);
            if (target != null && target.rows()) {
                throw new ExprException("'" + ref.name() + "' is one value in each of the rows: read them with sum, min, max or avg (sum("
                        + ref.name() + "))", ref.at());
            }
            if (target == null) {
                String loose = links.keySet().stream().filter(k -> k.equalsIgnoreCase(relation)).findFirst().orElse(null);
                throw new ExprException("'" + relation + "' is not a link of this entity (a MANY_TO_ONE)"
                        + (loose != null ? " (did you mean '" + loose + "'?)"
                        : links.isEmpty() ? "" : " — its links are " + String.join(", ", links.keySet())), ref.at());
            }
            FieldDefinition f = target.fields().stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
            if (f == null) {
                FieldDefinition loose = target.fields().stream().filter(x -> x.name().equalsIgnoreCase(name)).findFirst().orElse(null);
                throw new ExprException("'" + name + "' is not a field of " + target.name()
                        + (loose != null ? " (did you mean '" + loose.name() + "'?)" : ""), ref.at());
            }
            Type t = typeOf(f);
            if (t == null || f.type() == FieldType.TEXT || f.type() == FieldType.MARKDOWN) {
                throw new ExprException("'" + ref.name() + "' (" + f.type() + ") cannot be read through a link: only a short value can",
                        ref.at());
            }
            paths.put(ref.name(), new Path(relation, target, f));
            return t;
        }

        Type type(Node node, boolean emptinessOnly) {
            Type t = compute(node, emptinessOnly);
            types.put(node, t);
            return t;
        }

        private Type compute(Node node, boolean emptinessOnly) {
            if (node instanceof Lit lit) {
                literals.put(lit, lit.value());
                if (lit.value() instanceof BigDecimal) return Type.NUMBER;
                if (lit.value() instanceof Boolean) return Type.BOOL;
                return Type.TEXT;
            }
            if (node instanceof Ref ref) {
                if (ref.isPath()) return path(ref);
                FieldDefinition f = fields.get(ref.name());
                if (f == null && links.get(ref.name()) != null && links.get(ref.name()).rows()) {
                    throw new ExprException("'" + ref.name() + "' is the rows saved with this entity: count(" + ref.name()
                            + "), or sum, min, max or avg over one of their fields", ref.at());
                }
                if (f == null) {
                    FieldDefinition loose = fields.values().stream()
                            .filter(x -> x.name().equalsIgnoreCase(ref.name())).findFirst().orElse(null);
                    throw new ExprException("'" + ref.name() + "' is not a field of this entity"
                            + (loose != null ? " (did you mean '" + loose.name() + "'?)" : ""), ref.at());
                }
                refs.add(f.name());
                Type t = typeOf(f);
                if (t == null) {
                    if (emptinessOnly) return Type.ANY;
                    throw new ExprException("'" + f.name() + "' (" + f.type() + ") can only be tested with isEmpty()", ref.at());
                }
                return t;
            }
            if (node instanceof Unary u) {
                Type t = type(u.operand(), false);
                if (u.op().equals("-")) {
                    require(t, Type.NUMBER, u.operand(), "'-'");
                    return Type.NUMBER;
                }
                require(t, Type.BOOL, u.operand(), "'not'");
                return Type.BOOL;
            }
            if (node instanceof Binary b) return binary(b);
            return call((Call) node);
        }

        private Type binary(Binary b) {
            Type l = type(b.left(), false);
            Type r = type(b.right(), false);
            switch (b.op()) {
                case "and", "or" -> {
                    require(l, Type.BOOL, b.left(), "'" + b.op() + "'");
                    require(r, Type.BOOL, b.right(), "'" + b.op() + "'");
                    return Type.BOOL;
                }
                case "==", "!=" -> {
                    comparable(b, l, r, false);
                    return Type.BOOL;
                }
                case "<", "<=", ">", ">=" -> {
                    comparable(b, l, r, true);
                    return Type.BOOL;
                }
                case "+" -> {
                    if (l == Type.NUMBER && r == Type.NUMBER) return Type.NUMBER;
                    if (l == Type.TEXT && r == Type.TEXT) return Type.TEXT;
                    if (l == Type.DATE && r == Type.NUMBER || l == Type.NUMBER && r == Type.DATE) return Type.DATE;
                    throw new ExprException("'+' adds two numbers, joins two texts, or adds days to a date — not "
                            + describe(l) + " and " + describe(r), b.at());
                }
                case "-" -> {
                    if (l == Type.NUMBER && r == Type.NUMBER) return Type.NUMBER;
                    if (l == Type.DATE && r == Type.NUMBER) return Type.DATE;
                    throw new ExprException("'-' takes two numbers, or days from a date (daysBetween(a, b) is the days between two dates) — not "
                            + describe(l) + " and " + describe(r), b.at());
                }
                default -> {
                    require(l, Type.NUMBER, b.left(), "'" + b.op() + "'");
                    require(r, Type.NUMBER, b.right(), "'" + b.op() + "'");
                    return Type.NUMBER;
                }
            }
        }

        private void comparable(Binary b, Type l, Type r, boolean ordered) {
            // An enum against a text: the text must be one of its values, spelled as the constant is.
            if (l == Type.ENUM && r == Type.TEXT) {
                enumLiteral(b.left(), b.right());
                if (ordered) throw new ExprException("Enum values are not ordered: compare with == or !=", b.at());
                return;
            }
            if (l == Type.TEXT && r == Type.ENUM) {
                enumLiteral(b.right(), b.left());
                if (ordered) throw new ExprException("Enum values are not ordered: compare with == or !=", b.at());
                return;
            }
            if (l != r) {
                throw new ExprException("Cannot compare " + describe(l) + " with " + describe(r), b.at());
            }
            if (ordered && (l == Type.BOOL || l == Type.ENUM)) {
                throw new ExprException(describe(l) + " is not ordered: compare with == or !=", b.at());
            }
        }

        private void enumLiteral(Node enumSide, Node textSide) {
            if (!(textSide instanceof Lit lit) || !(enumSide instanceof Ref ref)) return;
            FieldDefinition f = fieldOf(ref);
            String text = (String) lit.value();
            String constant = f.enumValues().stream().filter(v -> v.equalsIgnoreCase(text)).findFirst().orElse(null);
            if (constant == null) {
                throw new ExprException("'" + text + "' is not one of " + f.name() + "'s values ("
                        + String.join(", ", f.enumValues()) + ")", lit.at());
            }
            literals.put(lit, constant);
        }

        private Type call(Call c) {
            String fn = FUNCTIONS.stream().filter(n -> n.equalsIgnoreCase(c.fn())).findFirst().orElseThrow(() ->
                    new ExprException("'" + c.fn() + "' is not a function (there are " + String.join(", ", FUNCTIONS) + ")", c.at()));
            List<Node> args = c.args();
            switch (fn) {
                case "isEmpty" -> {
                    arity(c, fn, 1);
                    type(args.get(0), true);
                    return Type.BOOL;
                }
                case "today", "now" -> {
                    arity(c, fn, 0);
                    return fn.equals("today") ? Type.DATE : Type.DATETIME;
                }
                case "daysBetween" -> {
                    arity(c, fn, 2);
                    Type a = type(args.get(0), false);
                    Type b = type(args.get(1), false);
                    if (a != b || !(a == Type.DATE || a == Type.DATETIME || a == Type.INSTANT)) {
                        throw new ExprException("daysBetween takes two dates, or two dates and times — not "
                                + describe(a) + " and " + describe(b), c.at());
                    }
                    return Type.NUMBER;
                }
                case "length" -> {
                    arity(c, fn, 1);
                    Type a = type(args.get(0), false);
                    if (a != Type.TEXT && a != Type.ENUM) require(a, Type.TEXT, args.get(0), "length");
                    return Type.NUMBER;
                }
                case "abs" -> {
                    arity(c, fn, 1);
                    require(type(args.get(0), false), Type.NUMBER, args.get(0), "abs");
                    return Type.NUMBER;
                }
                case "round" -> {
                    arity(c, fn, 2);
                    require(type(args.get(0), false), Type.NUMBER, args.get(0), "round");
                    type(args.get(1), false);
                    if (!(args.get(1) instanceof Lit lit) || !(lit.value() instanceof BigDecimal n)
                            || n.scale() > 0 || n.signum() < 0 || n.intValue() > 10) {
                        throw new ExprException("round's second argument is a whole number of decimal places, 0 to 10",
                                args.get(1).at());
                    }
                    return Type.NUMBER;
                }
                case "count" -> {
                    arity(c, fn, 1);
                    if (!(args.get(0) instanceof Ref ref) || ref.isPath() || rowsOf(ref.name()) == null) {
                        throw new ExprException("count takes the rows saved with this entity" + listsHint(), c.at());
                    }
                    rows.put(c, new Rows(fn, ref.name(), rowsOf(ref.name()), null));
                    lists.add(ref.name());
                    return Type.NUMBER;
                }
                case "sum", "min", "max", "avg" -> {
                    arity(c, fn, 1);
                    if (!(args.get(0) instanceof Ref ref) || !ref.isPath()
                            || rowsOf(ref.name().substring(0, ref.name().indexOf('.'))) == null) {
                        throw new ExprException(fn + " takes a field of the rows saved with this entity, like " + fn
                                + "(lines.total)" + listsHint(), c.at());
                    }
                    String list = ref.name().substring(0, ref.name().indexOf('.'));
                    String name = ref.name().substring(list.length() + 1);
                    EntityDefinition.LinkedEntity child = rowsOf(list);
                    FieldDefinition f = child.fields().stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
                    if (f == null) {
                        FieldDefinition loose = child.fields().stream().filter(x -> x.name().equalsIgnoreCase(name)).findFirst().orElse(null);
                        throw new ExprException("'" + name + "' is not a field of " + child.name()
                                + (loose != null ? " (did you mean '" + loose.name() + "'?)" : ""), ref.at());
                    }
                    if (typeOf(f) != Type.NUMBER) {
                        throw new ExprException(fn + " takes a number field of the rows; '" + ref.name() + "' is "
                                + describe(typeOf(f) == null ? Type.ANY : typeOf(f)), ref.at());
                    }
                    rows.put(c, new Rows(fn, list, child, f));
                    lists.add(list);
                    return Type.NUMBER;
                }
                default -> {
                    arity(c, fn, 1);
                    type(args.get(0), false);
                    if (!(args.get(0) instanceof Lit lit) || !(lit.value() instanceof String s)) {
                        throw new ExprException("date takes a date written out: date('2025-01-31')", c.at());
                    }
                    try {
                        literals.put(c, LocalDate.parse(s));
                    } catch (DateTimeParseException e) {
                        throw new ExprException("'" + s + "' is not a date (year-month-day, like 2025-01-31)", args.get(0).at());
                    }
                    return Type.DATE;
                }
            }
        }

        /** The rows a name stands for, or null when it is not a list saved with this entity. */
        private EntityDefinition.LinkedEntity rowsOf(String list) {
            EntityDefinition.LinkedEntity linked = links.get(list);
            return linked != null && linked.rows() ? linked : null;
        }

        private String listsHint() {
            List<String> lists = links.entrySet().stream().filter(e -> e.getValue().rows()).map(Map.Entry::getKey).toList();
            return lists.isEmpty() ? " — this entity has none (a ONE_TO_MANY with composition)"
                    : " — here: " + String.join(", ", lists);
        }

        private static void arity(Call c, String fn, int n) {
            if (c.args().size() != n) {
                throw new ExprException(fn + " takes " + (n == 0 ? "no arguments" : n == 1 ? "one argument" : n + " arguments"),
                        c.at());
            }
        }

        private static void require(Type actual, Type wanted, Node where, String what) {
            if (actual != wanted) {
                throw new ExprException(what + " takes " + describe(wanted) + ", not " + describe(actual), where.at());
            }
        }
    }
}
