# OData URL Patterns

How OData Codegen builds OData URLs.

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
    .addSegment("People")
    .addKey("UserName", "scottketchum")
    .addSegment("Trips");

// Produces: People('scottketchum')/Trips
```

### Key Segment Rules

- `addKey(name, value)` — adds to the last segment; single keys render nameless,
  composite keys render `(Name1=value1,Name2=value2)`
- `addKey(name, value, edmType)` — the typed form generated code uses; the literal is
  formatted from the Edm type instead of guessed from the value:

| Edm type | Literal | Example |
|---|---|---|
| `Edm.String` | always quoted (even UUID-shaped) | `People('0c5a…')` |
| `Edm.Guid` | bare 8-4-4-4-12 | `Advertisements(0c5a…)` |
| `Edm.Date` / `Edm.DateTimeOffset` | bare ISO | `Events(2024-01-01T10:00Z)` |
| `Edm.TimeOfDay` | `HH:mm:ss` | `Shifts(10:15:00)` |
| `Edm.Duration` | `duration'...'` | `Waits(duration'PT2H')` |
| Enum type | qualified | `Things(Ns.Color'Red')` |

### Query Parameters

Query options are collected across all segments and rendered once, after the whole
path — chaining options onto a `nextPage(...)` link produces a single valid `?...`
(never a double `?`).

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
