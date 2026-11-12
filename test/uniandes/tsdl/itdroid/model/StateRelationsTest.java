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
 * The layout graph of one screen: {@code graph[a][b]} holds the relations of node a with respect to
 * node b ("a is LEFT of b"). Node 0 is the full-screen root, nodes 1 and 2 the two buttons.
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
     * KNOWN GAP (TODO 4.5): INTERSECTS is only the fallback when no other relation holds, so two
     * elements that overlap while sharing an edge are reported as merely aligned. When 4.5 is done
     * this must also contain INTERSECTS.
     */
    @Test
    void knownGap_anOverlapThatSharesAnEdgeIsNotReportedAsIntersection() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 100, 100, 300, 300), button(1, 200, 100, 400, 300));

        assertEquals(EnumSet.of(TOP_ALIGNED, BOTTOM_ALIGNED), g[1][2]);
    }

    @Test
    void identicalBoundsContainEachOther() throws Exception {
        Set<GraphEdgeType>[][] g = graphOf(button(0, 100, 100, 300, 300), button(1, 100, 100, 300, 300));

        assertEquals(EnumSet.of(CONTAINS, CONTAINED, LEFT_ALIGNED, RIGHT_ALIGNED, TOP_ALIGNED, BOTTOM_ALIGNED), g[1][2]);
    }
}
