package com.modernodata.runtime.query;

record RawFilterExpression(String odata) implements FilterExpression {
    @Override
    public String toODataExpression() {
        return odata;
    }

    @Override
    public FilterExpression and(FilterExpression other) {
        return new RawFilterExpression("(" + odata + ") and (" + other.toODataExpression() + ")");
    }

    @Override
    public FilterExpression or(FilterExpression other) {
        return new RawFilterExpression("(" + odata + ") or (" + other.toODataExpression() + ")");
    }

    @Override
    public FilterExpression not() {
        return new RawFilterExpression("not (" + odata + ")");
    }
}
