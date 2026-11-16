package uniandes.tsdl.itdroid.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which element of the translated screen is which element of the source screen. Both trees are
 * walked from the top and the children of two paired parents are aligned in order by class and
 * resource id, so an inserted or removed element costs only itself and what hangs from it.
 * Identical siblings are told apart by their subtrees and then by their place on the screen.
 */
public final class NodePairing {

	/** For each node of the source screen, the position of its pair in the translated screen, or -1. */
	public final int[] targetOfSource;
	/** For each node of the translated screen, the position of its pair in the source screen, or -1. */
	public final int[] sourceOfTarget;
	/** Pairs made. */
	public final int paired;

	private final List<AndroidNode> source;
	private final List<AndroidNode> target;
	private final int[] sourceSubtree;
	private final int[] targetSubtree;
	private final double width;
	private final double height;
	/** True when the translated screen is expected to be the mirror image of the source one. */
	private final boolean mirrored;
	private int count;

	private NodePairing(State sourceState, State targetState, boolean mirrored, int screenWidth) {
		this.mirrored = mirrored;
		source = sourceState.getStateNodes();
		target = targetState.getStateNodes();
		targetOfSource = new int[source.size()];
		sourceOfTarget = new int[target.size()];
		Arrays.fill(targetOfSource, -1);
		Arrays.fill(sourceOfTarget, -1);
		sourceSubtree = subtreeSizes(source);
		targetSubtree = subtreeSizes(target);
		int w = Math.max(1, screenWidth);
		int h = 1;
		for (AndroidNode n : source) {
			w = Math.max(w, n.getPoint2()[0]);
			h = Math.max(h, n.getPoint2()[1]);
		}
		width = w;
		height = h;
		align(topLevel(source), topLevel(target));
		paired = count;
	}

	/** Pairs the elements of two screens of languages written in the same direction. */
	public static NodePairing of(State source, State target) {
		return new NodePairing(source, target, false, 0);
	}

	/**
	 * Pairs the elements of two screens. With {@code mirrored} the languages are written in opposite
	 * directions and an element is also looked for at its mirrored place, as the app may not mirror.
	 */
	public static NodePairing of(State source, State target, boolean mirrored) {
		return new NodePairing(source, target, mirrored, 0);
	}

	/**
	 * As {@link #of(State, State, boolean)}, with the device screen width. Without it the width comes
	 * from the source screen, which for a dialog is too narrow and puts the mirrored place off.
	 */
	public static NodePairing of(State source, State target, boolean mirrored, int screenWidth) {
		return new NodePairing(source, target, mirrored, screenWidth);
	}

	/** Pairs over the node count of the larger screen, from 0 to 1. Two captures of one screen are close to 1. */
	public double similarity() {
		int larger = Math.max(source.size(), target.size());
		return larger == 0 ? 1.0 : paired / (double) larger;
	}

	private static List<Integer> topLevel(List<AndroidNode> nodes) {
		List<Integer> out = new ArrayList<>();
		for (AndroidNode n : nodes) {
			if (n.getParentPosition() < 0) {
				out.add(n.getPosition());
			}
		}
		return out;
	}

	/** Nodes in the subtree of each node, itself included. Children come after their parent, so one backward pass does it. */
	private static int[] subtreeSizes(List<AndroidNode> nodes) {
		int[] size = new int[nodes.size()];
		for (int i = nodes.size() - 1; i >= 0; i--) {
			size[i] += 1;
			int parent = nodes.get(i).getParentPosition();
			if (parent >= 0) {
				size[parent] += size[i];
			}
		}
		return size;
	}

	/** Pairs two lists of siblings in order (the best-scoring alignment, as in a diff), then their children. */
	private void align(List<Integer> a, List<Integer> b) {
		int n = a.size();
		int m = b.size();
		if (n == 0 || m == 0) {
			return;
		}
		double[][] sim = new double[n][m];
		double[][] best = new double[n + 1][m + 1];
		for (int i = 1; i <= n; i++) {
			for (int j = 1; j <= m; j++) {
				sim[i - 1][j - 1] = similarity(source.get(a.get(i - 1)), target.get(b.get(j - 1)));
				double value = Math.max(best[i - 1][j], best[i][j - 1]);
				if (sim[i - 1][j - 1] > 0) {
					value = Math.max(value, best[i - 1][j - 1] + sim[i - 1][j - 1]);
				}
				best[i][j] = value;
			}
		}
		// Walk back. On a tie the later element is left out, so the first candidates are paired.
		int i = n;
		int j = m;
		List<int[]> pairs = new ArrayList<>();
		while (i > 0 && j > 0) {
			if (same(best[i][j], best[i][j - 1])) {
				j--;
			} else if (same(best[i][j], best[i - 1][j])) {
				i--;
			} else {
				pairs.add(new int[] {a.get(i - 1), b.get(j - 1)});
				i--;
				j--;
			}
		}
		for (int[] p : pairs) {
			targetOfSource[p[0]] = p[1];
			sourceOfTarget[p[1]] = p[0];
			count++;
			align(source.get(p[0]).getChildPositions(), target.get(p[1]).getChildPositions());
		}
	}

	/**
	 * The resource id when it is a real one, else "". The dump also puts Compose test tags there,
	 * which may be the visible label and so differ between languages.
	 */
	private static String realId(AndroidNode node) {
		String id = node.getResourceID();
		return id.contains(":id/") ? id : "";
	}

	private static boolean same(double x, double y) {
		return Math.abs(x - y) < 1e-9;
	}

	/**
	 * How likely two siblings are the same element, 0 when they cannot be. Class and resource id
	 * decide, then what the element is like, then its position, which weighs little as translation
	 * moves elements. When mirrored, the horizontal distance to the mirror image also counts.
	 */
	private double similarity(AndroidNode s, AndroidNode t) {
		if (!s.getpClass().equals(t.getpClass())) {
			return 0;
		}
		String sid = realId(s);
		String tid = realId(t);
		if (!sid.isEmpty() && !tid.isEmpty() && !sid.equals(tid)) {
			return 0;
		}
		double score = 1;
		if (!sid.isEmpty() && sid.equals(tid)) {
			score += 2;
		} else if (sid.isEmpty() != tid.isEmpty()) {
			score -= 0.5;
		} else if (!s.getResourceID().isEmpty() && s.getResourceID().equals(t.getResourceID())) {
			score += 1;   // the same tag, though not a resource id
		}
		if (s.isClickable() == t.isClickable()) {
			score += 0.5;
		}
		if (s.hasText() == t.hasText()) {
			score += 0.5;
		}
		if (s.getChildPositions().size() == t.getChildPositions().size()) {
			score += 0.5;
		}
		if (sourceSubtree[s.getPosition()] == targetSubtree[t.getPosition()]) {
			score += 0.5;
		}
		double sy = (s.getPoint1()[1] + s.getPoint2()[1]) / 2.0;
		double ty = (t.getPoint1()[1] + t.getPoint2()[1]) / 2.0;
		double sx = (s.getPoint1()[0] + s.getPoint2()[0]) / 2.0;
		double tx = (t.getPoint1()[0] + t.getPoint2()[0]) / 2.0;
		score += 0.2 * (1 - Math.min(1, Math.abs(sy - ty) / height));
		double apart = Math.abs(sx - tx);
		if (mirrored) {
			apart = Math.min(apart, Math.abs(width - sx - tx));
		}
		score += 0.2 * (1 - Math.min(1, apart / width));
		if (s.getWidth() == t.getWidth() && s.getHeight() == t.getHeight()) {
			score += 0.1;
		}
		return score;
	}
}
