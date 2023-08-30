import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum TokenType {
    NUM, ID, VAR, FUNCTION, K_FN, OP_ADD, OP_MUL, OP_LP, OP_RP, OP_EQ, OP_FN, UNKNOWN
}

enum ParseNodeType {
    ASSIGNMENT, FACTOR, FN_CALL, IDS, ADD, VAR, VALUE, NUM, EXPR, OP, MULT
}

record Token(String value, TokenType type) {
}

interface TokenStream {
    boolean isExhausted();

    Token peek();

    Token take();

    default void skip() {
        take();
    }

    default void skip(TokenType type) {
        if (peek().type() != type) throw new Error("Error: expected token " + type + " but got " + peek().type());
        skip();
    }
}

class BasicTokenStream implements TokenStream {
    private final List<Token> tokens;
    private int i;

    BasicTokenStream(List<Token> tokens) {
        this.tokens = Collections.unmodifiableList(tokens);
    }

    public boolean isExhausted() {
        return i > tokens.size();
    }

    public Token peek() {
        return tokens.get(i);
    }

    public Token take() {
        return tokens.get(i++);
    }
}

abstract class TokenStreamView implements TokenStream {
    private final TokenStream stream;

    public TokenStreamView(TokenStream stream) {
        this.stream = stream;
    }

    public abstract Token view(Token tk);

    @Override
    public final Token take() {
        return view(stream.take());
    }

    @Override
    public final Token peek() {
        return view(stream.peek());
    }

    @Override
    public final boolean isExhausted() {
        return stream.isExhausted();
    }
}

class ParseTree implements Iterable<ParseTree> {
    private final ParseNodeType type;
    private final ParseTree[] children;
    private final Token value;

    public ParseTree(ParseNodeType type, Token value) {
        this.type = type;
        children = null;
        this.value = value;
    }

    public ParseTree(ParseNodeType type, ParseTree... children) {
        this.type = type;
        this.children = children;
        this.value = null;
    }

    public ParseTree[] children() {
        if (isLeaf()) throw new Error("Cannot access children of leaf node");
        return children;
    }

    public Token value() {
        if (!isLeaf()) throw new Error("Cannot access value of non-leaf node");
        return value;
    }

    public Iterator<ParseTree> iterator() {
        return children == null ? Collections.emptyIterator() : Arrays.asList(children).iterator();
    }

    public boolean isLeaf() {
        return children == null;
    }

    public ParseNodeType getType() {
        return type;
    }

    public String toString() {
        if (children == null) return String.valueOf(value);
        if (children.length == 0) return type + " []";
        return type + " [" + (Arrays.stream(children).map(ParseTree::toString).reduce("", (a, b) -> a + "\n" + b)).replace("\n", "\n    ") + "\n]";
    }
}

record Function(ParseTree eval, String[] args, int argLength) {
}

public class Interpreter {
    private Map<String, Double> variables;
    private Map<String, Function> functions;

    private void parseFunction(TokenStream tokens) {
        tokens.skip(TokenType.K_FN);
        // This is a function declaration
        String name = tokens.take().value();
        List<String> argNames = new ArrayList<>();
        while (tokens.peek().type() == TokenType.ID) {
            argNames.add(tokens.take().value());
        }
        tokens.skip(TokenType.OP_FN);
        ParseTree expr = parseExpr(tokens);
        functions.put(name, new Function(expr, argNames.toArray(String[]::new), argNames.size()));
    }

    private ParseTree parseExpr(TokenStream tokens) {
        return parseAssignExpr(tokens);
    }

    private ParseTree parseAssignExpr(TokenStream tokens) {
        if (tokens.peek().type() == TokenType.VAR) {
            Token var = tokens.take();
            tokens.skip(TokenType.OP_EQ);
            ParseTree value = parseExpr(tokens);
            return new ParseTree(ParseNodeType.ASSIGNMENT, new ParseTree(ParseNodeType.VAR, var), new ParseTree(ParseNodeType.VALUE, value));
        } else return parseAddExpr(tokens);
    }

