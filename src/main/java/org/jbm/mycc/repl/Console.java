package org.jbm.mycc.repl;

import java.util.Optional;

/** Where the shell reads lines and writes output: a terminal, or a script in tests. */
public interface Console {

    /** The next line, or empty at the end of input. */
    Optional<String> readLine(String prompt);

    /** One line of output. */
    void print(String line);
}
