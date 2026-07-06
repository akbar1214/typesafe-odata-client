# Use Pagination

Handle large result sets with server-driven paging.

## Skip and Top

### Manual Pagination

```java
// First page
CollectionPage<Person> page1 = client.people()
    .top(10)
    .skip(0)
    .get();

// Second page
CollectionPage<Person> page2 = client.people()
    .top(10)
    .skip(10)
    .get();

// Third page
CollectionPage<Person> page3 = client.people()
    .top(10)
    .skip(20)
    .get();
```

## Server-Driven Paging

### Check for Next Page

```java
CollectionPage<Person> page = client.people()
    .top(10)
    .get();

// Iterate current page
for (Person person : page) {
    System.out.println(person.getFirstName());
}

// Check if more pages exist
Optional<String> nextLink = page.getNextLink();
if (nextLink.isPresent()) {
    // Fetch next page using the OData next link
    CollectionPage<Person> nextPage = client.people()
        .nextPage(nextLink.get())
        .get();
}
```

### Automatic Pagination

```java
// Iterate all results across pages
CollectionPage<Person> page = client.people()
    .top(10)
    .get();

while (true) {
    for (Person person : page) {
        System.out.println(person.getFirstName());
    }

    Optional<String> nextLink = page.getNextLink();
    if (nextLink.isEmpty()) {
        break;
    }
    page = client.people().nextPage(nextLink.get()).get();
}
```

## Count Results

### Get Total Count

```java
CollectionPage<Person> people = client.people()
    .count()
    .get();

Optional<Long> totalCount = people.count();
System.out.println("Total: " + totalCount.orElse(0L));
```

This executes a `GET /People/$count` and returns the total.

### Count with Filter

```java
CollectionPage<Person> people = client.people()
    .filter(Person.AGE.greaterThan(25))
    .count()
    .get();

Optional<Long> totalAdults = people.count();
```

## Best Practices

- Use `$top` to limit result size
- Use `$count` when building pagination UIs
- Store the `nextLink` for cursor-based pagination
- Avoid large `$skip` values — they're inefficient on the server

## What's Next

- [Perform CRUD Operations](crud.md) — Create, update, delete
- [Handle ETags and Concurrency](etag.md) — Optimistic concurrency
