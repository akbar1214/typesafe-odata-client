# OData v4 — Knowledge Reference

A comprehensive reference of OData v4 concepts, URL patterns, query options, and edge cases learned during the development of this client library.

---

## 1. Metadata Document

The OData metadata document (`$metadata`) is a CSDL (Conceptual Schema Definition Language) XML file that describes the service's entity types, complex types, enums, functions, actions, and bindings.

**URL:** `GET /$metadata`

**Key namespaces:**
- OData v4: `http://docs.oasis-open.org/odata/ns/edmx`
- OData v4: `http://docs.oasis-open.org/odata/ns/datamodels`
- OData v3 (legacy): `http://schemas.microsoft.com/ado/2008/09/edm`

**TripPin metadata quirk:** Requires `curl -sL` (follow redirects) — the service uses session-based routing that returns 302 on first request.

---

## 2. Entity Types

Entity types define the structure of entities with named properties and optional key.

```xml
<EntityType Name="Person" BaseType="Microsoft.OData.ModelBase">
    <Key>
        <PropertyRef Name="UserName" />
    </Key>
    <Property Name="UserName" Type="Edm.String" Nullable="false" />
    <Property Name="FirstName" Type="Edm.String" />
    <Property Name="LastName" Type="Edm.String" />
    <Property Name="Emails" Type="Collection(Edm.String)" />
    <Property Name="Gender" Type="Microsoft.OData.SampleService.Models.TripPin.PersonGender" />
    <Property Name="Concurrency" Type="Edm.Int64" />
    <NavigationProperty Name="Trips" Type="Collection(Microsoft.OData.SampleService.Models.TripPin.Trip)" />
    <NavigationProperty Name="Friends" Type="Collection(Microsoft.OData.SampleService.Models.TripPin.Person)" />
    <NavigationProperty Name="Photo" Type="Microsoft.OData.SampleService.Models.TripPin.Photo" />
</EntityType>
```

**Key rules:**
- Every entity type must have a `<Key>` element (except open types)
- Key can be a single property or multiple properties (composite key)
- Navigation properties are defined separately from structural properties

---

## 3. Complex Types

Complex types are structured types without keys — used as property values (not entities).

```xml
<ComplexType Name="Location">
    <Property Name="Address" Type="Edm.String" />
    <Property Name="City" Type="Microsoft.OData.SampleService.Models.TripPin.City" />
</ComplexType>

<ComplexType Name="City" BaseType="Microsoft.OData.SampleService.Models.TripPin.City">
    <Property Name="Name" Type="Edm.String" />
    <Property Name="CountryRegion" Type="Edm.String" />
    <Property Name="Region" Type="Edm.String" />
</ComplexType>
```

**Edge case:** Complex types can have inheritance (`City` → `AirportLocation`). The subclass inherits all parent properties.

---

## 4. Enum Types

```xml
<EnumType Name="PersonGender">
    <Member Name="Male" Value="0" />
    <Member Name="Female" Value="1" />
    <Member Name="Unknown" Value="2" />
</EnumType>
```

**Note:** Enums are serialized as strings in JSON (e.g., `"Male"`), not integer values.

---

## 5. URL Patterns

### Entity Sets
```
GET /People                              # Get all people
GET /People('scottketchum')              # Get single entity by key
GET /People('scottketchum')/Trips        # Navigation property
GET /People('scottketchum')/Trips(0)     # Navigate + key
```

### Singleton
```
GET /Me                                  # Singleton entity
```

### Functions (built-in)
```
GET /People('scottketchum')/Trips/Default.GetTripsByRange(startDate=2014-01-01,endDate=2014-02-01)
```

### Actions
```
POST /People('scottketchum')/Microsoft.OData.SampleService.Models.TripPin.ShareTrip
Body: {
    "sharedTripId": 0
}
```

---

## 6. Query Options

### $filter
```
GET /People?$filter=FirstName eq 'Scott'
GET /People?$filter=Age gt 30 and LastName ne 'Smith'
GET /People?$filter=Emails?$count gt 0
GET /People?$filter=Trips?$count gt 2
```

**Operators:**
| Operator | Description | Example |
|----------|-------------|---------|
| `eq` | Equal | `FirstName eq 'Scott'` |
| `ne` | Not equal | `FirstName ne 'Scott'` |
| `gt` | Greater than | `Age gt 30` |
| `ge` | Greater than or equal | `Age ge 30` |
| `lt` | Less than | `Age lt 30` |
| `le` | Less than or equal | `Age le 30` |
| `and` | Logical and | `Age gt 30 and LastName eq 'Smith'` |
| `or` | Logical or | `FirstName eq 'Scott' or FirstName eq 'Keith'` |
| `not` | Logical not | `not endswith(Emails, 'example.com')` |
| `in` | Value in set | `Name in ('Scott', 'Keith')` |

