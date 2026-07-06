# Manage Navigation Links ($ref)

Create and remove entity relationships using `$ref`.

## What is $ref?

In OData, relationships between entities are managed through navigation links. `$ref` is the reference to an entity.

## Add a Relationship

### Add a Friend

```java
// Add a friend link
client.peopleByUserName("scottketchum")
    .friends()
    .addRef(Person.builder()
        .userName("keithcombs")
        .build());
```

This creates a link between Scott and Keith as friends.

### Add Multiple Friends

```java
client.peopleByUserName("scottketchum")
    .friends()
    .addRef(Person.builder().userName("keithcombs").build());

client.peopleByUserName("scottketchum")
    .friends()
    .addRef(Person.builder().userName("louissons").build());
```

## Remove a Relationship

### Remove a Friend

```java
client.peopleByUserName("scottketchum")
    .friends()
    .removeRef(Person.builder().userName("keithcombs").build());
```

This removes the link but doesn't delete the entity.

## Reset a Relationship

```java
// Replace all friends with a new list
client.peopleByUserName("scottketchum")
    .friends()
    .setRef(List.of(
        Person.builder().userName("keithcombs").build(),
        Person.builder().userName("louissons").build()
    ));
```

## How It Works

### Add a Trip to a Person

```java
Trip newTrip = Trip.builder()
    .tripId(1001L)
    .name("Business Trip")
    .budget(1500.0f)
    .build();

client.peopleByUserName("scottketchum")
    .trips()
    .addRef(newTrip);
```

This creates a navigation link from the person to the trip.

## What's Next

- [Add Authentication](auth.md) — OAuth2, API keys, custom auth
- [Use Custom HTTP Transport](custom-transport.md) — OkHttp, Apache, or your own
