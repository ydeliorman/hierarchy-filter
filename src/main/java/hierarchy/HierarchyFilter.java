package hierarchy;

import java.util.function.IntPredicate;

public final class HierarchyFilter {
    private HierarchyFilter() {}

    /**
     * Returns a hierarchy that keeps a node iff its ID passes the predicate and all of its
     * ancestors pass it as well. Depths of kept nodes are unchanged: a kept node's ancestors
     * are all kept, so no re-leveling is needed.
     *
     * <p>Single pass, O(n) time, O(n) space for the result, no recursion. The predicate is
     * called at most once per node, and never for a node under a rejected ancestor.
     *
     * <p>Idea: when a node is rejected, every following node that is deeper than it is its
     * descendant (DFS order), so we skip until the depth comes back to that level or less.
     */
    public static Hierarchy filter(Hierarchy hierarchy, IntPredicate nodeIdPredicate) {
        int n = hierarchy.size();
        int[] ids = new int[n];
        int[] depths = new int[n];
        int kept = 0;
        int rejectedDepth = -1; // depth of the rejected node whose subtree we are skipping, -1 if none

        for (int i = 0; i < n; i++) {
            int depth = hierarchy.depth(i);
            if (rejectedDepth >= 0 && depth > rejectedDepth) {
                continue; // descendant of a rejected node
            }
            rejectedDepth = -1;
            int id = hierarchy.nodeId(i);
            if (nodeIdPredicate.test(id)) {
                ids[kept] = id;
                depths[kept] = depth;
                kept++;
            } else {
                rejectedDepth = depth;
            }
        }
        return new ArrayBasedHierarchy(java.util.Arrays.copyOf(ids, kept), java.util.Arrays.copyOf(depths, kept));
    }
}
