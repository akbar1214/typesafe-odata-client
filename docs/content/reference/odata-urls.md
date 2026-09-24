# OData URL Patterns

OData Codegen builds request URLs with `ContextPath`. Path segments, key predicates, and query options are kept separate and rendered in a stable order.

## Resource and Navigation URLs

```text
{baseUrl}/{entitySet}
{baseUrl}/{entitySet}({key})
{baseUrl}/{entitySet}({key})/{navigation}
{baseUrl}/{entitySet}({key})/{navigation}({key})
```

Examples:

```text
GET /V4/TripPinService/People
GET /V4/TripPinService/People('scottketchum')
GET /V4/TripPinService/People('scottketchum')/Trips
GET /V4/TripPinService/People('scottketchum')/Trips(1)
```

## Query Options

Generated collection requests can render:

```text
?$filter=FirstName eq 'Scott'
?$select=FirstName,LastName
?$orderby=LastName desc,FirstName asc
?$expand=Trips($select=Name;$top=5)
?$top=10&$skip=20
?$count=true
?$search=bread
?$apply=groupby((Category))/aggregate(Price with sum as Total)
```

Queries are collected from all path segments and rendered once at the end, after the complete resource path. This matters when a next-link query is followed by another segment such as `$ref`.

## Key Rules

A single-key entity uses the nameless form:

```text
People('scottketchum')
```

A composite key includes each property name:

```text
OrderDetails(OrderId=1,ProductId=5)
```

Generated accessors call the typed form:

```java
ContextPath path = ctx.basePath()
    .addSegment("People")
    .addKey("UserName", "scottketchum", "Edm.String")
    .addSegment("Trips");
```

The Edm type controls the literal:

| Edm type | Rendering |
|----------|-----------|
| `Edm.String` | Always single-quoted, including UUID-shaped text |
| `Edm.Guid` | Bare validated 8-4-4-4-12 value |
| `Edm.Date` / `Edm.DateTimeOffset` | Bare ISO value |
| `Edm.TimeOfDay` | `HH:mm:ss` value |
| `Edm.Duration` | `duration'...'` value |
| Enum type | Qualified `Namespace.Enum'Member'` value |

The two-argument `addKey(name, value)` overload is retained for direct runtime callers. Generated keyed request methods use the Edm-typed overload.

## URL Encoding

Spaces are encoded as `%20`. OData-safe characters restored in query values include `$`, `'`, `(`, `)`, `,`, `/`, `:`, and `@`.

The query parameter separator remains a literal `=`:

```text
?$filter=Name eq 'a%3Db'
```

A second `=` inside a query value is encoded as `%3D`; restoring it would make the value look like another query parameter. String key values also encode path-sensitive `/` and `+` characters (`%2F` and `%2B`).

## Next Links

`ContextPath.fromNextLink(...)` accepts absolute or service-root-relative links, splits the query string on `&` only, and percent-decodes values without treating `+` as a space. A link containing nested semicolon-separated expand options therefore remains one query option.

## Batch URL

The runtime posts a `multipart/mixed` request to:

```text
POST /V4/TripPinService/$batch
```

It does not send the JSON batch request shape. See [Batch API](batch-api.md) for the multipart framing and response correlation contract.

## What's Next

- [Package Structure](packages.md)
- [Batch API](batch-api.md)
- [Contributing](../contributing.md)
