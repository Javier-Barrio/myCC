package org.jbm.repl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Optional;

/** A console over standard input and output: no editing, no history; what a pipe or a plain terminal gets. */
public final class StdioConsole implements Console {

    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    private final PrintStream out = System.out;

    @Override
    public Optional<String> readLine(String prompt) {
        out.print(prompt);
        out.flush();
        try {
            return Optional.ofNullable(in.readLine());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void print(String line) {
        out.println(line);
    }
}
