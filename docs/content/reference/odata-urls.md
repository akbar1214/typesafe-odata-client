# OData URL Patterns

How Modern OData Client builds OData URLs.

## URL Structure

```
{baseUrl}/{entitySet}({key})
    /{navigationProperty}
    ?$filter=...
    &$select=...
    &$expand=...
    &$orderby=...
    &$top=...
    &$skip=...
    &$count=true
```

## Entity Set URLs

### List Entities

```
GET /V4/TripPinService/People
```

### Single Entity by Key

```
GET /V4/TripPinService/People('scottketchum')
```

### Composite Key

```
GET /V4/TripPinService/OrderDetails(OrderId=1,ProductId=5)
```

## Navigation URLs

### Navigation Property

```
GET /V4/TripPinService/People('scottketchum')/Trips
```

### Nested Navigation

```
GET /V4/TripPinService/People('scottketchum')/Trips(1)/Items
```

## Query Parameters

### $filter

```
GET /People?$filter=FirstName eq 'Scott' and Age gt 25
```

### $select

```
GET /People?$select=FirstName,LastName
```

### $orderby

```
GET /People?$orderby=LastName asc,FirstName desc
```

### $expand

```
GET /People?$expand=Trips
```

### $top and $skip

```
GET /People?$top=10&$skip=20
```

### $count

```
GET /People?$count=true
```

### Combined

```
GET /People?$filter=Age gt 25&$select=FirstName,LastName&$orderby=LastName asc&$top=10
```

## Key Rules

### Single-Key Entities

**Omit the key name:**

```
✓ People('scottketchum')
✗ People(UserName='scottketchum')
```

OData v4 convention: for single-key entities, the key name is implicit.

### Composite Keys

**Include key names:**

```
✓ OrderDetails(OrderId=1,ProductId=5)
✗ OrderDetails(1,5)
```

### URL Encoding

- Spaces → `%20`
- `$` → `%24` (in some contexts)
- Preserve: `'`, `(`, `)`, `,`, `=`

## Special Characters

### $ in URLs

The `$` prefix is part of OData query syntax:

```
GET /People?$filter=FirstName eq 'Scott'
GET /People?$count=true
```

### Quotes in Values

Use single quotes for string values:

```
GET /People?$filter=FirstName eq 'Scott'
```

### Parentheses in Keys

Use parentheses for key predicates:

```
GET /People('scottketchum')
GET /Trips(1)
```

## URL Building with ContextPath

```java
ContextPath path = ctx.basePath()
    .segment("People")
    .key("scottketchum")
    .segment("Trips");

// Produces: People('scottketchum')/Trips
```

### Key Segment Rules

- `addKey(value)` — adds to the last segment
- Single-key: omit name → `('value')`
- Composite-key: include names → `(Name1=value1,Name2=value2)`

## Batch Requests

### $batch Endpoint

```
POST /V4/TripPinService/$batch
```

### Request Format

```json
{
    "requests": [
        {"method": "GET", "url": "People('scottketchum')"},
        {"method": "GET", "url": "People('keithcombs')"}
    ]
}
```

## What's Next

- [Package Structure](packages.md) — Module organization
- [Contributing](../contributing.md) — How to contribute
