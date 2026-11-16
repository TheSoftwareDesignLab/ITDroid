package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.ABOVE;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.BELOW;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.BOTTOM_ALIGNED;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.CONTAINED;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.CONTAINS;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.DEFAULT;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.INTERSECTS;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.LEFT;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.LEFT_ALIGNED;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.RIGHT;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.RIGHT_ALIGNED;
import static uniandes.tsdl.itdroid.model.GraphEdgeType.TOP_ALIGNED;
import static uniandes.tsdl.itdroid.model.TestGraphs.button;
import static uniandes.tsdl.itdroid.model.TestGraphs.screen;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The layout graph of one screen, where graph[a][b] holds the relations of node a to node b. Node 0
 * is the root, nodes 1 and 2 the two buttons.
 */
class StateRelationsTest {

    private static Set<GraphEdgeType>[][] graphOf(String... buttons) throws Exception {
        return new State(1, ".Main", screen(buttons), "").getGraph();
    }

    @Test
    void twoButtonsOnTheSameRow() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));

        assertEquals(EnumSet.of(LEFT, TOP_ALIGNED, BOTTOM_ALIGNED), g[1][2]);
        assertEquals(EnumSet.of(RIGHT, TOP_ALIGNED, BOTTOM_ALIGNED), g[2][1]);
    }

    @Test
    void twoButtonsInTheSameColumn() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        assertEquals(EnumSet.of(ABOVE, LEFT_ALIGNED, RIGHT_ALIGNED), g[1][2]);
        assertEquals(EnumSet.of(BELOW, LEFT_ALIGNED, RIGHT_ALIGNED), g[2][1]);
    }

    @Test
    void aContainerContainsItsChildren() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 50, 100, 450, 200));

        assertEquals(EnumSet.of(CONTAINS), g[0][1]);
        assertEquals(EnumSet.of(CONTAINED), g[1][0]);
    }

    @Test
    void everyNodeRelatesToItselfAsDefault() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 50, 100, 450, 200));

        assertEquals(EnumSet.of(DEFAULT), g[0][0]);
        assertEquals(EnumSet.of(DEFAULT), g[1][1]);
    }

    @Test
    void touchingEdgesCountAsBesideNotAsOverlap() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 50, 100, 450, 200), button(1, 450, 100, 850, 200));

        assertTrue(g[1][2].contains(LEFT));
        assertFalse(g[1][2].contains(INTERSECTS));
    }

    @Test
    void aPartialOverlapWithNoSharedEdgeIsAnIntersection() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 100, 100, 300, 300), button(1, 200, 200, 400, 400));

        assertEquals(EnumSet.of(INTERSECTS), g[1][2]);
        assertEquals(EnumSet.of(INTERSECTS), g[2][1]);
    }

    /**
     * Two buttons of the same row that step on each other share their top and bottom edges. That
     * they are aligned does not hide that they overlap, which is what a longer text causes.
     */
    @Test
    void anOverlapThatSharesAnEdgeIsAnIntersectionToo() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 100, 100, 300, 300), button(1, 200, 100, 400, 300));

        assertEquals(EnumSet.of(TOP_ALIGNED, BOTTOM_ALIGNED, INTERSECTS), g[1][2]);
        assertEquals(EnumSet.of(TOP_ALIGNED, BOTTOM_ALIGNED, INTERSECTS), g[2][1]);
    }

    @Test
    void anElementInsideAnotherDoesNotIntersectIt() throws Exception {
        // the second button lies inside the first and shares its left edge
        Set<GraphEdgeType>[][] g = graphOf(button(0, 100, 100, 500, 500), button(1, 100, 200, 300, 300));

        assertEquals(EnumSet.of(LEFT_ALIGNED, CONTAINS), g[1][2]);
        assertEquals(EnumSet.of(LEFT_ALIGNED, CONTAINED), g[2][1]);
        // nor does the root intersect what it holds
        assertFalse(g[0][1].contains(INTERSECTS));
    }

    @Test
    void theRelationsOfAPairAreListedInTheOrderOfTheEnum() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400));

        // an EnumSet, so reports and dumps are the same from one run to the next
        assertTrue(g[1][2] instanceof EnumSet);
        assertEquals("[ABOVE, RIGHT_ALIGNED, LEFT_ALIGNED]", g[1][2].toString());
    }

    @Test
    void identicalBoundsContainEachOther() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 100, 100, 300, 300), button(1, 100, 100, 300, 300));

        assertEquals(EnumSet.of(CONTAINS, CONTAINED, LEFT_ALIGNED, RIGHT_ALIGNED, TOP_ALIGNED, BOTTOM_ALIGNED), g[1][2]);
    }
}
