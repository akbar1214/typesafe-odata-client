# Expand Navigation Properties

Include related entities in your query results with `$expand`.

## Basic Expand

### Expand a Navigation Property

```java
client.people()
    .expand(Person.TRIPS)
    .get();
```

Each `Person` will have its `trips` field populated.

### Expand Multiple Properties

```java
client.people()
    .expand(Person.TRIPS, Person.PHOTOS)
    .get();
```

## Nested Expand

### Expand with Sub-Expansion

```java
client.people()
    .expand(Person.TRIPS, trip -> trip.expand(Trip.ITEMS))
    .get();
```

Expands trips and each trip's items.

### Deep Nesting

```java
client.people()
    .expand(Person.TRIPS, trip ->
        trip.expand(Trip.ITEMS)
    )
    .expand(Person.PHOTOS)
    .get();
```

## Filter Expanded Results

### Filter within Expand

```java
client.people()
    .expand(Person.TRIPS, trip ->
        trip.filter(Trip.BUDGET.greaterThan(500.0f))
    )
    .get();
```

Only trips with budget > 500 are included in the expansion.

### Expand with Select

```java
client.people()
    .expand(Person.TRIPS, trip ->
        trip.select(Trip.TRIP_ID, Trip.BUDGET)
    )
    .get();
```

Only trip ID and budget are included.

### Expand with Order

```java
client.people()
    .expand(Person.TRIPS, trip ->
        trip.orderBy(Trip.STARTS_AT.desc())
    )
    .get();
```

### Combine Filter, Select, Order

```java
client.people()
    .expand(Person.TRIPS, trip ->
        trip.filter(Trip.BUDGET.greaterThan(500.0f))
            .select(Trip.TRIP_ID, Trip.BUDGET)
            .orderBy(Trip.STARTS_AT.desc())
            .top(5)
    )
    .get();
```

## Expand on Entity Requests

```java
// Expand when getting a single entity
client.peopleByUserName("scottketchum")
    .expand(Person.TRIPS)
    .get();
```

## What's Next

- [Use Pagination](pagination.md) — Handle large result sets
- [Perform CRUD Operations](crud.md) — Create, update, delete
