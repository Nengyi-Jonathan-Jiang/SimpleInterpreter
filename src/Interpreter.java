import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum TokenType {
    NUM, ID, FUNCTION, K_FN, OP_ADD, OP_MUL, OP_LP, OP_RP, OP_EQ, OP_FN, UNKNOWN
}

enum ParseNodeType {
    ASSIGNMENT, FN_CALL, ADD, MULT, VAR, NUM, OP, PARENTHESIZED, FUNCTION_NAME
}

record Token(String value, TokenType type) { }

interface TokenStream {
    boolean isExhausted();

    Token peek();

    Token take();

    List<Token> peekRest();

    default Token take(TokenType type) {
        if (peek().type() != type) throw new RuntimeException("Error: expected token " + type + " but got " + peek().type());
        return take();
    }

    default void skip() {
        take();
    }

    default void skip(TokenType type) {
        if (peek().type() != type) throw new RuntimeException("Error: expected token " + type + " but got " + peek().type());
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
        return i >= tokens.size();
    }

    public Token peek() {
        return isExhausted() ? new Token("", TokenType.UNKNOWN) : tokens.get(i);
    }

    public Token take() {
        return isExhausted() ? new Token("", TokenType.UNKNOWN) : tokens.get(i++);
    }

    @Override
    public List<Token> peekRest() {
        return tokens.subList(i, tokens.size());
    }

    @Override
    public String toString() {
        return peekRest().toString();
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

    @Override
    public List<Token> peekRest() {
        return stream.peekRest().stream().map(this::view).toList();
    }

    @Override
    public String toString() {
        return peekRest().toString();
    }
}

class ParseTreeNode implements Iterable<ParseTreeNode> {
    private final ParseNodeType type;
    private final ParseTreeNode[] children;
    private final Token value;

    public ParseTreeNode(ParseNodeType type, Token value) {
        this.type = type;
        children = null;
        this.value = value;
    }

    public ParseTreeNode(ParseNodeType type, ParseTreeNode... children) {
        this.type = type;
        this.children = children;
        this.value = null;
    }

    public ParseTreeNode[] children() {
        if (isLeaf()) throw new Error("Cannot access children of leaf node");
        return children;
    }

    public Token value() {
        if (!isLeaf()) throw new Error("Cannot access value of non-leaf node");
        return value;
    }

    public Iterator<ParseTreeNode> iterator() {
        return children == null ? Collections.emptyIterator() : Arrays.asList(children).iterator();
    }

    public boolean isLeaf() {
        return children == null;
    }

    public ParseNodeType getType() {
        return type;
    }

    public String toString() {
        if (children == null) return value.toString();
        if (children.length == 0) return type + " []";
        return type + " [" + (Arrays.stream(children).map(ParseTreeNode::toString).reduce("", (a, b) -> a + "\n" + b)).replace("\n", "\n    ") + "\n]";
    }
}

record Function(ParseTreeNode body, String[] args, int argLength) {
}

public class Interpreter {
    private final Map<String, Double> variables = new HashMap<>();
    private final Map<String, Function> functions = new HashMap<>();

    private class Parser {
        private void parseFunction(TokenStream tokens) throws Exception {
            tokens.skip(TokenType.K_FN);
            // This is a function declaration
            String name = tokens.take(TokenType.ID).value();
            List<String> argNames = new ArrayList<>();
            do {
                argNames.add(tokens.take(TokenType.ID).value());
            } while (tokens.peek().type() == TokenType.ID);
            tokens.skip(TokenType.OP_FN);
            ParseTreeNode expr = parseExpr(tokens);

            System.out.println("Parsed: FN " + name + "(" + argNames + ") => " + expr);

            functions.put(name, new Function(expr, argNames.toArray(String[]::new), argNames.size()));
        }

        private ParseTreeNode parseExpr(TokenStream tokens) throws Exception {
            return parseAssignExpr(tokens);
        }

        private ParseTreeNode parseAssignExpr(TokenStream tokens) throws Exception {
            ParseTreeNode lhs = parseAddExpr(tokens);
            if(tokens.peek().type() == TokenType.OP_EQ) {
                tokens.skip(TokenType.OP_EQ);
                ParseTreeNode value = parseExpr(tokens);
                return new ParseTreeNode(ParseNodeType.ASSIGNMENT, lhs, value);
            }
            else return lhs;
        }

        private ParseTreeNode parseAddExpr(TokenStream tokens) throws Exception {
            ParseTreeNode factor1 = parseMultExpr(tokens);
//        System.out.println("factor: " + factor1);
//        System.out.println("next: " + tokens.peek());
            Token next = tokens.peek();
            TokenType t = next.type();
            if (t == TokenType.OP_ADD) {
                tokens.skip(TokenType.OP_ADD);
                ParseTreeNode factor2 = parseAddExpr(tokens);
                if (factor2.getType() == ParseNodeType.ADD) {
                    // We have to manually restructure the tree for left associativity
                    return new ParseTreeNode(ParseNodeType.ADD,
                            new ParseTreeNode(ParseNodeType.ADD,
                                    factor1,
                                    new ParseTreeNode(ParseNodeType.OP, next),
                                    factor2.children()[0]
                            ),
                            new ParseTreeNode(ParseNodeType.OP, factor2.children()[1].value()),
                            factor2.children()[2]
                    );
                } else return new ParseTreeNode(ParseNodeType.ADD,
                        factor1,
                        new ParseTreeNode(ParseNodeType.OP, next),
                        factor2
                );
            } else return factor1;
        }

