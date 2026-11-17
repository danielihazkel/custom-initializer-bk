package com.menora.initializr.fullstack.expr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The fullstack model's small expression language: one line over a row's own fields, written in
 * an entity's rules ({@code endsOn >= startsOn}, {@code status == 'REJECTED'}) and — in a later
 * phase — show-when conditions and computed fields. It is parsed here once, checked against the
 * entity's fields ({@link ExprCheck}), and written out as Java for the generated backend and as
 * TypeScript for the generated form ({@link ExprCode}), so both sides agree; {@link ExprEval}
 * runs it in the generator itself.
 *
 * <pre>
 * expr    := or
 * or      := and   (('or' | '||')  and)*
 * and     := not   (('and' | '&&') not)*
 * not     := ('not' | '!') not | compare
 * compare := sum   (('==' | '=' | '!=' | '<' | '<=' | '>' | '>=') sum)?
 * sum     := product (('+' | '-') product)*
 * product := unary (('*' | '/') unary)*
 * unary   := '-' unary | primary
 * primary := number | 'text' | "text" | true | false | name '(' args ')' | name | '(' expr ')'
 * </pre>
 */
public final class Expr {

    private Expr() {
    }

    /** At most this many characters in one expression. */
    public static final int MAX_LENGTH = 500;

    public enum Type { NUMBER, TEXT, BOOL, DATE, DATETIME, INSTANT, TIME, ENUM, ANY }

    public sealed interface Node permits Lit, Ref, Unary, Binary, Call {
        /** Where it starts in the text, from 0. */
        int at();
    }

    /** A literal: a {@link BigDecimal}, a String or a Boolean. */
    public record Lit(Object value, int at) implements Node {}

    /** A field of the row. */
    public record Ref(String name, int at) implements Node {}

    /** {@code -} or {@code not}. */
    public record Unary(String op, Node operand, int at) implements Node {}

    /** {@code + - * / == != < <= > >= and or}. */
    public record Binary(String op, Node left, Node right, int at) implements Node {}

    /** A function: {@code isEmpty today now daysBetween length abs round date}. */
    public record Call(String fn, List<Node> args, int at) implements Node {}

    /** A text that is not an expression, or one that does not fit the entity; says where. */
    public static class ExprException extends RuntimeException {
        private final int at;

        public ExprException(String message, int at) {
            super(message);
            this.at = at;
        }

        public int at() {
            return at;
        }

        /** "message (at character n)". */
        public String describe() {
            return getMessage() + (at >= 0 ? " (at character " + (at + 1) + ")" : "");
        }
    }

    public static Node parse(String text) {
        if (text == null || text.isBlank()) throw new ExprException("The expression is empty", -1);
        if (text.length() > MAX_LENGTH) throw new ExprException("The expression is over " + MAX_LENGTH + " characters", -1);
        Parser p = new Parser(lex(text));
        Node node = p.or();
        if (p.peek().kind != Kind.END) throw new ExprException("Unexpected '" + p.peek().text + "'", p.peek().at);
        return node;
    }

    // ── Lexing ─────────────────────────────────────────────────────────────────

    private enum Kind { NUMBER, TEXT, NAME, OP, END }

    private record Token(Kind kind, String text, Object value, int at) {}

