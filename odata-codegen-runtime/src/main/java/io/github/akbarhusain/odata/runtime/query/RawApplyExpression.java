package io.github.akbarhusain.odata.runtime.query;

record RawApplyExpression(String odata) implements ApplyExpression {
    @Override
    public String toODataApply() {
        return odata;
    }
}