        private ParseTreeNode parseMultExpr(TokenStream tokens) throws Exception {
            ParseTreeNode factor1 = parseFactor(tokens);
//        System.out.println("factor: " + factor1);
//        System.out.println("next: " + tokens.peek());
            Token next = tokens.peek();
            TokenType t = next.type();
            if (t == TokenType.OP_MUL) {
                tokens.skip(TokenType.OP_MUL);
                ParseTreeNode factor2 = parseMultExpr(tokens);
                if (factor2.getType() == ParseNodeType.MULT) {
                    // We have to manually restructure the tree for left associativity
                    return new ParseTreeNode(ParseNodeType.MULT,
                            new ParseTreeNode(ParseNodeType.MULT,
                                    factor1,
                                    new ParseTreeNode(ParseNodeType.OP, next),
                                    factor2.children()[0]
                            ),
                            new ParseTreeNode(ParseNodeType.OP, factor2.children()[1].value()),
                            factor2.children()[2]
                    );
                } else return new ParseTreeNode(ParseNodeType.MULT,
                        factor1,
                        new ParseTreeNode(ParseNodeType.OP, next),
                        factor2
                );
            } else return factor1;
        }

        private ParseTreeNode parseFactor(TokenStream tokens) throws Exception {
            var tk = tokens.take();
            return switch (tk.type()) {
                case NUM -> new ParseTreeNode(ParseNodeType.NUM, tk);
                case ID -> new ParseTreeNode(ParseNodeType.VAR, tk);
                case OP_LP -> {
                    ParseTreeNode inner = parseExpr(tokens);
                    tokens.skip(TokenType.OP_RP);
                    yield new ParseTreeNode(ParseNodeType.PARENTHESIZED, inner);
                }
                case FUNCTION -> {
                    int numParams = functions.get(tk.value()).argLength();
                    ParseTreeNode[] children = new ParseTreeNode[numParams + 1];
                    children[0] = new ParseTreeNode(ParseNodeType.FUNCTION_NAME, tk);
                    for(int i = 1; i <= numParams; i++) children[i] = parseExpr(tokens);
                    yield new ParseTreeNode(ParseNodeType.FN_CALL, children);
                }
                default -> throw new Exception("Unexpected value: " + tk.type());
            };
        }
    }
    private final Parser parser = new Parser();

    private double eval(ParseTreeNode node) throws Exception {
        switch (node.getType()) {
            case ASSIGNMENT -> {
                double ev = eval(node.children()[1]);
                variables.put(node.children()[0].value().value(), ev);
                return ev;
            }
            case FN_CALL -> {
                String funcName = node.children()[0].value().value();
                Function func = functions.get(funcName);

                double[] argValues = new double[func.argLength()];
                for(int i = 0; i < func.argLength(); i++) argValues[i] = eval(node.children()[i + 1]);

                String[] argNames = func.args();
                // Save old arg values
                Map<String, Double> old = new HashMap<>();
                for(String i : argNames) old.put(i, variables.get(i));

                for(int i = 0; i < func.argLength(); i++) {
                    variables.put(argNames[i], argValues[i]);
                }

                double result = eval(func.body());

                variables.putAll(old);
                for(String i : argNames) if(!old.containsKey(i)) variables.remove(i);

                return result;
            }
            case ADD, MULT -> {
                double lhs = eval(node.children()[0]);
                double rhs = eval(node.children()[2]);
                String op = node.children()[1].value().value();
                return switch (op) {
                    case "+" -> lhs + rhs;
                    case "-" -> lhs - rhs;
                    case "*" -> lhs * rhs;
                    case "/" -> lhs / rhs;
                    case "%" -> lhs % rhs;
                    default -> throw new Exception("Invalid binary operator: " + op);
                };
            }
            case PARENTHESIZED -> {
                return eval(node.children()[0]);
            }
            case VAR -> {
                return variables.get(node.value().value());
            }
            case NUM -> {
                return Double.parseDouble(node.value().value());
            }
            case OP, FUNCTION_NAME -> throw new IllegalStateException("Cannot evaluate parse node of type " + node.getType());
        }
        return 0.0;
    }

    public Double input(String input) {
        System.out.println("Input: " + input);

        TokenStream tokens = new TokenStreamView(tokenize(input)) {
            @Override
            public Token view(Token tk) {
                if (tk.type() != TokenType.ID) return tk;
                String id = tk.value();
                if (functions.containsKey(id)) return new Token(id, TokenType.FUNCTION);
                if (variables.containsKey(id)) return new Token(id, TokenType.ID);
                return tk;
            }
        };

        if (tokens.peek().type() == TokenType.K_FN) {
            try {
                parser.parseFunction(tokens);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        try {
            ParseTreeNode expr = parser.parseExpr(tokens);

            if(!tokens.isExhausted()) throw new RuntimeException(new Exception("Expected EOF, instead got " + tokens));

            System.out.println("Parsed: " + expr);
            return eval(expr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
                    if ('0' <= s.charAt(0) && s.charAt(0) <= '9') yield TokenType.NUM;
                    yield TokenType.ID;
                }
            };

            tokens.add(new Token(s, type));
        }
        return new BasicTokenStream(tokens);
    }
}
