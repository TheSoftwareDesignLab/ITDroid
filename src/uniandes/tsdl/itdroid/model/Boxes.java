package uniandes.tsdl.itdroid.model;

/** Boxes as x1, y1, x2, y2 with the second corner outside, as the dump writes them. */
final class Boxes {

	private Boxes() {
	}

	static int[] of(AndroidNode n) {
		return new int[] { n.getPoint1()[0], n.getPoint1()[1], n.getPoint2()[0], n.getPoint2()[1] };
	}

	static boolean empty(int[] b) {
		return b == null || b[2] <= b[0] || b[3] <= b[1];
	}

	/** Pixels of a box, 0 for none. */
	static long area(int[] b) {
		return empty(b) ? 0 : (long) (b[2] - b[0]) * (b[3] - b[1]);
	}

	/** Pixels two boxes share. */
	static long overlap(int[] a, int[] b) {
		return area(intersection(a, b));
	}

	/** The common part of two boxes, null when they do not share any. */
	static int[] intersection(int[] a, int[] b) {
		if (a == null || b == null) {
			return null;
		}
		int[] r = { Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.min(a[2], b[2]), Math.min(a[3], b[3]) };
		return empty(r) ? null : r;
	}

	/** True when the two boxes have the same corners. */
	static boolean same(int[] a, int[] b) {
		return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
	}

	/** True when a is inside b, give or take the slack on every side. */
	static boolean inside(int[] a, int[] b, int slack) {
		return a[0] >= b[0] - slack && a[1] >= b[1] - slack && a[2] <= b[2] + slack && a[3] <= b[3] + slack;
	}

	static int[] grow(int[] b, int by) {
		return new int[] { b[0] - by, b[1] - by, b[2] + by, b[3] + by };
	}

	static int[] shift(int[] b, int dx, int dy) {
		return new int[] { b[0] + dx, b[1] + dy, b[2] + dx, b[3] + dy };
	}
}