**Functions:**
| Function | Description | Example |
|----------|-------------|---------|
| `contains()` | String contains | `contains(FirstName, 'ott')` |
| `startswith()` | String starts with | `startswith(FirstName, 'S')` |
| `endswith()` | String ends with | `endswith(Email, '@contoso.com')` |
| `length()` | String length | `length(FirstName) gt 3` |
| `tolower()` | Lowercase | `tolower(FirstName) eq 'scott'` |
| `toupper()` | Uppercase | `toupper(FirstName) eq 'SCOTT'` |
| `trim()` | Trim whitespace | `trim(FirstName) eq 'Scott'` |
| `concat()` | Concatenate | `concat(FirstName, ' ', LastName)` |
| `year()` | Extract year | `year(BirthDate) eq 1985` |
| `month()` | Extract month | `month(BirthDate) eq 10` |
| `day()` | Extract day | `day(BirthDate) eq 15` |
| `hour()` | Extract hour | `hour(BirthDate) eq 14` |
| `minute()` | Extract minute | `minute(BirthDate) eq 30` |
| `second()` | Extract second | `second(BirthDate) eq 45` |
| `round()` | Round number | `round(Rate) eq 10` |
| `floor()` | Floor number | `floor(Rate) eq 9` |
| `ceiling()` | Ceiling number | `ceiling(Rate) eq 10` |
| `isof()` | Type check | `isof(ShippingAddress, 'Northwind模型.Address')` |
| `cast()` | Type cast | `cast(ShippingAddress, 'Northwind模型.Address')` |

**Lambda operators:**
```
GET /People?$filter=Trips/any(t:t/Budget gt 500)
GET /People?$filter=Trips/all(t:t/Budget gt 100)
```

**Note:** `$expand` with nested `$filter` is different from filtering inside `$filter`:
```
# Correct: $expand with nested $filter
GET /People?$expand=Trips($filter=Budget gt 500)

# Incorrect: Can't use dot notation in $filter
GET /People?$filter=Trips/Budget gt 500  # WRONG - 400 error
```

### $select
```
GET /People?$select=FirstName,LastName
GET /People?$select=UserName,FirstName,LastName,Emails
```

**Rules:**
- Only specified properties are returned
- Navigation properties are not selectable (use $expand instead)
- Key properties are always included regardless of $select

### $orderby
```
GET /People?$orderby=LastName desc
GET /People?$orderby=LastName desc,FirstName asc
GET /People?$orderby=LastName asc,FirstName desc
```

### $expand
```
GET /People?$expand=Trips
GET /People?$expand=Trips,Friends
GET /People?$expand=Trips($expand=PlanItems)
GET /People?$expand=Trips($filter=Budget gt 500;$select=Name,Budget)
GET /People?$expand=Trips($orderby=Budget desc;$top=3)
```

### $top
```
GET /People?$top=10
GET /People?$top=5&$skip=10  # Pagination: page 2, 5 per page
```

### $skip
```
GET /People?$skip=10
GET /People?$top=5&$skip=10
```

### $count
```
GET /People?$count=true
GET /People?$count=true&$top=10
```

**Response:** Includes `@odata.count` at root level:
```json
{
    "value": [...],
    "@odata.count": 42
}
```

### $search
```
GET /People?$search=Scott
GET /People?$search=Scott Ketchum
GET /People?$search=Category:Office  # In some implementations
```

### $format
```
GET /People?$format=json
GET /People?$format=application/json
GET /People?$format=atom     # XML
```

### $compute
```
GET /People?$compute=HourRate mul HoursPerWeek as WeeklyRate
```

---

## 7. Response Format

### Collection Response
```json
{
    "odata.context": "https://services.odata.org/V4/TripPinService/$metadata#People",
    "value": [
        {
            "UserName": "scottketchum",
            "FirstName": "Scott",
            "LastName": "Ketchum",
            "Emails": ["scott@example.com"],
            "Gender": "Male",
            "Concurrency": 635517363200320000
        }
    ],
    "@odata.nextLink": "https://services.odata.org/V4/TripPinService/People?$skip=10&$top=10"
}
```

