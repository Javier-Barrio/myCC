package org.jbm.cc.ast;

import org.jbm.cc.cpp.CppTokenizer.Token;

import java.util.List;

/**
 * One attribute out of an attribute-specifier-sequence (C2y 6.7.12.2):
 * {@code [[ prefix::name(arguments) ]]}. The prefix is null for a standard
 * attribute; the arguments are the raw balanced-token-sequence of the
 * argument clause, or null when there is none.
 */
public record Attribute(Token name, Token prefix, List<Token> arguments) {
}