    private ParseTree parseAddExpr(TokenStream tokens) {
        ParseTree factor1 = parseMultExpr(tokens);
        Token next = tokens.peek();
        TokenType t = next.type();
        if(t == TokenType.OP_ADD) {
            tokens.skip(TokenType.OP_ADD);
            ParseTree factor2 = parseAddExpr(tokens);
            if(factor2.getType() == ParseNodeType.ADD) {
                // We have to manually restructure the tree for left associativity
                return new ParseTree(ParseNodeType.ADD,
                        new ParseTree(ParseNodeType.ADD,
                                factor1,
                                new ParseTree(ParseNodeType.OP, next),
                                factor2.children()[0]
                        ),
                        new ParseTree(ParseNodeType.OP, factor2.children()[1]),
                        factor2.children()[2]
                );
            }
            else return new ParseTree(ParseNodeType.ADD, factor1, factor2);
        }
        else return factor1;
    }

    private ParseTree parseMultExpr(TokenStream tokens) {
        ParseTree factor1 = parseFactor(tokens);
        Token next = tokens.peek();
        TokenType t = next.type();
        if(t == TokenType.OP_MUL) {
            tokens.skip(TokenType.OP_MUL);
            ParseTree factor2 = parseMultExpr(tokens);
            if(factor2.getType() == ParseNodeType.MULT) {
                // We have to manually restructure the tree for left associativity
                return new ParseTree(ParseNodeType.MULT,
                    new ParseTree(ParseNodeType.MULT,
                        factor1,
                        new ParseTree(ParseNodeType.OP, next),
                        factor2.children()[0]
                    ),
                    new ParseTree(ParseNodeType.OP, factor2.children()[1]),
                    factor2.children()[2]
                );
            }
            else {
                return new ParseTree(ParseNodeType.MULT, factor1, factor2);
            }
        }
        else return factor1;
    }

    private ParseTree parseFactor(TokenStream tokens) {
        var tk = tokens.peek();
        return switch (tk.type()) {
            case NUM -> new ParseTree(ParseNodeType.NUM, tk);
            case VAR -> new ParseTree(ParseNodeType.VAR, tk);
            case OP_LP -> {
                tokens.skip(TokenType.OP_LP);
                ParseTree inner = parseExpr(tokens);
                tokens.skip(TokenType.OP_RP);
                yield inner;
            }
            default -> throw new IllegalStateException("Unexpected value: " + tk.type());
        };
    }

    public Double input(String input) {
        TokenStream tokens = new TokenStreamView(tokenize(input)) {
            @Override
            public Token view(Token tk) {
                if (tk.type() != TokenType.ID) return tk;
                String id = tk.value();
                if (variables.containsKey(id)) return new Token(id, TokenType.VAR);
                if (functions.containsKey(id)) return new Token(id, TokenType.FUNCTION);
                throw new Error("Identifier: " + id + " has not been declared yet.");
            }
        };

        if (tokens.peek().type() == TokenType.K_FN) {
            parseFunction(tokens);
            return 0.;
        }

        System.out.println(tokens);

        return 0.;
    }

    private static TokenStream tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        Pattern pattern = Pattern.compile("=>|[-+*/%=()]|[A-Za-z_][A-Za-z0-9_]*|[0-9]*(\\.?[0-9]+)");
        Matcher m = pattern.matcher(input);
        while (m.find()) {
            String s = m.group();
            TokenType type = switch (s) {
                case "=>" -> TokenType.OP_FN;
                case "+", "-" -> TokenType.OP_ADD;
                case "*", "/", "%" -> TokenType.OP_MUL;
                case "=" -> TokenType.OP_EQ;
                case "(" -> TokenType.OP_LP;
                case ")" -> TokenType.OP_RP;
                case "fn" -> TokenType.K_FN;
                default -> {
                    if ('0' <= s.charAt(0) && s.charAt(0) <= '9')
                        yield TokenType.NUM;
                    yield TokenType.UNKNOWN;
                }
            };

            tokens.add(new Token(s, type));
        }
        return new BasicTokenStream(tokens);
    }
}