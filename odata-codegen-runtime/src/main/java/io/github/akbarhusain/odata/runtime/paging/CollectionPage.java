package io.github.akbarhusain.odata.runtime.paging;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class CollectionPage<T> implements Iterable<T> {
    private final List<T> currentPage;
    private final String nextLink;
    private final Long count;

    public CollectionPage(List<T> currentPage, String nextLink) {
        this(currentPage, nextLink, null);
    }

    public CollectionPage(List<T> currentPage, String nextLink, Long count) {
        this.currentPage = Collections.unmodifiableList(currentPage);
        this.nextLink = nextLink;
        this.count = count;
    }

    public List<T> currentPage() {
        return currentPage;
    }

    public boolean hasNextPage() {
        return nextLink != null && !nextLink.isEmpty();
    }

    public String getNextLink() {
        return nextLink;
    }

    /**
     * Returns the total count if $count was requested.
     */
    public Optional<Long> count() {
        return Optional.ofNullable(count);
    }

    public Stream<T> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    public List<T> toList() {
        return currentPage;
    }

    @Override
    public Iterator<T> iterator() {
        return currentPage.iterator();
    }

    @Override
    public Spliterator<T> spliterator() {
        return Spliterators.spliterator(currentPage.iterator(), currentPage.size(),
                Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL);
    }
}
