import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class InterpreterTest {
    Interpreter interpreter = new Interpreter();

    @Test
    public void arithmeticTest() throws Exception {
        assertEquals(2, interpreter.input("1 + 1"), 0.0);
        assertEquals(1, interpreter.input("2 - 1"), 0.0);
        assertEquals(6, interpreter.input("2 * 3"), 0.0);
        assertEquals(2, interpreter.input("8 / 4"), 0.0);
        assertEquals(3, interpreter.input("7 % 4"), 0.0);
    }

    @Test
    public void orderOfOperationsTest() throws Exception {
        assertEquals(6, interpreter.input("(8 - (4 + 2)) * 3"), 0.0);
    }

    @Test
    public void variablesTest() throws Exception {
        assertEquals(1, interpreter.input("x = 1"), 0.0);
        assertEquals(1, interpreter.input("x"), 0.0);
        assertEquals(4, interpreter.input("x + 3"), 0.0);
        assertFail("input: 'y'", "y");
    }

    @Test
    public void functionTest() throws Exception {
        assertEquals(0, interpreter.input("fn pair x y => (x + y) * (x + y + 1) / 2 + y"), 0.0);
        assertEquals(50, interpreter.input("pair 4 5"), 0.0);
        assertEquals(42, interpreter.input("pair 2 6"), 0.0);
        assertEquals(97, interpreter.input("pair pair 2 1 6"), 0.0);
        assertEquals(700, interpreter.input("pair 2 pair 1 6"), 0.0);
    }

    private void assertFail(String msg, String input) {
        try {
            interpreter.input(input);
            fail(msg);
        } catch (Exception ignored) {}
    }
}
