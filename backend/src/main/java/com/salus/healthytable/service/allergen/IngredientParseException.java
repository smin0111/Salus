package com.salus.healthytable.service.allergen;

public class IngredientParseException extends IllegalArgumentException {
    private final Kind kind;
    public IngredientParseException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }
    public Kind kind() { return kind; }
    public enum Kind { UNBALANCED_BRACKET, MISMATCHED_BRACKET, EMPTY_NODE, UNSUPPORTED_STRUCTURE }
}
