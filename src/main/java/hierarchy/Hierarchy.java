package hierarchy;

/**
 * A {@code Hierarchy} stores an arbitrary forest (an ordered collection of ordered trees)
 * as an array of node IDs in DFS order, with a parallel array of node depths.
 *
 * <p>Example: nodeIds 1..11 with depths 0,1,2,3,1,0,1,0,1,1,2 describes
 * <pre>
 * 1
 * - 2
 * - - 3
 * - - - 4
 * - 5
 * 6
 * - 7
 * 8
 * - 9
 * - 10
 * - - 11
 * </pre>
 *
 * <p>Invariants: the first depth is 0; the next depth is at most the previous depth plus one.
 */
public interface Hierarchy {
    /** The number of nodes in the hierarchy. */
    int size();

    /** The unique ID of the node at {@code index}, which must be in {@code [0, size())}. */
    int nodeId(int index);

    /** The depth of the node at {@code index}, which must be in {@code [0, size())}. */
    int depth(int index);

    default String formatString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(nodeId(i)).append(":").append(depth(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
