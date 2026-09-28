package io.github.akbarhusain.odata.runtime.query;

record RawApplyExpression(String odata) implements ApplyExpression {
    RawApplyExpression {
        if (odata == null || odata.isBlank()) {
            throw new IllegalArgumentException("raw $apply expression must not be blank");
        }
    }

    @Override
    public String toODataApply() {
        return odata;
    }
}
