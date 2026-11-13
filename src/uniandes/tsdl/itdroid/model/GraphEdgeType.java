package uniandes.tsdl.itdroid.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * How one element of a screen stands with respect to another. {@code graph[a][b]} of a
 * {@link State} holds the relations of node a with respect to node b ("a is LEFT of b").
 * Sets of relations are {@link EnumSet}s everywhere, so they are always written in this order.
 */
public enum GraphEdgeType {
	ABOVE,
	BELOW,
	LEFT,
	RIGHT,
	TOP_ALIGNED,
	BOTTOM_ALIGNED,
	RIGHT_ALIGNED,
	LEFT_ALIGNED,
	CONTAINS,
	CONTAINED,
	INTERSECTS,
	DEFAULT;

	/** The four alignments: what a change of width of a text alters without anything having moved. */
	public static Set<GraphEdgeType> getAligmentTypes() {
		return EnumSet.of(TOP_ALIGNED, BOTTOM_ALIGNED, LEFT_ALIGNED, RIGHT_ALIGNED);
	}

	/**
	 * The relations that are expected to change between a left-to-right and a right-to-left
	 * language, because the layout is mirrored: who is left of whom, and which side is aligned.
	 */
	public static Set<GraphEdgeType> getRtlChangeExpectedTypes() {
		return EnumSet.of(LEFT, RIGHT, LEFT_ALIGNED, RIGHT_ALIGNED);
	}

	/** The relation this one becomes in a mirrored layout: left and right swap, the rest stays. */
	public GraphEdgeType mirrored() {
		switch (this) {
			case LEFT:
				return RIGHT;
			case RIGHT:
				return LEFT;
			case LEFT_ALIGNED:
				return RIGHT_ALIGNED;
			case RIGHT_ALIGNED:
				return LEFT_ALIGNED;
			default:
				return this;
		}
	}

	public boolean isAlignment() {
		return this == TOP_ALIGNED || this == BOTTOM_ALIGNED || this == LEFT_ALIGNED || this == RIGHT_ALIGNED;
	}
}
