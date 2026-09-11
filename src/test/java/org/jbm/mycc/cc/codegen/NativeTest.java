package org.jbm.mycc.cc.codegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Programs compiled to assembly, built with gcc, and run: the exit status is the check. */
class NativeTest {

    @Test
    void aConstantReturnRuns() throws Exception {
        assertEquals(42, Native.run("int main(void) { return 42; }").exit());
    }

    @Test
    void aMovedValueRuns() throws Exception {
        assertEquals(7, Native.run("int main(void) { char c = 7; int i = c; return i; }").exit());
        assertEquals(200, Native.run("int main(void) { unsigned char c = 200; return c; }").exit());
    }
}
