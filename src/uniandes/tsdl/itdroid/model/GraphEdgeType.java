package uniandes.tsdl.itdroid.model;

import java.util.HashSet;
import java.util.Set;

public enum GraphEdgeType {
	ABOVE, BELOW, LEFT, RIGHT, TOP_ALIGNED, BOTTOM_ALIGNED, RIGHT_ALIGNED, LEFT_ALIGNED, CONTAINS, CONTAINED,
	INTERSECTS, DEFAULT;

	public static Set<GraphEdgeType> getAligmentTypes() {
		Set<GraphEdgeType> set = new HashSet<GraphEdgeType>();
		set.add(TOP_ALIGNED);
		set.add(BOTTOM_ALIGNED);
		set.add(LEFT_ALIGNED);
		set.add(RIGHT_ALIGNED);
		return set;
	}

	public static Set<GraphEdgeType> getDirectionTypes() {
		Set<GraphEdgeType> set = new HashSet<GraphEdgeType>();
		set.add(ABOVE);
		set.add(BELOW);
		set.add(LEFT);
		set.add(RIGHT);
		return set;
	}

	public static Set<GraphEdgeType> getRtlChangeExpectedTypes() {
		Set<GraphEdgeType> set = new HashSet<>();
		set.add(LEFT);
		set.add(RIGHT);
		set.add(LEFT_ALIGNED);
		set.add(RIGHT_ALIGNED);
		return set;
	}
}
