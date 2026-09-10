package org.jbm.cc.parse.ast;

import lombok.NonNull;
import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.Optional;

/**
 * One attribute out of an attribute-specifier-sequence (C2y 6.7.12.2):
 * {@code [[ prefix::name(arguments) ]]}. The prefix is absent for a
 * standard attribute; the arguments are the raw balanced-token-sequence
 * of the argument clause, absent when there is none.
 */
public record Attribute(@NonNull Token name,
                        @NonNull Optional<Token> prefix,
                        @NonNull Optional<List<Token>> arguments) {
}
