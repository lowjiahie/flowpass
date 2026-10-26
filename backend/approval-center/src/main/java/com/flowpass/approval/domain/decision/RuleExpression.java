package com.flowpass.approval.domain.decision;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A small, safe condition language over the variables of an application — used for approval-matrix rules and for
 * cross-field business checks of a form.
 *
 * <pre>
 *   amount &gt; 1000 &amp;&amp; department in ['IT', 'HR']
 *   urgent || customer.tier == 'gold'
 *   !(region in ['EU']) &amp;&amp; reason contains 'laptop'
 *   endDate &gt; startDate
 *   total &gt; budget * 1.1
 * </pre>
 *
 * <p><b>Grammar</b> (lowest to highest precedence): {@code ||}/{@code or}, {@code &&}/{@code and},
 * {@code !}/{@code not}, one comparison ({@code == != &lt; &lt;= &gt; &gt;=}, {@code in [..]}, {@code not in [..]},
 * {@code contains}), {@code + -}, {@code * /}, unary minus, then literals (numbers, {@code 'strings'},
 * {@code true}, {@code false}, {@code null}, {@code [lists]}), variable paths ({@code a.b.c}, property access on
 * nested maps only) and parentheses.</p>
 *
 * <p><b>Safe by construction.</b> It is a closed interpreter: no reflection, no method or function calls, no
 * assignment, no I/O, no loops. Input is bounded (length, nesting depth, list size), so evaluation always
 * terminates quickly. The same expression and variables always give the same answer.</p>
 *
 * <p><b>Semantics.</b> Typing is strict — the string {@code "250"} is not the number {@code 250}. A missing
 * variable is {@code null}; comparing {@code null} (or mismatched types) with {@code < <= > >=} is simply
 * {@code false}, never an error, so a rule that cannot apply just does not match. {@code ==}/{@code !=} compare
 * numbers by value and everything else by equality. Strings order lexicographically, which orders ISO dates
 * ({@code 2026-12-31}) correctly. {@code &&}, {@code ||} and {@code !} treat anything that is not the boolean
 * {@code true} as false. Arithmetic on non-numbers (or dividing by zero) yields {@code null}.</p>
 */
public final class RuleExpression {

    public static final int MAX_LENGTH = 500;
    static final int MAX_DEPTH = 25;
    static final int MAX_LIST_SIZE = 50;

    private final String source;
    private final Node root;
    private final Set<String> variables;

    private RuleExpression(String source, Node root, Set<String> variables) {
        this.source = source;
        this.root = root;
        this.variables = Set.copyOf(variables);
    }

