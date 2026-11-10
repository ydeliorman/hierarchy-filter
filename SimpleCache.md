# Task 2: SimpleCache code review

Context: thousands of reads/sec, hundreds of writes/sec, tens of concurrent threads.

Using `ConcurrentHashMap` and an immutable `CacheEntry` (final fields) is a good start. Single `get`/`put`
calls are thread-safe and entries are safely published. The problems are mostly about **lifecycle,
memory, and behavior under load**. The list goes from most to least severe.

<details>
<summary>Code under review</summary>

```java
public class SimpleCache<K, V> {
    private final ConcurrentHashMap<K, CacheEntry<V>> cache = new ConcurrentHashMap<>();
    private final long ttlMs = 60000; // 1 minute

    public static class CacheEntry<V> { /* final value, final timestamp, getters */ }

    public void put(K key, V value) {
        cache.put(key, new CacheEntry<>(value, System.currentTimeMillis()));
    }

    public V get(K key) {
        CacheEntry<V> entry = cache.get(key);
        if (entry != null) {
            if (System.currentTimeMillis() - entry.getTimestamp() < ttlMs) {
                return entry.getValue();
            }
        }
        return null;
    }

    public int size() { return cache.size(); }
}
```
</details>

## Issues

### 1. Expired entries are never removed (memory leak)
`get()` only *hides* an expired entry. The entry stays in the map until the same key is written again.
Keys that are written once and never again (session IDs, request IDs, user IDs that churn) stay forever.
With hundreds of writes/sec, that is millions of dead entries per day.

**Impact:** heap keeps growing, GC pauses get longer, and the service ends with `OutOfMemoryError`.
The TTL gives a false sense that memory is bounded.

**Fix:** remove expired entries. Do it lazily in `get()` with a conditional remove, and also with a
periodic sweep (for keys that are never read again), or use a library that does both.

### 2. No size limit and no eviction policy
Even with expiry, the cache can hold "all keys written in the last 60 seconds". A traffic spike, a
key with high cardinality, or a bad caller (for example, a key built from user input) can fill the heap
inside one TTL window.

**Impact:** OOM or long GC pauses under load spikes. That is when the cache matters most.

**Fix:** add a `maximumSize` (or weight) limit with an eviction policy (LRU / W-TinyLFU).

### 3. Cache stampede (thundering herd) on miss or expiry
The API is `get` and then the caller does `put`: check, then act, with nothing in between.
When a hot key expires, every thread that reads it in that moment sees `null` and goes to the
database/service at the same time. With thousands of reads/sec, one hot key expiring can send
hundreds of identical requests to the backend. All of them then `put`, last write wins.

**Impact:** load spikes on the downstream system every 60 seconds for each hot key, higher latency,
and possible cascading failure exactly when the backend is slow.

**Fix:** offer an atomic `get(key, loader)` that makes sure only one thread loads a key and the others
wait for its result (single-flight). Also consider refresh-ahead (`refreshAfterWrite`): serve the old
value while one background thread reloads it.

### 4. Removing expired entries naively would add a race
If someone fixes issue 1 with `cache.remove(key)` inside `get()`, this can happen: thread A reads an
expired entry, thread B does `put(key, fresh)`, then thread A calls `remove(key)` and deletes the
**fresh** value.

**Impact:** fresh writes disappear, so there are extra misses and extra backend calls.

**Fix:** use the conditional remove, which only removes that exact entry:
```java
if (expired) cache.remove(key, entry);
```

### 5. Wall clock time (`System.currentTimeMillis()`) is used to measure a duration
`currentTimeMillis()` follows the system clock, which NTP, manual changes, or VM migration can move.
- Clock jumps **backward**: `now - timestamp` gets small or negative, so the entry looks fresh for much
  longer than 60s and serves stale data.
- Clock jumps **forward**: the whole cache expires at once, which triggers a stampede (issue 3) for every key.

**Fix:** use `System.nanoTime()`, which is monotonic, for elapsed time. Better: inject a clock
(`LongSupplier` / `Ticker`) so tests can control time instead of using `Thread.sleep(60_000)`.

