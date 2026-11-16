package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static uniandes.tsdl.itdroid.model.TestGraphs.button;
import static uniandes.tsdl.itdroid.model.TestGraphs.group;
import static uniandes.tsdl.itdroid.model.TestGraphs.node;
import static uniandes.tsdl.itdroid.model.TestGraphs.screen;
import static uniandes.tsdl.itdroid.model.TestGraphs.state;
import static uniandes.tsdl.itdroid.model.TestGraphs.textView;

import org.junit.jupiter.api.Test;

/**
 * Tests which element of the translated screen is which element of the source screen. Positions
 * are those of the dump, 0 is the full-screen root and a container comes before what it holds.
 */
class NodePairingTest {

    private static NodePairing pair(String source, String translated) throws Exception {
        return NodePairing.of(state(source), state(translated));
    }

    @Test
    void theSameScreenPairsEveryElementWithItself() throws Exception {
        String s = screen(group(0, 0, 100, 1000, 400, button(0, 50, 150, 450, 250), textView(1, 500, 150, 900, 250)),
                button(1, 50, 500, 450, 600));

        NodePairing p = pair(s, s);

        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, p.targetOfSource);
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, p.sourceOfTarget);
        assertEquals(5, p.paired);
        assertEquals(1.0, p.similarity());
    }

    @Test
    void aTranslatedScreenWhoseElementsMovedAndGrewStillPairsByPosition() throws Exception {
        // what a translation does, the same elements wider and pushed around
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200), textView(2, 50, 300, 450, 400));
        String translated = screen(button(0, 50, 100, 700, 200), button(1, 50, 250, 600, 350), textView(2, 50, 400, 950, 600));

        assertArrayEquals(new int[] {0, 1, 2, 3}, pair(source, translated).targetOfSource);
    }

    @Test
    void anElementInsertedAtTheStartCostsOnlyThatElement() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200), button(1, 50, 300, 450, 400), button(2, 50, 500, 450, 600));
        String translated = screen(button(0, 600, 100, 900, 200),
                button(1, 50, 100, 450, 200), button(2, 50, 300, 450, 400), button(3, 50, 500, 450, 600));

        NodePairing p = pair(source, translated);

        assertArrayEquals(new int[] {0, 2, 3, 4}, p.targetOfSource);
        assertArrayEquals(new int[] {0, -1, 1, 2, 3}, p.sourceOfTarget);
        assertEquals(4, p.paired);
        assertEquals(0.8, p.similarity(), 1e-9);
    }

    @Test
    void whatHangsFromAnElementWithoutPairHasNoPairEither() throws Exception {
        String source = screen(group(0, 0, 100, 1000, 400, button(0, 50, 150, 450, 250), textView(1, 500, 150, 900, 250)),
                button(1, 50, 500, 450, 600));
        String translated = screen(button(0, 50, 500, 450, 600));

        assertArrayEquals(new int[] {0, -1, -1, -1, 1}, pair(source, translated).targetOfSource);
    }

    @Test
    void elementsOfDifferentClassAreNeverPaired() throws Exception {
        String source = screen(button(0, 50, 100, 450, 200));
        String translated = screen(textView(0, 50, 100, 450, 200));

        NodePairing p = pair(source, translated);

        assertArrayEquals(new int[] {0, -1}, p.targetOfSource);
        assertArrayEquals(new int[] {0, -1}, p.sourceOfTarget);
    }

    @Test
    void theResourceIdDecidesBeforeThePlace() throws Exception {
        // The translated screen lost "ok", and "cancel" sits where "ok" was.
        String ok = node(0, "android.widget.Button", "org.example:id/ok", 50, 100, 450, 200);
        String cancel = node(1, "android.widget.Button", "org.example:id/cancel", 500, 100, 900, 200);
        String cancelAlone = node(0, "android.widget.Button", "org.example:id/cancel", 50, 100, 450, 200);

        assertArrayEquals(new int[] {0, -1, 1}, pair(screen(ok, cancel), screen(cancelAlone)).targetOfSource);

        // without ids only the place tells them apart, so the one left is taken for the first
        assertArrayEquals(new int[] {0, 1, -1},
                pair(screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200)), screen(button(0, 50, 100, 450, 200))).targetOfSource);
    }

    @Test
    void aComposeTagThatIsTheTranslatedLabelDoesNotMakeTwoElementsDifferent() throws Exception {
        // Compose puts the test tag in resource-id, and some apps use the label as the tag
        String source = screen(node(0, "android.widget.EditText", "Username", 50, 100, 950, 200),
                node(1, "android.widget.EditText", "Password", 50, 300, 950, 400),
                node(2, "android.view.View", "Login", 50, 500, 950, 600));
        String translated = screen(node(0, "android.widget.EditText", "Nombre de usuario", 50, 100, 950, 200),
                node(1, "android.widget.EditText", "Contraseña", 50, 300, 950, 400),
                node(2, "android.view.View", "Inicio de sesión", 50, 500, 950, 600));

        assertArrayEquals(new int[] {0, 1, 2, 3}, pair(source, translated).targetOfSource);
    }

    @Test
    void identicalRowsArePairedInOrderAndTheOneMissingIsTheLast() throws Exception {
        String source = screen(group(0, 0, 100, 1000, 300, textView(0, 50, 150, 950, 250)),
                group(1, 0, 300, 1000, 500, textView(0, 50, 350, 950, 450)),
                group(2, 0, 500, 1000, 700, textView(0, 50, 550, 950, 650)));
        String translated = screen(group(0, 0, 100, 1000, 300, textView(0, 50, 150, 950, 250)),
                group(1, 0, 300, 1000, 500, textView(0, 50, 350, 950, 450)));

        assertArrayEquals(new int[] {0, 1, 2, 3, 4, -1, -1}, pair(source, translated).targetOfSource);
    }

    @Test
    void rowsOfTheSameKindAreToldApartByWhatHangsFromThem() throws Exception {
        // The first row (one text) is gone and the second (two texts) is in its place.
        String source = screen(group(0, 0, 100, 1000, 300, textView(0, 50, 150, 950, 250)),
                group(1, 0, 300, 1000, 500, textView(0, 50, 350, 450, 450), textView(1, 500, 350, 950, 450)));
        String translated = screen(group(0, 0, 100, 1000, 300, textView(0, 50, 150, 450, 250), textView(1, 500, 150, 950, 250)));

        assertArrayEquals(new int[] {0, -1, -1, 1, 2, 3}, pair(source, translated).targetOfSource);
    }

    @Test
    void aMirroredRowKeepsItsPairs() throws Exception {
        // in right-to-left the dump keeps the order of the elements and their places are mirrored
        String source = screen(button(0, 50, 100, 450, 200), button(1, 500, 100, 900, 200));
        String translated = screen(button(0, 550, 100, 950, 200), button(1, 100, 100, 500, 200));

        assertArrayEquals(new int[] {0, 1, 2}, NodePairing.of(state(source), state(translated), true).targetOfSource);
    }

    @Test
    void againstAMirroredLanguageTheMirroredPlaceCountsAsMuchAsTheSamePlace() throws Exception {
        // Only the right one of two buttons in a row is left. Left to right it is the second one,
        // against a right-to-left language it may be the first one mirrored, and the first is taken.
        String source = screen(button(0, 50, 100, 450, 200), button(1, 550, 100, 950, 200));
        String translated = screen(button(0, 550, 100, 950, 200));

        assertArrayEquals(new int[] {0, -1, 1}, NodePairing.of(state(source), state(translated), false).targetOfSource);
        assertArrayEquals(new int[] {0, 1, -1}, NodePairing.of(state(source), state(translated), true).targetOfSource);
    }

    @Test
    void anEmptyScreenAgainstAnotherIsNotSimilar() throws Exception {
        String empty = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\"></hierarchy>";

        assertEquals(0.0, pair(empty, screen(button(0, 50, 100, 450, 200))).similarity());
        assertEquals(1.0, pair(empty, empty).similarity());
    }
}