    /**
     * Parse an expression.
     *
     * @throws IllegalArgumentException with the reason and position when it is empty, too long, or not valid
     */
    public static RuleExpression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("the condition is empty");
        }
        if (source.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("the condition is longer than " + MAX_LENGTH + " characters");
        }
        Parser parser = new Parser(source);
        Node root = parser.parseAll();
        return new RuleExpression(source.trim(), root, parser.variables);
    }

    /** The expression as written (trimmed). */
    public String source() {
        return source;
    }

    /** The top-level variable names the expression reads. */
    public Set<String> variables() {
        return variables;
    }

    /** True when the expression evaluates to the boolean {@code true} for these variables. */
    public boolean matches(Map<String, Object> vars) {
        return Boolean.TRUE.equals(evaluate(vars));
    }

    /** The raw result: a {@link BigDecimal}, {@link String}, {@link Boolean}, {@link List} or {@code null}. */
    public Object evaluate(Map<String, Object> vars) {
        return root.eval(vars == null ? Map.of() : vars);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RuleExpression that && source.equals(that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source);
    }

    @Override
    public String toString() {
        return source;
    }

    // ------------------------------------------------------------------------------------------------ eval

    @FunctionalInterface
    private interface Node {
        Object eval(Map<String, Object> vars);
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return null;
    }

    private static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null && y != null) {
            return x.compareTo(y) == 0;
        }
        return a.equals(b);
    }

    /** Ordering of two comparable values, or null when they cannot be ordered (null / mixed types). */
    private static Integer order(Object a, Object b) {
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x != null && y != null) {
            return x.compareTo(y);
        }
        if (a instanceof String s && b instanceof String t) {
            return s.compareTo(t);
        }
        return null;
    }

    private static boolean in(Object value, Object collection) {
        if (!(collection instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (equal(value, item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(Object whole, Object part) {
        if (whole instanceof String s && part instanceof String t) {
            return s.contains(t);
        }
        return in(part, whole);
    }

    private static Object arithmetic(String op, Object a, Object b) {
        if (op.equals("+") && a instanceof String s && b instanceof String t) {
            return s + t;
        }
        BigDecimal x = number(a);
        BigDecimal y = number(b);
        if (x == null || y == null) {
            return null;
        }
        return switch (op) {
            case "+" -> x.add(y);
            case "-" -> x.subtract(y);
            case "*" -> x.multiply(y);
            case "/" -> y.signum() == 0 ? null : x.divide(y, MathContext.DECIMAL64);
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------ parse

    private enum Kind { NUMBER, STRING, IDENT, OP, LPAREN, RPAREN, LBRACKET, RBRACKET, COMMA, DOT, END }

    private record Token(Kind kind, String text, int position) {
        boolean is(Kind k, String t) {
            return kind == k && text.equals(t);
        }

        boolean keyword(String word) {
            return kind == Kind.IDENT && text.equals(word);
        }
    }

    private static final class Parser {
        private final String source;
        private final List<Token> tokens;
        private final Set<String> variables = new LinkedHashSet<>();
        private int index;
        private int depth;

        Parser(String source) {
            this.source = source;
            this.tokens = tokenize(source);
        }

        Node parseAll() {
            Node node = parseOr();
            if (peek().kind() != Kind.END) {
                throw error(peek(), "unexpected '" + peek().text() + "'"
                        + (isComparison(peek()) ? " (comparisons cannot be chained; combine them with &&)" : ""));
            }
            return node;
        }

        // ---- tokens

        private static List<Token> tokenize(String s) {
            List<Token> out = new ArrayList<>();
            int i = 0;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (Character.isDigit(c)) {
                    int start = i;
                    while (i < n && Character.isDigit(s.charAt(i))) {
                        i++;
                    }
                    if (i + 1 < n && s.charAt(i) == '.' && Character.isDigit(s.charAt(i + 1))) {
                        i++;
                        while (i < n && Character.isDigit(s.charAt(i))) {
                            i++;
                        }
                    }
                    out.add(new Token(Kind.NUMBER, s.substring(start, i), start));
                } else if (c == '\'' || c == '"') {
                    int end = s.indexOf(c, i + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException("unterminated string at position " + i);
                    }
                    String text = s.substring(i + 1, end);
                    if (text.indexOf('\\') >= 0) {
                        throw new IllegalArgumentException("escape sequences are not supported (position " + i + ")");
                    }
                    out.add(new Token(Kind.STRING, text, i));
                    i = end + 1;
                } else if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                        i++;
                    }
                    out.add(new Token(Kind.IDENT, s.substring(start, i), start));
                } else {
                    String two = i + 1 < n ? s.substring(i, i + 2) : "";
                    if (Set.of("==", "!=", "<=", ">=", "&&", "||").contains(two)) {
                        out.add(new Token(Kind.OP, two, i));
                        i += 2;
                    } else if ("<>!+-*/".indexOf(c) >= 0) {
                        out.add(new Token(Kind.OP, String.valueOf(c), i));
                        i++;
                    } else if (c == '(') {
                        out.add(new Token(Kind.LPAREN, "(", i++));
                    } else if (c == ')') {
                        out.add(new Token(Kind.RPAREN, ")", i++));
                    } else if (c == '[') {
                        out.add(new Token(Kind.LBRACKET, "[", i++));
                    } else if (c == ']') {
                        out.add(new Token(Kind.RBRACKET, "]", i++));
                    } else if (c == ',') {
                        out.add(new Token(Kind.COMMA, ",", i++));
                    } else if (c == '.') {
                        out.add(new Token(Kind.DOT, ".", i++));
                    } else {
                        throw new IllegalArgumentException("'" + c + "' is not allowed (position " + i + ")"
                                + (c == '=' ? "; use == to compare" : ""));
                    }
                }
            }
            out.add(new Token(Kind.END, "", n));
            return out;
        }

        private Token peek() {
            return tokens.get(index);
        }

        private Token peekAt(int offset) {
            return tokens.get(Math.min(index + offset, tokens.size() - 1));
        }

        private Token next() {
            return tokens.get(index++);
        }

        private IllegalArgumentException error(Token at, String message) {
            return new IllegalArgumentException(message + " (position " + at.position() + ")");
        }

        private static boolean isComparison(Token t) {
            return t.kind() == Kind.OP && Set.of("==", "!=", "<", "<=", ">", ">=").contains(t.text());
        }

        private void enter(Token at) {
            if (++depth > MAX_DEPTH) {
                throw error(at, "the condition is nested too deeply");
            }
        }

        // ---- grammar

        private Node parseOr() {
            Node left = parseAnd();
            while (peek().is(Kind.OP, "||") || peek().keyword("or")) {
                next();
                Node a = left;
                Node b = parseAnd();
                left = vars -> truthy(a.eval(vars)) || truthy(b.eval(vars));
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseNot();
            while (peek().is(Kind.OP, "&&") || peek().keyword("and")) {
                next();
                Node a = left;
                Node b = parseNot();
                left = vars -> truthy(a.eval(vars)) && truthy(b.eval(vars));
            }
            return left;
        }

        private Node parseNot() {
            if (peek().is(Kind.OP, "!") || (peek().keyword("not") && !peekAt(1).keyword("in"))) {
                Token at = next();
                enter(at);
                Node operand = parseNot();
                depth--;
                return vars -> !truthy(operand.eval(vars));
            }
            return parseComparison();
        }

        private Node parseComparison() {
            Node left = parseAdditive();
            Token t = peek();
            if (isComparison(t)) {
                next();
                Node right = parseAdditive();
                String op = t.text();
                return vars -> {
                    Object a = left.eval(vars);
                    Object b = right.eval(vars);
                    if (op.equals("==")) {
                        return equal(a, b);
                    }
                    if (op.equals("!=")) {
                        return !equal(a, b);
                    }
                    Integer c = order(a, b);
                    if (c == null) {
                        return false;
                    }
                    return switch (op) {
                        case "<" -> c < 0;
                        case "<=" -> c <= 0;
                        case ">" -> c > 0;
                        default -> c >= 0;
                    };
                };
            }
            if (t.keyword("in")) {
                next();
                Node right = parseAdditive();
                return vars -> in(left.eval(vars), right.eval(vars));
            }
            if (t.keyword("not") && peekAt(1).keyword("in")) {
                next();
                next();
                Node right = parseAdditive();
                return vars -> !in(left.eval(vars), right.eval(vars));
            }
            if (t.keyword("contains")) {
                next();
                Node right = parseAdditive();
                return vars -> contains(left.eval(vars), right.eval(vars));
            }
            return left;
        }

        private Node parseAdditive() {
            Node left = parseTerm();
            while (peek().is(Kind.OP, "+") || peek().is(Kind.OP, "-")) {
                String op = next().text();
                Node a = left;
                Node b = parseTerm();
                left = vars -> arithmetic(op, a.eval(vars), b.eval(vars));
            }
            return left;
        }

        private Node parseTerm() {
            Node left = parseUnary();
            while (peek().is(Kind.OP, "*") || peek().is(Kind.OP, "/")) {
                String op = next().text();
                Node a = left;
                Node b = parseUnary();
                left = vars -> arithmetic(op, a.eval(vars), b.eval(vars));
            }
            return left;
        }

        private Node parseUnary() {
            if (peek().is(Kind.OP, "-")) {
                Token at = next();
                enter(at);
                Node operand = parseUnary();
                depth--;
                return vars -> {
                    BigDecimal value = number(operand.eval(vars));
                    return value == null ? null : value.negate();
                };
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            Token t = next();
            switch (t.kind()) {
                case NUMBER -> {
                    BigDecimal value = new BigDecimal(t.text());
                    return vars -> value;
                }
                case STRING -> {
                    String value = t.text();
                    return vars -> value;
                }
                case LPAREN -> {
                    enter(t);
                    Node inner = parseOr();
                    depth--;
                    expect(Kind.RPAREN, "')'");
                    return inner;
                }
                case LBRACKET -> {
                    return parseList(t);
                }
                case IDENT -> {
                    return switch (t.text()) {
                        case "true" -> vars -> Boolean.TRUE;
                        case "false" -> vars -> Boolean.FALSE;
                        case "null" -> vars -> null;
                        case "and", "or", "not", "in", "contains" ->
                                throw error(t, "unexpected keyword '" + t.text() + "'");
                        default -> parsePath(t);
                    };
                }
                default -> throw error(t, t.kind() == Kind.END ? "the condition ends unexpectedly"
                        : "unexpected '" + t.text() + "'");
            }
        }

        private Node parsePath(Token first) {
            List<String> path = new ArrayList<>();
            path.add(first.text());
            variables.add(first.text());
            while (peek().kind() == Kind.DOT) {
                next();
                Token part = next();
                if (part.kind() != Kind.IDENT) {
                    throw error(part, "expected a field name after '.'");
                }
                path.add(part.text());
            }
            if (peek().kind() == Kind.LPAREN) {
                throw error(peek(), "functions and methods cannot be called");
            }
            return vars -> {
                Object current = vars;
                for (String segment : path) {
                    if (!(current instanceof Map<?, ?> map)) {
                        return null;
                    }
                    current = map.get(segment);
                }
                return current instanceof Number || current instanceof Boolean || current instanceof String
                        || current instanceof List || current == null ? normalize(current) : null;
            };
        }

        private static Object normalize(Object value) {
            if (value instanceof List<?> list) {
                List<Object> out = new ArrayList<>(list.size());
                for (Object item : list) {
                    out.add(normalize(item));
                }
                return out;
            }
            BigDecimal number = number(value);
            return number != null ? number : value;
        }

        private Node parseList(Token open) {
            enter(open);
            List<Node> items = new ArrayList<>();
            if (peek().kind() != Kind.RBRACKET) {
                do {
                    if (items.size() >= MAX_LIST_SIZE) {
                        throw error(peek(), "a list can hold at most " + MAX_LIST_SIZE + " items");
                    }
                    items.add(parseAdditive());
                } while (peek().kind() == Kind.COMMA && next() != null);
            }
            depth--;
            expect(Kind.RBRACKET, "']'");
            return vars -> {
                List<Object> out = new ArrayList<>(items.size());
                for (Node item : items) {
                    out.add(item.eval(vars));
                }
                return out;
            };
        }

        private void expect(Kind kind, String what) {
            Token t = next();
            if (t.kind() != kind) {
                throw error(t, "expected " + what + (t.kind() == Kind.END ? " but the condition ends" : " but found '" + t.text() + "'"));
            }
        }
    }
}