### 6. All entries can expire at the same moment
Every entry gets the same fixed TTL. After a deploy, a restart, or a bulk warm up, many entries are
written in the same second, so they all expire in the same second 60 seconds later.

**Impact:** periodic miss storms and backend load spikes, together with issue 3.

**Fix:** add a small random jitter to the TTL (for example ±10%), or refresh ahead.

### 7. `null` handling: NPE on put, and no way to cache "not found"
- `ConcurrentHashMap` does not accept `null` keys. `get(null)` and `put(null, v)` throw `NullPointerException`.
- `put(key, null)` does **not** throw. It stores an entry with a null value. Then `get()` returns `null`
  for three different cases: "missing", "expired", and "cached null".
- So callers cannot cache a negative result (for example "user does not exist"). Repeated lookups of
  missing keys always go to the backend. This is a common way to overload a database (cache penetration).

**Fix:** validate arguments with `Objects.requireNonNull`, document the contract, and return
`Optional<V>` or use a sentinel value if negative caching is needed.

### 8. `size()` is misleading
It counts expired entries that are still in the map (issue 1). Also, `ConcurrentHashMap.size()` is only
an estimate while other threads are writing.

**Impact:** metrics, dashboards and capacity decisions built on `size()` are wrong. The number grows
even when the live data does not.

**Fix:** document it as approximate, and report live size, hit/miss and eviction counts as separate metrics.

### 9. No invalidation API
There is no `remove(key)`, `invalidate`, or `clear()`. When the source data changes, callers have to
wait up to 60s, and there is no way to clear a bad value in an incident.

**Impact:** stale or wrong data is served for up to a full TTL, and you cannot fix it quickly in production.

### 10. TTL is hard coded and is an instance field
`private final long ttlMs = 60000;` cannot be configured, cannot be different per cache or per entry, and
cannot be changed in tests. It should be a constructor parameter (`Duration ttl`) that is checked to be
positive. If it really is a constant, it should be `static final`.

### 11. Cached values can be mutable and shared
`CacheEntry` is immutable, but `V` may not be. Every caller gets the **same instance**. If one thread
changes it (for example a `List` or a DTO with setters), other threads see that change, possibly
half done, because nothing synchronizes it.

**Impact:** data races and hard-to-reproduce corruption that look like cache bugs.

**Fix:** store immutable values (records, `List.copyOf`), or copy defensively, and document this.

### 12. `CacheEntry` is part of the public API
`public static class CacheEntry` exposes an internal detail. Callers can depend on it, which makes
later changes (for example a per-entry expiry time instead of a write timestamp) a breaking change.
It should be a `private record`.

### 13. No observability
There are no hit/miss/load/eviction counters and no load latency. In production you cannot tell if
the cache helps, if the TTL is right, or if the stampede in issue 3 is happening.

## Recommendation

Do not build a production cache by hand unless there is a strong reason. Use **Caffeine**. It fixes
issues 1-9 and 13 with a few lines: bounded size with W-TinyLFU eviction, removal of expired entries,
single-flight loading, refresh ahead, a monotonic `Ticker` you can inject in tests, and statistics.

```java
LoadingCache<K, V> cache = Caffeine.newBuilder()
        .maximumSize(100_000)                       // issue 2
        .expireAfterWrite(Duration.ofMinutes(1))    // issues 1, 5 (expired entries removed, nanoTime ticker)
        .refreshAfterWrite(Duration.ofSeconds(50))  // issues 3, 6 (serve old value while one thread reloads)
        .recordStats()                              // issues 8, 13
        .build(key -> loadFromSource(key));         // issue 3 (one load per key at a time)

V value = cache.get(key);   // cache.invalidate(key) for issue 9
```

If a custom version is required, the minimum changes are:
1. `System.nanoTime()` (or an injected ticker) for expiry.
2. `cache.remove(key, entry)` for expired entries in `get()`, plus a scheduled sweep.
3. A maximum size with eviction.
4. `get(key, loader)` with one load per key, for example a `ConcurrentHashMap<K, CompletableFuture<V>>`
   with `computeIfAbsent`. Do not run the slow loader inside `compute`, because that blocks other keys
   in the same bin.
5. Null checks, a configurable TTL with jitter, `invalidate`/`clear`, and metrics.