### Single Entity Response
```json
{
    "odata.context": "https://services.odata.org/V4/TripPinService/$metadata#People/$entity",
    "UserName": "scottketchum",
    "FirstName": "Scott",
    "LastName": "Ketchum",
    "Emails": ["scott@example.com"],
    "Gender": "Male",
    "Concurrency": 635517363200320000
}
```

### Error Response
```json
{
    "error": {
        "code": "InternalServerError",
        "message": "The request URI is not valid.",
        "innererror": {
            "message": "The segment 'People' refers to a collection...",
            "type": "Microsoft.OData.Core.UriParser.ODataException",
            "stacktrace": "..."
        }
    }
}
```

---

## 8. Key Formats

### Single Key
```
GET /People('scottketchum')
GET /Airlines('AA')
GET /Airports('LAX')
```

**Note:** Key name is omitted for single keys — this is conventional in OData v4.

### Composite Key
```
GET /OrderDetails(OrderId=1,ProductId=5)
GET /OrderDetails(OrderId=1, ProductId=5)  # Spaces allowed
```

### Key with Special Characters
```
GET /People('keith%20combs')  # URL-encoded spaces
GET /People('don''t')         # Single quote escaped as two single quotes
```

---

## 9. Navigation Properties

### Collection Navigation
```
GET /People('scottketchum')/Trips
GET /People('scottketchum')/Friends
```

### Single Navigation
```
GET /People('scottketchum')/Photo
GET /People('scottketchum')/BestFriend
```

### $ref — Create/Remove Links
```
# Add friend
POST /People('scottketchum')/Friends/$ref
Body: {"@odata.id": "People('keithcombs')"}

# Remove friend
DELETE /People('scottketchum')/Friends('keithcombs')/$ref

# Remove all friends (some services)
DELETE /People('scottketchum')/Friends/$ref
```

### $ref — Get References
```
GET /People('scottketchum')/Friends/$ref
# Returns array of {"@odata.id": "People('keithcombs')"}
```

---

## 10. CRUD Operations

### GET (Read)
```
GET /People('scottketchum')
```

### POST (Create)
```
POST /People
Body: {
    "UserName": "newuser",
    "FirstName": "New",
    "LastName": "User"
}
```

### PATCH (Update — partial)
```
PATCH /People('scottketchum')
If-Match: "W/"12345678""
Body: {
    "FirstName": "Updated"
}
```

### PUT (Update — full replacement)
```
PUT /People('scottketchum')
Body: {
    "UserName": "scottketchum",
    "FirstName": "Updated",
    "LastName": "Ketchum",
    "Emails": ["scott@example.com"],
    "Gender": "Male"
}
```

### DELETE
```
DELETE /People('scottketchum')
If-Match: W/"12345678"
```

> **Note:** Many OData services (including TripPin) require `If-Match` for DELETE operations. GET the entity first to obtain the ETag, then include it in the DELETE request. Without it, the server returns HTTP 428 (Precondition Required).

---

## 11. ETag / Optimistic Concurrency

OData supports optimistic concurrency via ETags:

```http
GET /People('scottketchum')
HTTP/1.1 200 OK
ETag: W/"12345678"
```

When updating, include the ETag:
```http
PATCH /People('scottketchum')
If-Match: W/"12345678"
Content-Type: application/json
Body: {"FirstName": "Updated"}
```

If the ETag doesn't match (someone else modified the resource):
```http
HTTP/1.1 412 Precondition Failed
```

**OData annotation:** `@odata.etag` in JSON response:
```json
{
    "UserName": "scottketchum",
    "FirstName": "Scott",
    "@odata.etag": "W/\"12345678\""
}
```

---

## 12. Batch Requests (`$batch`)

OData supports batching multiple operations in a single HTTP request:

```http
POST /$batch
Content-Type: multipart/mixed; boundary=batch_boundary

--batch_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

GET /People('scottketchum') HTTP/1.1
Host: services.odata.org

--batch_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

PATCH /People('scottketchum') HTTP/1.1
Content-Type: application/json
If-Match: W/"12345678"

{"FirstName": "Updated"}
--batch_boundary--
```

**Response:**
```
--batch_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

HTTP/1.1 200 OK
Content-Type: application/json

{"UserName": "scottketchum", "FirstName": "Scott"}
--batch_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

HTTP/1.1 204 No Content
--batch_boundary--
```

---

## 13. OData Annotations

Annotations provide metadata about entities, properties, or the response:

