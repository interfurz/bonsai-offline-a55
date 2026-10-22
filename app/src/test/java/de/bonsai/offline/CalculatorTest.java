package de.bonsai.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class CalculatorTest {
    @Test public void precedencePowersAndFunctions() {
        assertEquals(14,Calculator.evaluate("2*(3+4)"),0);
        assertEquals(512,Calculator.evaluate("2^3^2"),0);
        assertEquals(-4,Calculator.evaluate("-2^2"),0);
        assertEquals(0.25,Calculator.evaluate("2^-2"),0);
        assertEquals(105,Calculator.evaluate("1e2 + sqrt(9) + log10(100)"),0);
        assertEquals(1,Calculator.evaluate("sin(pi/2)"),1e-12);
        assertEquals(3,Calculator.evaluate("abs(-8)%5"),0);
    }
    @Test public void invalidOrUnboundedExpressionsFail() {
        for(String expression:new String[]{"", "2+", "2x3", "(2+3", "1/0", "0/0", "sqrt(-1)", "10^9999", "1e999", "exec(2)", "1 2", "9".repeat(1025), "(".repeat(65)+"1"+")".repeat(65)}) {
            try { Calculator.evaluate(expression); fail(expression); }
            catch(IllegalArgumentException expected) { }
        }
    }
}
