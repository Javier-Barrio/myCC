package org.jbm.repl;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The terminal console over JLine: line editing, history kept in
 * {@code ~/.cshell_history}, tab completion, Ctrl-D ends the session,
 * Ctrl-C clears the line.
 */
public final class JLineConsole implements Console {

    private final Terminal terminal;
    private final LineReader reader;

    public JLineConsole(Completer completer) {
        try {
            terminal = TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer((lineReader, parsed, candidates) -> {
                    List<Completer.Candidate> found = completer.complete(parsed.line(), parsed.cursor());
                    for (Completer.Candidate c : found) {
                        candidates.add(new org.jline.reader.Candidate(c.text(), c.text(), null, c.description(), null, null, true));
                    }
                })
                .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".cshell_history"))
                .build();
    }

    @Override
    public Optional<String> readLine(String prompt) {
        try {
            return Optional.of(reader.readLine(prompt));
        } catch (EndOfFileException e) {
            return Optional.empty();
        } catch (UserInterruptException e) {
            return Optional.of("");
        }
    }

    @Override
    public void print(String line) {
        terminal.writer().println(line);
        terminal.writer().flush();
    }
}
