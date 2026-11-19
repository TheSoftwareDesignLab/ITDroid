package uniandes.tsdl.itdroid.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * A screen with its screenshot, to tell what each element draws. Nested elements with the same box
 * are one visible thing, a chain, and an element inside a scrolling area is drawn only within it.
 * Without a screenshot every element with a box draws its box.
 */
final class DrawnScreen {

	/** Pixels of the ring around a box that tell what the box stands on. */
	static final int RING = 2;

	final List<AndroidNode> nodes;
	final Ink ink;
	private final int[][] inkBoxes;
	private final boolean[] inkDone;
	private final Boolean[] isText;

	DrawnScreen(State state, BufferedImage shot) {
		nodes = state.getStateNodes();
		ink = new Ink(shot);
		inkBoxes = new int[nodes.size()][];
		inkDone = new boolean[nodes.size()];
		isText = new Boolean[nodes.size()];
	}

	int[] box(int i) {
		return Boxes.of(nodes.get(i));
	}

	int parent(int i) {
		return nodes.get(i).getParentPosition();
	}

	/** True when a holds d, directly or through others. */
	boolean isAncestor(int a, int d) {
		for (int p = parent(d); p >= 0; p = parent(p)) {
			if (p == a) {
				return true;
			}
		}
		return false;
	}

	/** True when one of the two holds the other. */
	boolean related(int a, int b) {
		return isAncestor(a, b) || isAncestor(b, a);
	}

	/** The nearest scrolling area that holds the element, -1 when none. */
	int scrollingAncestor(int i) {
		for (int p = parent(i); p >= 0; p = parent(p)) {
			if (nodes.get(p).isScrollable()) {
				return p;
			}
		}
		return -1;
	}

	/** The box of the element cut to the scrolling areas that hold it, which do not draw it past their sides. Null when nothing is left. */
	int[] visibleBox(int i) {
		int[] b = box(i);
		for (int p = parent(i); p >= 0 && b != null; p = parent(p)) {
			if (nodes.get(p).isScrollable()) {
				b = Boxes.intersection(b, box(p));
			}
		}
		return b;
	}

	/** Whether the element touches the bottom of the scrolling area it is in, so part of it can be out of view. */
	boolean cutAtBottom(int i, int slack) {
		int p = scrollingAncestor(i);
		return p >= 0 && nodes.get(i).getPoint2()[1] >= nodes.get(p).getPoint2()[1] - slack;
	}

	/** The outermost element of the chain of nested elements with the same box as this one. */
	int chainTop(int i) {
		int top = i;
		while (parent(top) >= 0 && Boxes.same(box(parent(top)), box(top))) {
			top = parent(top);
		}
		return top;
	}

	/** The elements of the chain of this one, the outermost first. */
	List<Integer> chain(int i) {
		int top = chainTop(i);
		int[] b = box(top);
		List<Integer> out = new ArrayList<>();
		out.add(top);
		for (int k = 0; k < out.size(); k++) {
			for (int c : nodes.get(out.get(k)).getChildPositions()) {
				if (Boxes.same(box(c), b)) {
					out.add(c);
				}
			}
		}
		out.sort(null);
		return out;
	}

	/** The element that speaks for its chain among those accepted, the first with a text, else the outermost. */
	int representative(int i, IntPredicate accepted) {
		List<Integer> chain = chain(i);
		for (int m : chain) {
			if (accepted.test(m) && nodes.get(m).hasText()) {
				return m;
			}
		}
		for (int m : chain) {
			if (accepted.test(m)) {
				return m;
			}
		}
		return i;
	}

	/** True when an element of the chain of this one has a text or a description, so the chain is a text. */
	boolean isText(int i) {
		if (isText[i] == null) {
			isText[i] = false;
			for (int m : chain(i)) {
				isText[i] |= nodes.get(m).hasText();
			}
		}
		return isText[i];
	}

	/** The box of what the element draws within the scrolling areas that hold it, null when nothing. */
	int[] inkBox(int i) {
		if (!inkDone[i]) {
			inkDone[i] = true;
			int[] visible = visibleBox(i);
			inkBoxes[i] = visible == null ? null : ink.box(visible);
		}
		return inkBoxes[i];
	}

	static boolean isImage(AndroidNode n) {
		return n.getpClass() != null && n.getpClass().contains("Image");
	}

	/** A text, a content description or a picture, what a person reads or recognises. */
	static boolean textOrImage(AndroidNode n) {
		return n.hasText() || isImage(n);
	}

	/** True when the element fills its box with a colour that is not what it stands on. */
	boolean filled(int i) {
		int[] b = visibleBox(i);
		if (b == null || !ink.available()) {
			return false;
		}
		int inside = ink.background(b), around = ink.surroundings(b, RING);
		return inside >= 0 && around >= 0 && Ink.distance(inside, around) > Ink.INK;
	}

	/**
	 * Whether the chain of the element draws something of its own. It does when one of its elements
	 * can be tapped or has a text, a description or a picture, when ink is left once the boxes of
	 * what it holds are taken out, or when it fills its box with a colour of its own.
	 */
	boolean drawsSomething(int i) {
		List<Integer> chain = chain(i);
		for (int m : chain) {
			AndroidNode n = nodes.get(m);
			if (n.isClickable() || textOrImage(n)) {
				return true;
			}
		}
		int[] visible = visibleBox(i);
		if (visible == null) {
			return false;
		}
		if (!ink.available()) {
			return true;
		}
		List<int[]> held = new ArrayList<>();
		for (int m : chain) {
			for (int c : nodes.get(m).getChildPositions()) {
				if (!chain.contains(c)) {
					held.add(box(c));
				}
			}
		}
		return ink.box(visible, held) != null || filled(i);
	}

	/** Whether the element with all it holds draws anything, for an element only one screen has. */
	boolean drawsAnything(int i) {
		AndroidNode n = nodes.get(i);
		if (n.isClickable() || textOrImage(n)) {
			return true;
		}
		for (int c : n.getChildPositions()) {
			if (drawsAnything(c)) {
				return true;
			}
		}
		return inkBox(i) != null || filled(i);
	}
}