```json
{
    "odata.context": "...",           // Metadata context URL
    "odata.etag": "W/\"123\"",       // Entity ETag
    "odata.nextLink": "...",         // Next page URL
    "@odata.count": 42,              // Total count
    "@odata.bind": "...",            // Entity binding
    "@odata.type": "#Namespace.Type" // Type discriminator
}
```

---

## 14. HTTP Headers

### Required Headers
```http
OData-MaxVersion: 4.0    # Maximum OData version the client supports
OData-Version: 4.0       # OData version of the response
```

### Request Headers
```http
Accept: application/json           # Response format
Content-Type: application/json     # Request body format
If-Match: W/"123"                 # Optimistic concurrency
If-None-Match: W/"123"            # Conditional GET (304 Not Modified)
Prefer: return=representation      # Return updated entity after PATCH
Prefer: return=minimal             # Return empty body after PATCH (default)
```

### Response Headers
```http
ETag: W/"123"                     // Entity version tag
Content-Type: application/json     // Response format
Preference-Applied: return=representation  // Applied preference
```

---

## 15. TripPin Service Reference

The [TripPin](https://services.odata.org/V4/TripPinService) sample service is an OData v4 reference implementation.

**Key endpoints:**
| Endpoint | Description |
|----------|-------------|
| `GET /People` | All people (8 entries) |
| `GET /People('scottketchum')` | Scott Ketchum |
| `GET /People('scottketchum')/Trips` | Scott's trips |
| `GET /Airlines` | All airlines |
| `GET /Airports` | All airports |
| `GET /Me` | Current user (singleton) |

**Key people:**
| UserName | FirstName | LastName |
|----------|-----------|----------|
| `scottketchum` | Scott | Ketchum |
| `keithcombs` | Keith | Combs |
| `louissons` | Louis | Sons |
| `johanpelletier` | Johan | Pelletier |
| `mirsk` | Mirsk | Smith |
| `sprattley` | Sprat | Tley |
| `aprilcline` | April | Cline |
| `davideboling` | David | Boling |

**Quirks:**
- Returns 500 for navigation URLs like `People/Trips('scott')` (key must be on `People`, not `Trips`)
- Requires `OData-MaxVersion: 4.0` and `OData-Version: 4.0` headers
- Uses session-based redirects for metadata
- Supports `$filter`, `$select`, `$orderby`, `$expand`, `$top`, `$skip`, `$count`, `$search`

---

## 16. CSDL Parsing Edge Cases

### Self-Closing Tags
```xml
<!-- Self-closing (valid XML) -->
<Property Name="UserName" Type="Edm.String" />

<!-- With explicit closing tag (also valid) -->
<Property Name="UserName" Type="Edm.String"></Property>
```

Both forms must be handled by the parser.

### Namespace Variations
```xml
<!-- OData v4 namespaces -->
<edmx:Edmx Version="4.0">
    <edmx:DataServices>
        <Schema Namespace="..." xmlns="http://docs.oasis-open.org/odata/ns/edm">

<!-- OData v3 namespaces (legacy) -->
<edmx:Edmx Version="1.0">
    <edmx:DataServices m:DataServiceVersion="3.0">
        <Schema Namespace="..." xmlns:m="http://schemas.microsoft.com/ado/2007/08/dataservices/metadata">
```

### Inheritance
```xml
<EntityType Name="PublicTransportation" BaseType="Microsoft.OData.SampleService.Models.TripPin.Vehicle" />
```

Inheritance is flat in CSDL — no nested hierarchy. The parser must resolve base types by name.

### Annotations
```xml
<Annotations Target="Microsoft.OData.SampleService.Models.TripPin.Person/FirstName">
    <Annotation Term="...Description" String="The first name of the person" />
</Annotations>
```

Annotations are optional and can be ignored for code generation (but useful for documentation).

---

## 17. Common Pitfalls

1. **Navigation URL errors:** `People/Trips('scott')` returns 500 — key must be on the correct segment.
2. **Missing OData headers:** Services may return 406 or 500 without `OData-MaxVersion` and `OData-Version`.
3. **URL encoding spaces:** `$filter=FirstName eq 'Scott'` must be `$filter=FirstName%20eq%20'Scott'`.
4. **Key name for single keys:** `People(UserName='scott')` may fail — use `People('scott')` instead.
5. **`$expand` vs `$filter` nesting:** Can't filter on expanded nav properties in `$filter` — use nested `$filter` in `$expand`.
6. **`@odata.count` location:** Count is at the response root, not inside each item.
7. **Complex type inheritance:** Subclass constructors must call `super()` with parent properties.
8. **Open types:** Some entities allow unmapped properties (`UnmappedFields`).
