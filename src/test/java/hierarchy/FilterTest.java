package hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class FilterTest {

    private static Hierarchy h(int[] ids, int[] depths) {
        return new ArrayBasedHierarchy(ids, depths);
    }

    private static void check(Hierarchy input, IntPredicate p, int[] expIds, int[] expDepths) {
        assertEquals(h(expIds, expDepths).formatString(), HierarchyFilter.filter(input, p).formatString());
    }

    @Test
    void testFilter() { // the example from the task
        Hierarchy unfiltered = h(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11},
                                 new int[]{0, 1, 2, 3, 1, 0, 1, 0, 1, 1, 2});
        check(unfiltered, id -> id % 3 != 0, new int[]{1, 2, 5, 8, 10, 11}, new int[]{0, 1, 1, 0, 1, 2});
    }

    @Test
    void emptyHierarchy() {
        check(h(new int[0], new int[0]), id -> true, new int[0], new int[0]);
    }

    @Test
    void everythingPasses() {
        Hierarchy in = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 1, 0});
        check(in, id -> true, new int[]{1, 2, 3, 4}, new int[]{0, 1, 1, 0});
    }

    @Test
    void nothingPasses() {
        check(h(new int[]{1, 2, 3}, new int[]{0, 1, 0}), id -> false, new int[0], new int[0]);
    }

    @Test
    void rejectedRootRemovesWholeTreeOnly() {
        // 1(2(3)) 4(5): reject 1, tree 4 stays
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 2, 0, 1});
        check(in, id -> id != 1, new int[]{4, 5}, new int[]{0, 1});
    }

    @Test
    void passingDescendantOfRejectedAncestorIsDropped() {
        // chain 1-2-3-4, reject 2: 3 and 4 pass the predicate but must go
        Hierarchy in = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 2, 3});
        check(in, id -> id != 2, new int[]{1}, new int[]{0});
    }

    @Test
    void rejectedNodeDoesNotAffectItsSiblings() {
        // 1 with children 2,3,4; reject 3
        Hierarchy in = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 1, 1});
        check(in, id -> id != 3, new int[]{1, 2, 4}, new int[]{0, 1, 1});
    }

    @Test
    void skipEndsWhenDepthReturnsToRejectedLevelOrLess() {
        // 1(2(3, 4(5)), 6) reject 2: 3,4,5 gone, 6 stays (depth 1 == rejected depth)
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5, 6}, new int[]{0, 1, 2, 2, 3, 1});
        check(in, id -> id != 2, new int[]{1, 6}, new int[]{0, 1});
    }

    @Test
    void skipEndsWhenDepthDropsBelowRejectedLevel() {
        // 1(2(3(4))) 5: reject 3, then 5 is a new root
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 2, 3, 0});
        check(in, id -> id != 3, new int[]{1, 2, 5}, new int[]{0, 1, 0});
    }

    @Test
    void consecutiveRejectedSiblings() {
        Hierarchy in = h(new int[]{1, 2, 3, 4, 5}, new int[]{0, 1, 1, 1, 1});
        check(in, id -> id == 1 || id == 5, new int[]{1, 5}, new int[]{0, 1});
    }

    @Test
    void forestOfSingleRoots() {
        Hierarchy in = h(new int[]{1, 2, 3, 4}, new int[]{0, 0, 0, 0});
        check(in, id -> id % 2 == 0, new int[]{2, 4}, new int[]{0, 0});
    }

    @Test
    void negativeAndZeroIds() {
        Hierarchy in = h(new int[]{-1, 0, 5}, new int[]{0, 1, 1});
        check(in, id -> id >= 0 || id == -1, new int[]{-1, 0, 5}, new int[]{0, 1, 1});
    }

    @Test
    void doesNotModifyInput() {
        Hierarchy in = h(new int[]{1, 2, 3}, new int[]{0, 1, 2});
        String before = in.formatString();
        HierarchyFilter.filter(in, id -> id != 2);
        assertEquals(before, in.formatString());
    }

    @Test
    void predicateIsNotCalledForNodesUnderRejectedAncestor() {
        Hierarchy in = h(new int[]{1, 2, 3, 4}, new int[]{0, 1, 2, 3});
        AtomicInteger calls = new AtomicInteger();
        HierarchyFilter.filter(in, id -> { calls.incrementAndGet(); return id != 2; });
        assertEquals(2, calls.get()); // only 1 and 2 are tested
    }

    @Test
    void deepChainDoesNotOverflowStack() {
        int n = 200_000;
        int[] ids = new int[n], depths = new int[n];
        for (int i = 0; i < n; i++) { ids[i] = i; depths[i] = i; }
        Hierarchy out = HierarchyFilter.filter(h(ids, depths), id -> id != 150_000);
        assertEquals(150_000, out.size());
    }

    @Test
    void randomizedAgainstNaiveReference() {
        Random rnd = new Random(42);
        for (int round = 0; round < 500; round++) {
            int n = 1 + rnd.nextInt(60);
            int[] ids = new int[n], depths = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = i;
                depths[i] = i == 0 ? 0 : rnd.nextInt(depths[i - 1] + 2); // valid DFS depths
            }
            boolean[] pass = new boolean[n];
            for (int i = 0; i < n; i++) pass[i] = rnd.nextInt(4) != 0;
            Hierarchy in = h(ids, depths);
            assertEquals(naive(in, pass), HierarchyFilter.filter(in, id -> pass[id]).formatString());
        }
    }

    /** Reference: walk up to find each node's ancestors explicitly. */
    private static String naive(Hierarchy in, boolean[] pass) {
        List<Integer> keepIds = new ArrayList<>(), keepDepths = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            boolean ok = pass[in.nodeId(i)];
            int need = in.depth(i) - 1;
            for (int j = i - 1; ok && j >= 0 && need >= 0; j--) {
                if (in.depth(j) == need) {
                    ok = pass[in.nodeId(j)];
                    need--;
                }
            }
            if (ok) { keepIds.add(in.nodeId(i)); keepDepths.add(in.depth(i)); }
        }
        return h(keepIds.stream().mapToInt(x -> x).toArray(), keepDepths.stream().mapToInt(x -> x).toArray()).formatString();
    }
}
