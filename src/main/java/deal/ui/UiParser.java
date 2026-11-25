package deal.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UiParser {
    private enum K { ID, STRING, NUMBER, TEMPLATE, SYMBOL, EOF }
    private record T(K kind, String text, int line, int column, int endLine, int endColumn) {}

    private final Path file;
    private final String source;
    private final List<T> tokens;
    private int position;

    private UiParser(Path file, String source) {
        this.file = file;
        this.source = source;
        try {
            tokens = lex(source);
        } catch (UiDiagnostic failure) {
            throw new UiDiagnostic(failure.code(), failure.getMessage(), file, failure.line(), failure.column(),
                    failure.endLine(), failure.endColumn(), failure.expected(), failure.actual()).withSources(Map.of(file.toString(), source));
        }
    }

    public static UiModel.ViewModule parseViews(Path file, String source) {
        try { return new UiParser(file, source).views(source); }
        catch (UiDiagnostic failure) { throw failure.withSources(Map.of(file.toString(), source)); }
    }

    public static UiModel.PackModule parsePack(Path file, String source) {
        try { return new UiParser(file, source).pack(source); }
        catch (UiDiagnostic failure) { throw failure.withSources(Map.of(file.toString(), source)); }
    }

    private UiModel.ViewModule views(String source) {
        List<UiModel.Import> imports = imports();
        List<UiModel.View> views = new ArrayList<>();
        while (!at(K.EOF)) {
            boolean root = match("@ui-root");
            boolean exported = match("export");
            T start = require("view");
            String name = id();
            require("(");
            List<UiModel.Parameter> parameters = new ArrayList<>();
            if (!at(")")) {
                do {
                    String parameter = id();
                    require(":");
                    parameters.add(new UiModel.Parameter(parameter, type()));
                } while (match(",") && !at(")"));
            }
            require(")");
            require(":");
            require("View");
            views.add(new UiModel.View(exported, root, name, parameters, nodes(), span(start)));
        }
        if (views.isEmpty()) fail("UI1001", "At least one view is required", peek());
        return new UiModel.ViewModule(imports, views, source);
    }

    private UiModel.PackModule pack(String source) {
        List<UiModel.Import> imports = imports();
        String version = "unversioned";
        if (match("pack")) {
            require("version");
            version = string();
            require(";");
        }
        Map<String, UiModel.PackClass> classes = new LinkedHashMap<>();
        Map<String, UiModel.Component> components = new LinkedHashMap<>();
        Map<String, UiModel.Token> tokens = new LinkedHashMap<>();
        while (!at(K.EOF)) {
            require("export");
            T start = peek();
            if (match("class")) {
                String name = id();
                require("{");
                List<UiModel.Field> fields = new ArrayList<>();
                while (!match("}")) {
                    T field = peek();
                    String fieldName = id();
                    boolean optional = match("?");
                    require(":");
                    UiModel.TypeRef type = type();
                    UiModel.Expr value = match("=") ? packDefault() : null;
                    match(";");
                    fields.add(new UiModel.Field(fieldName, type, value, span(field), optional));
                }
                duplicate(classes, name, new UiModel.PackClass(name, fields, span(start)));
            } else if (match("component")) {
                String name = id();
                require("(");
                require("props");
                require(":");
                String props = qualified();
                require(")");
                require(":");
                require("View");
                List<UiModel.Contract> contracts = new ArrayList<>();
                if (match("{")) {
                    while (!match("}")) {
                        if (match("children")) {
                            boolean required = match("required");
                            if (!required) match("optional");
                            String componentType = null;
                            if (at(K.ID)) {
                                StringBuilder types = new StringBuilder(qualified());
                                while (match("|")) types.append('|').append(qualified());
                                componentType = types.toString();
                            }
                            contracts.add(new UiModel.Children(required, componentType));
                        } else if (match("parent")) {
                            require("required");
                            StringBuilder types = new StringBuilder(qualified());
                            while (match("|")) types.append('|').append(qualified());
                            contracts.add(new UiModel.Parent(types.toString()));
                        } else if (match("event")) {
                            String prop = id();
                            UiModel.TypeRef payload = null;
                            if (match("(")) {
                                require("payload");
                                require(":");
                                payload = type();
                                require(")");
                            }
                            contracts.add(new UiModel.Event(prop, payload));
                        } else if (match("accessibility")) {
                            contracts.add(new UiModel.Accessibility(id()));
                        } else if (match("token")) {
                            contracts.add(new UiModel.TokenProp(id()));
                        } else if (match("capability")) {
                            contracts.add(new UiModel.Capability(string()));
                        } else fail("UI1002", "Expected component contract", peek());
                        require(";");
                    }
                } else require(";");
                duplicate(components, name, new UiModel.Component(name, props, contracts, span(start)));
            } else if (match("token")) {
                String name = id();
                require(":");
                UiModel.TypeRef type = type();
                UiModel.Expr value = match("=") ? packDefault() : null;
                require(";");
                duplicate(tokens, name, new UiModel.Token(name, type, value, span(start)));
            } else fail("UI1003", "Expected pack class, component, or token", peek());
        }
        return new UiModel.PackModule(version, sha256(source), imports, classes, components, tokens, source);
    }

    private List<UiModel.Import> imports() {
        List<UiModel.Import> result = new ArrayList<>();
        while (at("import")) {
            T start = take();
            if (!at("*")) unsupported("UI1020", "Named/default/side-effect imports are not supported; use import * as alias from \"module\"", peek());
            require("*");
            require("as");
            String alias = id();
            require("from");
            String specifier = string();
            match(";");
            result.add(new UiModel.Import(alias, specifier, span(start)));
        }
        return List.copyOf(result);
    }

    private List<UiModel.Node> nodes() {
        require("{");
        List<UiModel.Node> result = new ArrayList<>();
        while (!match("}")) result.add(node());
        return List.copyOf(result);
    }

    private UiModel.Node node() {
        T start = peek();
        if (at("if")) unsupported("UI1021", "Imperative if is not a view node; use When(condition) { ... } Else { ... }", start);
        if (at("<")) unsupported("UI1021", "JSX is not supported; use a named component invocation", start);
        if (atNamespacedStructural("When") || atNamespacedStructural("ForEach")) {
            String structural = tokens.get(position + 2).text();
            fail(
                "UI1014",
                "Structural control flow is not namespaced; write " + structural + "(...) instead of ui." + structural + "(...)",
                start
            );
        }
        if (match("When")) {
            require("(");
            UiModel.Expr condition = expression();
            require(")");
            List<UiModel.Node> yes = nodes();
            List<UiModel.Node> no = match("Else") ? nodes() : List.of();
            return new UiModel.When(condition, yes, no, span(start));
        }
        if (match("ForEach")) {
            require("(");
            if (!at(K.ID)) unsupported("UI1022", "ForEach requires a direct collection path; prepare computed collections in DEAL", peek());
            UiModel.PathExpr source = path();
            if (!at(",")) unsupported("UI1022", "ForEach requires a direct collection path; prepare computed collections in DEAL", peek());
            require(",");
            String item = id();
            require(":");
            UiModel.TypeRef itemType = type();
            require(",");
            require("key");
            require(":");
            UiModel.PathExpr key = path();
            require(")");
            return new UiModel.ForEach(source, new UiModel.Parameter(item, itemType), key, nodes(), span(start));
        }
        String name = qualified();
        require("(");
        Map<String, UiModel.Expr> arguments = new LinkedHashMap<>();
        if (!at(")")) {
            do {
                if (at("{")) unsupported("UI1023", "Object-wrapped props are not supported; write Component(prop: value)", peek());
                if (!at(K.ID) || position + 1 >= tokens.size() || !tokens.get(position + 1).text().equals(":"))
                    unsupported("UI1023", "Positional arguments are not supported; write Component(prop: value)", peek());
                String argument = id();
                require(":");
                if (arguments.putIfAbsent(argument, expression()) != null) fail("UI1004", "Duplicate argument '" + argument + "'", start);
            } while (match(",") && !at(")"));
        }
        require(")");
        UiModel.Span childBlock = null;
        List<UiModel.Node> children = List.of();
        if (at("{")) {
            T childStart = peek();
            children = nodes();
            childBlock = span(childStart);
        }
        match(";");
        return new UiModel.Call(name, arguments, children, childBlock, span(start));
    }

    private UiModel.Expr expression() {
        UiModel.Expr value = binary(1);
        rejectExpressionToken();
        if (at("?")) {
            fail(
                "UI1015",
                "Conditional expressions are not part of Deal UI; render alternatives with When(condition) { ... } Else { ... }",
                peek()
            );
        }
        return value;
    }

    private UiModel.Expr binary(int level) {
        if (level == 7) return unary();
        UiModel.Expr left = binary(level + 1);
        while (at(K.SYMBOL) && precedence(peek().text()) == level) {
            T operator = take();
            left = new UiModel.Binary(operator.text(), left, binary(level + 1), span(operator));
        }
        return left;
    }

    private int precedence(String operator) {
        return switch (operator) {
            case "||" -> 1;
            case "&&" -> 2;
            case "===", "!==" -> 3;
            case "<", "<=", ">", ">=" -> 4;
            case "+", "-" -> 5;
            case "*", "/" -> 6;
            default -> 0;
        };
    }

    private UiModel.Expr unary() {
        T start = peek();
        if (at("[") || at("{") || at(K.TEMPLATE)) unsupported("UI1024", "Array/object/template literals are not view expressions; prepare a field in DEAL", start);
        if (at("<")) unsupported("UI1021", "JSX/type assertions are not view expressions; use named components and checked DEAL fields", start);
        if (at("typeof") || at("instanceof") || at("~")) unsupported("UI1025", "Operator '" + start.text() + "' is not supported in view expressions; compute a field in DEAL", start);
        if (match("!") || match("-")) return new UiModel.Unary(previous().text(), unary(), span(start));
        if (match("(")) {
            UiModel.Expr value = expression();
            require(")");
            return value;
        }
        if (match("has")) {
            require("(");
            UiModel.PathExpr value = path();
            require(")");
            return new UiModel.Has(value, span(start));
        }
        if (match("action")) {
            String name = qualified();
            if (!at("{")) {
                T token = peek();
                throw new UiDiagnostic("UI1009",
                        "Expected '{' after action " + name + "; action bindings use field initializers, not function-call arguments",
                        file, token.line(), token.column(), token.endLine(), token.endColumn(),
                        "action " + name + " { field: value }; omit fields for a parameterless action",
                        token.kind() == K.EOF ? "end of input" : token.text());
            }
            require("{");
            Map<String, UiModel.Expr> fields = new LinkedHashMap<>();
            while (!match("}")) {
                String field = id();
                require(":");
                if (fields.putIfAbsent(field, expression()) != null) fail("UI1005", "Duplicate action field '" + field + "'", start);
                match(",");
            }
            return new UiModel.Action(name, fields, span(start));
        }
        if (match("null")) return new UiModel.Literal(null, "null", span(start));
        if (match("true") || match("false")) return new UiModel.Literal(previous().text().equals("true"), "boolean", span(start));
        if (at(K.STRING)) return new UiModel.Literal(string(), "string", span(start));
        if (at(K.NUMBER)) return new UiModel.Literal(Double.parseDouble(take().text()), "number", span(start));
        UiModel.PathExpr value = path();
        if (at("(")) fail("UI1016", "Function calls are not supported in view expressions. Compute this value in DEAL and bind a state/row field.", peek());
        return value;
    }

    private void rejectExpressionToken() {
        T token = peek();
        if (at("==") || at("!=")) unsupported("UI1026", "Coercing equality '" + token.text() + "' is not supported. Strict ===/!== do not coerce and distinguish null from undefined; choose the intended comparison in DEAL", token);
        if (at("??")) unsupported("UI1027", "Nullish coalescing is not supported in views; prepare the fallback in DEAL", token);
        if (at("?.")) unsupported("UI1028", "Optional chaining is not supported in views; prepare a safe field in DEAL or use When/has", token);
        if (at("[")) unsupported("UI1029", "Indexing is not supported in views; use ForEach or prepare a selected field in DEAL", token);
        if (at("!")) unsupported("UI1030", "Postfix non-null assertion is not supported; check presence in DEAL", token);
        if (at(K.TEMPLATE)) unsupported("UI1024", "Tagged templates are not view expressions; prepare text in DEAL", token);
        if (List.of("%", "**", "&", "|", "^", "~", "<<", ">>", ">>>", "as", "typeof", "instanceof").contains(token.text()) && token.kind() != K.STRING)
            unsupported("UI1025", "Operator '" + token.text() + "' is not supported in view expressions; compute a field in DEAL", token);
        if (List.of("=", "+=", "-=", "*=", "/=", "%=", "**=", "&&=", "||=", "??=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "++", "--").contains(token.text()) && token.kind() == K.SYMBOL)
            unsupported("UI1031", "Assignment/update is not supported in declarative views; dispatch an action to a DEAL update", token);
    }

    private void unsupported(String code, String message, T token) {
        throw new UiDiagnostic(code, message, file, token.line(), token.column(), token.endLine(), token.endColumn(),
                "supported declarative view syntax", token.text())
                .withFacts(new deal.diagnostics.DiagnosticFacts(code, "ui.parse", "supported declarative view syntax", token.text(),
                        List.of("Compute application values in DEAL; bind declared fields or use When/ForEach in the view."), List.of(), null, null, null,
                        new deal.diagnostics.DiagnosticFacts.SyntaxContext(switch (code) {
                            case "UI1020" -> "ui.import";
                            case "UI1021" -> "ui.node";
                            case "UI1022" -> "ui.foreach";
                            case "UI1023" -> "ui.invocation";
                            default -> "ui.expression";
                        }, List.of("declarative expression/node"), token.kind().name(), token.text(), token.text().length(), false)))
                .withSources(Map.of(file.toString(), source));
    }

    private UiModel.Expr packDefault() {
        T start = peek();
        if (match("-")) {
            T number = take();
            if (number.kind() == K.NUMBER) return new UiModel.Literal(-Double.parseDouble(number.text()), "number", span(start));
            fail("UI1006", "Expected numeric pack default", number);
        }
        if (match("[")) {
            List<UiModel.Expr> values = new ArrayList<>();
            if (!at("]")) do values.add(packDefault()); while (match(",") && !at("]"));
            require("]");
            return new UiModel.Literal(List.copyOf(values), "array", span(start));
        }
        if (match("{")) {
            Map<String, UiModel.Expr> values = new LinkedHashMap<>();
            if (!at("}")) do { String name = id(); require(":"); values.put(name, packDefault()); } while (match(",") && !at("}"));
            require("}");
            return new UiModel.Literal(Map.copyOf(values), "object", span(start));
        }
        return unary();
    }

    private UiModel.PathExpr path() {
        T start = peek();
        List<String> parts = new ArrayList<>();
        parts.add(id());
        while (match(".")) parts.add(id());
        return new UiModel.PathExpr(parts, span(start));
    }

    private UiModel.TypeRef type() { return type(false); }
    private UiModel.TypeRef type(boolean optional) {
        boolean readonly = match("readonly");
        UiModel.TypeRef base;
        if (match("(")) { base = type(); require(")"); }
        else {
            String name = qualified();
            if (name.equals("Record")) {
                require("<"); require("string"); require(",");
                String value = id();
                if (!value.equals("string") && !value.equals("unknown")) fail("UI1007", "Record values must be string or unknown", previous());
                require(">"); name = "Record<string, " + value + ">";
            }
            base = new UiModel.TypeRef(name, false, false);
        }
        int initialDepth = base.dimensions();
        while (match("[")) { require("]"); base = base.arrayOf(false); }
        if (readonly && base.dimensions() == initialDepth) fail("UI1007", "readonly requires an array type", peek());
        if (readonly) {
            var layers = new ArrayList<>(base.readonlyLayers());
            layers.set(layers.size() - 1, true);
            base = new UiModel.TypeRef(base.name(), base.optional(), base.dimensions(), layers, base.nullableElements(), base.undefined(), base.undefinedElements());
        }
        while (match("|")) {
            UiModel.TypeRef member = type();
            if (!base.array() && (base.name().equals("null") || base.name().equals("undefined"))) {
                boolean nullMember = base.name().equals("null") || base.optional();
                boolean undefinedMember = base.name().equals("undefined") || base.undefined();
                base = member.withOptional(member.optional() || nullMember).withUndefined(member.undefined() || undefinedMember);
            } else if (!member.array() && member.name().equals("null")) base = base.withOptional(true).withUndefined(base.undefined() || member.undefined());
            else if (!member.array() && member.name().equals("undefined")) base = base.withUndefined(true).withOptional(base.optional() || member.optional());
            else fail("UI1007", "Only nullish unions are supported", previous());
        }
        return base.withOptional(optional || base.optional());
    }
    private String qualified() {
        StringBuilder value = new StringBuilder(id());
        while (match(".")) value.append('.').append(id());
        return value.toString();
    }
    private String id() {
        if (!at(K.ID)) fail("UI1007", "Expected identifier", peek());
        return take().text();
    }
    private String string() {
        if (!at(K.STRING)) fail("UI1008", "Expected string literal", peek());
        return take().text();
    }
    private UiModel.Span span(T token) {
        T end = position == 0 ? token : previous();
        if (end.line() < token.line() || end.line() == token.line() && end.column() < token.column()) end = token;
        return new UiModel.Span(file, token.line(), token.column(), end.endLine(), end.endColumn());
    }
    private T peek() { return tokens.get(position); }
    private T previous() { return tokens.get(position - 1); }
    private T take() { return tokens.get(position++); }
    private boolean atNamespacedStructural(String name) {
        return position + 2 < tokens.size()
            && tokens.get(position).kind() == K.ID
            && tokens.get(position + 1).kind() == K.SYMBOL
            && tokens.get(position + 2).kind() == K.ID
            && tokens.get(position).text().equals("ui")
            && tokens.get(position + 1).text().equals(".")
            && tokens.get(position + 2).text().equals(name);
    }
    private boolean at(K kind) { return peek().kind() == kind; }
    private boolean at(String text) { return (at(K.ID) || at(K.SYMBOL)) && peek().text().equals(text); }
    private boolean match(String text) { if (!at(text)) return false; position++; return true; }
    private T require(String text) {
        if (!at(text)) {
            T token = peek();
            throw new UiDiagnostic("UI1009", "Expected '" + text + "'", file,
                    token.line(), token.column(), token.endLine(), token.endColumn(), text, token.kind() == K.EOF ? "end of input" : token.text());
        }
        return take();
    }
    private void fail(String code, String message, T token) {
        throw new UiDiagnostic(code, message, file, token.line(), token.column(), token.endLine(), token.endColumn(),
                "", token.kind() == K.EOF ? "end of input" : token.text());
    }
    private <V> void duplicate(Map<String, V> map, String name, V value) { if (map.putIfAbsent(name, value) != null) fail("UI1010", "Duplicate declaration '" + name + "'", previous()); }

    private static List<T> lex(String source) {
        List<T> result = new ArrayList<>();
        int i = 0, line = 1, column = 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { if (c == '\n') { line++; column = 1; } else column++; i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int start = i, startColumn = column;
                while (i < source.length() && source.charAt(i) != '\n') { i++; column++; }
                String comment = source.substring(start, i).trim();
                if (comment.equals("// @ui-root")) result.add(new T(K.ID, "@ui-root", line, startColumn, line, column - 1));
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2; column += 2;
                boolean closed = false;
                while (i < source.length()) { if (i + 1 < source.length() && source.charAt(i) == '*' && source.charAt(i + 1) == '/') { i += 2; column += 2; closed = true; break; } if (source.charAt(i) == '\n') { line++; column = 1; i++; } else { i++; column++; } }
                if (!closed) throw new UiDiagnostic("UI1011", "Unterminated comment", Path.of("<source>"), line, column);
                continue;
            }
            int startLine = line, startColumn = column;
            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                column++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) { i++; column++; }
                result.add(new T(K.ID, source.substring(start, i), startLine, startColumn, line, column - 1));
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i++;
                column++;
                while (i < source.length() && Character.isDigit(source.charAt(i))) { i++; column++; }
                if (i < source.length() && source.charAt(i) == '.') { i++; column++; while (i < source.length() && Character.isDigit(source.charAt(i))) { i++; column++; } }
                // The canonical printer emits Double spellings, including exponents.
                // Consume the entire literal so emitted source is parseable again.
                if (i < source.length() && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
                    i++; column++;
                    if (i < source.length() && (source.charAt(i) == '+' || source.charAt(i) == '-')) { i++; column++; }
                    int digits = i;
                    while (i < source.length() && source.charAt(i) >= '0' && source.charAt(i) <= '9') { i++; column++; }
                    if (i == digits) throw new UiDiagnostic("UI1006", "Expected exponent digits", Path.of("<source>"), startLine, startColumn);
                }
                String spelling = source.substring(start, i);
                if (!Double.isFinite(Double.parseDouble(spelling))) throw new UiDiagnostic("UI1006", "Numeric literal must be finite", Path.of("<source>"), startLine, startColumn);
                result.add(new T(K.NUMBER, spelling, startLine, startColumn, line, column - 1));
                continue;
            }
            if (c == '"') {
                i++; column++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < source.length()) {
                    char d = source.charAt(i++); column++;
                    if (d == '"') { closed = true; break; }
                    if (d == '\\') { if (i >= source.length()) break; char e = source.charAt(i++); column++; value.append(switch (e) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '"' -> '"'; case '\\' -> '\\'; default -> throw new UiDiagnostic("UI1012", "Unsupported escape", Path.of("<source>"), startLine, startColumn); }); }
                    else value.append(d);
                }
                if (!closed) throw new UiDiagnostic("UI1012", "Unterminated string", Path.of("<source>"), startLine, startColumn);
                result.add(new T(K.STRING, value.toString(), startLine, startColumn, line, column - 1));
                continue;
            }
            if (c == '`') {
                int start = i++; column++;
                boolean closed = false;
                while (i < source.length()) {
                    char d = source.charAt(i++);
                    if (d == '\n') { line++; column = 1; } else column++;
                    if (d == '`') { closed = true; break; }
                    if (d == '\\' && i < source.length()) { if (source.charAt(i++) == '\n') { line++; column = 1; } else column++; }
                }
                if (!closed) throw new UiDiagnostic("UI1024", "Unterminated template literal; templates are not view expressions", Path.of("<source>"), startLine, startColumn);
                result.add(new T(K.TEMPLATE, source.substring(start, i), startLine, startColumn, line, column - 1));
                continue;
            }
            String op = null;
            for (String candidate : List.of(">>>=", "===", "!==", "**=", "&&=", "||=", "??=", "<<=", ">>=", ">>>", "==", "!=", "??", "?.", "**", "<<", ">>", "||", "&&", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "++", "--")) {
                if (source.startsWith(candidate, i)) { op = candidate; break; }
            }
            if (op != null) {
                result.add(new T(K.SYMBOL, op, startLine, startColumn, line, startColumn + op.length() - 1)); i += op.length(); column += op.length(); continue;
            }
            if ("{}()[]:,.?;=+-*/!<>|%&^~".indexOf(c) >= 0) { result.add(new T(K.SYMBOL, Character.toString(c), startLine, startColumn, line, column)); i++; column++; continue; }
            throw new UiDiagnostic("UI1013", "Unexpected character '" + c + "'", Path.of("<source>"), line, column);
        }
        result.add(new T(K.EOF, "", line, column, line, column));
        return List.copyOf(result);
    }

    private static String sha256(String source) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
