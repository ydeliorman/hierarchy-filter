# Senior Developer Assessment

This repo has both tasks:

- **Task 1: Hierarchy filter.** Code in `src/main/java/hierarchy`, tests in `src/test/java/hierarchy`. Details below.
- **Task 2: SimpleCache code review.** See [SimpleCache.md](SimpleCache.md).

# Task 1: Hierarchy filter

`HierarchyFilter.filter(hierarchy, predicate)` returns a new hierarchy that keeps a node
only if its ID passes the predicate **and** every ancestor passes it too.

## Approach

A hierarchy is a DFS-ordered array of node IDs with a parallel depth array. In DFS order
the descendants of a node are exactly the nodes that follow it with a larger depth. So the
filter is one left-to-right pass:

- When a node is rejected, remember its depth and skip every following node that is deeper.
- The first node with depth less than or equal to the remembered depth ends the skip.
- A kept node keeps its depth, because all of its ancestors were kept too, so nothing needs re-leveling.

Complexity: O(n) time, O(n) space for the result, no recursion (a 200,000 deep chain is
tested). The predicate runs at most once per node and never for nodes under a rejected ancestor.

## Assumptions

- The input follows the invariants in `Hierarchy` (first depth is 0, depth grows by at most 1).
  Inputs that break them are not validated; a mismatch between the two array lengths is rejected.
- The input is not modified; a new `ArrayBasedHierarchy` is returned.
- The predicate is pure (no side effects), since how often it is called is an implementation detail.

## Tests

`FilterTest` has the example from the task, plus: empty input, everything or nothing passing,
rejected root, a passing node under a rejected ancestor, siblings, skip ending at equal and at
lower depth, a forest of single roots, negative IDs, input not modified, predicate call count,
a very deep chain, and a randomized comparison against a naive reference on 500 random forests.

## Run

Java 21+ and Maven:

```
mvn test
```
