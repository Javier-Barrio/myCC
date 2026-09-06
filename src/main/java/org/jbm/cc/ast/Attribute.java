package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;
import java.util.Optional;

/**
 * One attribute out of an attribute-specifier-sequence (C2y 6.7.12.2):
 * {@code [[ prefix::name(arguments) ]]}. The prefix is absent for a
 * standard attribute; the arguments are the raw balanced-token-sequence
 * of the argument clause, absent when there is none.
 */
public record Attribute(Token name, Optional<Token> prefix, Optional<List<Token>> arguments) {
}