    private static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int start = i;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                String n = s.substring(start, i);
                try {
                    out.add(new Token(Kind.NUMBER, n, new BigDecimal(n), start));
                } catch (NumberFormatException e) {
                    throw new ExprException("'" + n + "' is not a number", start);
                }
            } else if (c == '\'' || c == '"') {
                int start = i++;
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (i >= s.length()) throw new ExprException("A text is not closed with " + c, start);
                    char d = s.charAt(i++);
                    if (d == c) break;
                    if (d == '\\' && i < s.length()) d = s.charAt(i++);
                    sb.append(d);
                }
                out.add(new Token(Kind.TEXT, s.substring(start, i), sb.toString(), start));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                out.add(new Token(Kind.NAME, s.substring(start, i), null, start));
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (List.of("==", "!=", "<=", ">=", "&&", "||").contains(two)) {
                    out.add(new Token(Kind.OP, two, null, i));
                    i += 2;
                } else if ("+-*/()<>=!,".indexOf(c) >= 0) {
                    out.add(new Token(Kind.OP, String.valueOf(c), null, i));
                    i++;
                } else {
                    throw new ExprException("'" + c + "' is not part of the language", i);
                }
            }
        }
        out.add(new Token(Kind.END, "the end", null, s.length()));
        return out;
    }

    // ── Parsing ────────────────────────────────────────────────────────────────

    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        Token peek() {
            return tokens.get(pos);
        }

        private boolean isOp(String... ops) {
            Token t = peek();
            if (t.kind == Kind.OP) {
                for (String op : ops) if (t.text.equals(op)) return true;
            }
            if (t.kind == Kind.NAME) {
                String word = t.text.toLowerCase(Locale.ROOT);
                for (String op : ops) if (word.equals(op)) return true;
            }
            return false;
        }

        private Token next() {
            return tokens.get(pos++);
        }

        private void expect(String op) {
            if (!isOp(op)) throw new ExprException("Expected '" + op + "' but found '" + peek().text + "'", peek().at);
            pos++;
        }

        Node or() {
            Node left = and();
            while (isOp("or", "||")) {
                Token t = next();
                left = new Binary("or", left, and(), t.at);
            }
            return left;
        }

        Node and() {
            Node left = not();
            while (isOp("and", "&&")) {
                Token t = next();
                left = new Binary("and", left, not(), t.at);
            }
            return left;
        }

        Node not() {
            if (isOp("not", "!")) {
                Token t = next();
                return new Unary("not", not(), t.at);
            }
            return compare();
        }

        Node compare() {
            Node left = sum();
            if (isOp("==", "=", "!=", "<", "<=", ">", ">=")) {
                Token t = next();
                String op = t.text.equals("=") ? "==" : t.text;
                Node right = sum();
                if (isOp("==", "=", "!=", "<", "<=", ">", ">=")) {
                    throw new ExprException("Comparisons do not chain: join them with 'and'", peek().at);
                }
                return new Binary(op, left, right, t.at);
            }
            return left;
        }

        Node sum() {
            Node left = product();
            while (isOp("+", "-")) {
                Token t = next();
                left = new Binary(t.text, left, product(), t.at);
            }
            return left;
        }

        Node product() {
            Node left = unary();
            while (isOp("*", "/")) {
                Token t = next();
                left = new Binary(t.text, left, unary(), t.at);
            }
            return left;
        }

        Node unary() {
            if (isOp("-")) {
                Token t = next();
                return new Unary("-", unary(), t.at);
            }
            return primary();
        }

        Node primary() {
            Token t = next();
            switch (t.kind) {
                case NUMBER, TEXT -> {
                    return new Lit(t.value, t.at);
                }
                case NAME -> {
                    String word = t.text.toLowerCase(Locale.ROOT);
                    if (word.equals("true") || word.equals("false")) return new Lit(word.equals("true"), t.at);
                    if (List.of("and", "or", "not").contains(word)) {
                        throw new ExprException("Expected a value but found '" + t.text + "'", t.at);
                    }
                    if (isOp("(")) {
                        pos++;
                        List<Node> args = new ArrayList<>();
                        if (!isOp(")")) {
                            args.add(or());
                            while (isOp(",")) {
                                pos++;
                                args.add(or());
                            }
                        }
                        expect(")");
                        return new Call(t.text, args, t.at);
                    }
                    return new Ref(t.text, t.at);
                }
                case OP -> {
                    if (t.text.equals("(")) {
                        Node inner = or();
                        expect(")");
                        return inner;
                    }
                    throw new ExprException("Expected a value but found '" + t.text + "'", t.at);
                }
                default -> throw new ExprException("The expression ends too early", t.at);
            }
        }
    }
}
