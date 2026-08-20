package deal.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiParser {
    private enum Kind {
        IDENTIFIER,
        STRING,
        TRUE,
        FALSE,
        EXPORT,
        VIEW,
        ACTION,
        LBRACE,
        RBRACE,
        LPAREN,
        RPAREN,
        COLON,
        COMMA,
        DOT,
        BANG,
        EOF
    }

    private record Token(Kind kind, String text, int startOffset, int endOffset,
                         int line, int column, int endLine, int endColumn) {}

    private final Path file;
    private final String source;
    private final List<Token> tokens;
    private int position;

    private UiParser(Path file, String source) {
        this.file = file;
        this.source = source;
        Token rootDirective = findRootDirective(file, source);
        this.tokens = tokenize(file, source, rootDirective.endOffset());
    }

    public static UiModel.ParsedSource parse(Path file, String source) {
        return new UiParser(file, source).parseSource();
    }

    private UiModel.ParsedSource parseSource() {
        Token rootDirective = findRootDirective(file, source);
        int declarationStart = rootDirective.endOffset();
        while (declarationStart < source.length() && Character.isWhitespace(source.charAt(declarationStart))) {
            declarationStart++;
        }
        while (position < tokens.size() && peek().startOffset() < declarationStart) {
            position++;
        }
        Token first = peek();
        expect(Kind.EXPORT, "Expected 'export view' after // @ui-root");
        expect(Kind.VIEW, "Expected 'view' after 'export'");
        Token name = expect(Kind.IDENTIFIER, "Expected view name");
        expect(Kind.LPAREN, "Expected '(' after view name");
        Token stateParameter = expect(Kind.IDENTIFIER, "Expected root state parameter name");
        expect(Kind.COLON, "Expected ':' after root state parameter");
        Token stateType = expect(Kind.IDENTIFIER, "Expected root state type");
        expect(Kind.RPAREN, "Expected ')' after root state parameter");
        expect(Kind.COLON, "Expected ':' before view return type");
        Token returnType = expect(Kind.IDENTIFIER, "Expected View return type");
        if (!returnType.text().equals("View")) {
            throw error("UI1002", "Root view return type must be View", returnType);
        }
        List<UiModel.Node> children = parseBody();
        Token end = previous();
        if (peek().kind() != Kind.EOF) {
            throw error("UI1003", "Only one root view is allowed and no declarations may follow it", peek());
        }
        UiModel.View view = new UiModel.View(name.text(), stateParameter.text(), stateType.text(), children,
            span(first, end));
        String dealSource = source.substring(0, rootDirective.startOffset())
            + whitespacePreservingLines(source.substring(rootDirective.startOffset()));
        return new UiModel.ParsedSource(source, dealSource, view);
    }

    private List<UiModel.Node> parseBody() {
        expect(Kind.LBRACE, "Expected '{'");
        List<UiModel.Node> nodes = new ArrayList<>();
        while (peek().kind() != Kind.RBRACE) {
            if (peek().kind() == Kind.EOF) {
                throw error("UI1004", "Unterminated UI body", peek());
            }
            nodes.add(parseNode());
        }
        expect(Kind.RBRACE, "Expected '}'");
        return List.copyOf(nodes);
    }

    private UiModel.Node parseNode() {
        Token name = expect(Kind.IDENTIFIER, "Expected component or When");
        if (name.text().equals("When")) {
            expect(Kind.LPAREN, "Expected '(' after When");
            UiModel.Expression condition = parseExpression();
            expect(Kind.RPAREN, "Expected ')' after When condition");
            List<UiModel.Node> children = parseBody();
            return new UiModel.When(condition, children, span(name, previous()));
        }
        Map<String, UiModel.Expression> props = new LinkedHashMap<>();
        if (match(Kind.LPAREN)) {
            if (peek().kind() != Kind.RPAREN) {
                do {
                    Token prop = expect(Kind.IDENTIFIER, "Expected named property");
                    expect(Kind.COLON, "Expected ':' after property name");
                    if (props.containsKey(prop.text())) {
                        throw error("UI1005", "Duplicate property '" + prop.text() + "'", prop);
                    }
                    props.put(prop.text(), parseExpression());
                } while (match(Kind.COMMA));
            }
            expect(Kind.RPAREN, "Expected ')' after component properties");
        }
        List<UiModel.Node> children = List.of();
        if (peek().kind() == Kind.LBRACE) {
            children = parseBody();
        }
        return new UiModel.Component(name.text(), props, children, span(name, previous()));
    }

    private UiModel.Expression parseExpression() {
        if (match(Kind.BANG)) {
            Token start = previous();
            UiModel.Expression operand = parseExpression();
            return new UiModel.NotExpression(operand, span(start, tokenFor(operand.span())));
        }
        if (match(Kind.STRING)) {
            Token token = previous();
            return new UiModel.StringLiteral(token.text(), span(token, token));
        }
        if (match(Kind.TRUE) || match(Kind.FALSE)) {
            Token token = previous();
            return new UiModel.BooleanLiteral(token.kind() == Kind.TRUE, span(token, token));
        }
        if (match(Kind.ACTION)) {
            Token start = previous();
            Token type = expect(Kind.IDENTIFIER, "Expected action class name");
            expect(Kind.LBRACE, "Expected '{' after action class name");
            Map<String, UiModel.Expression> fields = new LinkedHashMap<>();
            while (peek().kind() != Kind.RBRACE) {
                Token field = expect(Kind.IDENTIFIER, "Expected action field name");
                expect(Kind.COLON, "Expected ':' after action field name");
                if (fields.containsKey(field.text())) {
                    throw error("UI1006", "Duplicate action field '" + field.text() + "'", field);
                }
                fields.put(field.text(), parseExpression());
                match(Kind.COMMA);
            }
            Token end = expect(Kind.RBRACE, "Expected '}' after action fields");
            return new UiModel.ActionLiteral(type.text(), fields, span(start, end));
        }
        Token root = expect(Kind.IDENTIFIER, "Expected UI expression");
        expect(Kind.DOT, "State references must be direct paths such as state.title");
        Token field = expect(Kind.IDENTIFIER, "Expected state field name after '.'");
        return new UiModel.StatePath(root.text(), field.text(), span(root, field));
    }

    private Token tokenFor(UiModel.SourceSpan span) {
        for (Token token : tokens) {
            if (token.endLine() == span.endLine() && token.endColumn() == span.endColumn()) {
                return token;
            }
        }
        return previous();
    }

    private Token expect(Kind kind, String message) {
        if (peek().kind() != kind) {
            throw error("UI1001", message, peek());
        }
        return tokens.get(position++);
    }

    private boolean match(Kind kind) {
        if (peek().kind() != kind) {
            return false;
        }
        position++;
        return true;
    }

    private Token peek() {
        return tokens.get(Math.min(position, tokens.size() - 1));
    }

    private Token previous() {
        return tokens.get(Math.max(0, position - 1));
    }

    private UiDiagnostic error(String code, String message, Token token) {
        return new UiDiagnostic(code, message, span(token, token));
    }

    private UiModel.SourceSpan span(Token start, Token end) {
        return new UiModel.SourceSpan(file, start.line(), start.column(), end.endLine(), end.endColumn());
    }

    private static Token findRootDirective(Path file, String source) {
        int foundStart = -1;
        int foundEnd = -1;
        int offset = 0;
        int line = 1;
        int foundLine = 1;
        while (offset <= source.length()) {
            int end = source.indexOf('\n', offset);
            if (end < 0) {
                end = source.length();
            }
            String value = source.substring(offset, end).trim();
            if (value.equals("// @ui-root")) {
                if (foundStart >= 0) {
                    throw new UiDiagnostic("UI1007", "Exactly one // @ui-root directive is required",
                        file, line, 1);
                }
                foundStart = offset;
                foundEnd = end < source.length() ? end + 1 : end;
                foundLine = line;
            }
            if (end == source.length()) {
                break;
            }
            offset = end + 1;
            line++;
        }
        if (foundStart < 0) {
            throw new UiDiagnostic("UI1007", "Exactly one // @ui-root directive is required", file, 1, 1);
        }
        return new Token(Kind.IDENTIFIER, "@ui-root", foundStart, foundEnd, foundLine, 1, foundLine, 12);
    }

    private static String whitespacePreservingLines(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            result.append(c == '\n' || c == '\r' ? c : ' ');
        }
        return result.toString();
    }

    private static List<Token> tokenize(Path file, String source, int startOffset) {
        List<Token> result = new ArrayList<>();
        int offset = startOffset;
        int line = 1;
        int column = 1;
        for (int i = 0; i < startOffset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        while (offset < source.length()) {
            char c = source.charAt(offset);
            if (Character.isWhitespace(c)) {
                if (c == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
                offset++;
                continue;
            }
            if (c == '/' && offset + 1 < source.length() && source.charAt(offset + 1) == '/') {
                while (offset < source.length() && source.charAt(offset) != '\n') {
                    offset++;
                    column++;
                }
                continue;
            }
            int start = offset;
            int startLine = line;
            int startColumn = column;
            Kind kind;
            String text;
            if (Character.isLetter(c) || c == '_') {
                offset++;
                column++;
                while (offset < source.length()) {
                    char next = source.charAt(offset);
                    if (!Character.isLetterOrDigit(next) && next != '_') {
                        break;
                    }
                    offset++;
                    column++;
                }
                text = source.substring(start, offset);
                kind = switch (text) {
                    case "export" -> Kind.EXPORT;
                    case "view" -> Kind.VIEW;
                    case "action" -> Kind.ACTION;
                    case "true" -> Kind.TRUE;
                    case "false" -> Kind.FALSE;
                    default -> Kind.IDENTIFIER;
                };
            } else if (c == '"' || c == '\'') {
                char quote = c;
                offset++;
                column++;
                StringBuilder decoded = new StringBuilder();
                boolean closed = false;
                while (offset < source.length()) {
                    char next = source.charAt(offset++);
                    column++;
                    if (next == quote) {
                        closed = true;
                        break;
                    }
                    if (next == '\n' || next == '\r') {
                        throw new UiDiagnostic("UI1008", "String literal may not contain a raw line break",
                            file, startLine, startColumn);
                    }
                    if (next == '\\') {
                        if (offset >= source.length()) {
                            break;
                        }
                        char escape = source.charAt(offset++);
                        column++;
                        decoded.append(switch (escape) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case '\\' -> '\\';
                            case '"' -> '"';
                            case '\'' -> '\'';
                            default -> throw new UiDiagnostic("UI1008", "Unsupported string escape '\\" + escape + "'",
                                file, line, column - 2);
                        });
                    } else {
                        decoded.append(next);
                    }
                }
                if (!closed) {
                    throw new UiDiagnostic("UI1008", "Unterminated string literal", file, startLine, startColumn);
                }
                text = decoded.toString();
                kind = Kind.STRING;
            } else {
                offset++;
                column++;
                text = Character.toString(c);
                kind = switch (c) {
                    case '{' -> Kind.LBRACE;
                    case '}' -> Kind.RBRACE;
                    case '(' -> Kind.LPAREN;
                    case ')' -> Kind.RPAREN;
                    case ':' -> Kind.COLON;
                    case ',' -> Kind.COMMA;
                    case '.' -> Kind.DOT;
                    case '!' -> Kind.BANG;
                    default -> throw new UiDiagnostic("UI1009", "Unexpected character '" + c + "' in UI source",
                        file, startLine, startColumn);
                };
            }
            result.add(new Token(kind, text, start, offset, startLine, startColumn, line, column - 1));
        }
        result.add(new Token(Kind.EOF, "", source.length(), source.length(), line, column, line, column));
        return List.copyOf(result);
    }
}
