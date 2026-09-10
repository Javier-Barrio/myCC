package org.jbm.repl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A console fed from a list of lines, recording the whole session as
 * it would appear on a terminal: prompts, inputs and output in order.
 */
public final class ScriptConsole implements Console {

    private final Deque<String> inputs;
    private final StringBuilder transcript = new StringBuilder();

    public ScriptConsole(List<String> inputs) {
        this.inputs = new ArrayDeque<>(inputs);
    }

    @Override
    public Optional<String> readLine(String prompt) {
        String line = inputs.poll();
        if (line == null) {
            return Optional.empty();
        }
        transcript.append(prompt).append(line).append('\n');
        return Optional.of(line);
    }

    @Override
    public void print(String line) {
        transcript.append(line).append('\n');
    }

    public String transcript() {
        return transcript.toString();
    }
}
