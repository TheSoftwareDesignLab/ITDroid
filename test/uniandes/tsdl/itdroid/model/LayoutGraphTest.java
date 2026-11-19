package uniandes.tsdl.itdroid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static uniandes.tsdl.itdroid.model.TestGraphs.button;
import static uniandes.tsdl.itdroid.model.TestGraphs.screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import uniandes.tsdl.itdroid.Fixtures;

/** Tests loading the result.json of an exploration into a graph of states and transitions. */
class LayoutGraphTest {

    @TempDir
    Path tmp;

    private Path copyOfCandleSource() throws IOException {
        Path copy = tmp.resolve("en");
        FileUtils.copyDirectory(Fixtures.evaluation("com.elasticrock.candle_14").resolve("trnsResults").resolve("en").toFile(), copy.toFile());
        return copy;
    }

    private static List<String> filesIn(Path folder) throws IOException {
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void loadsTheStatesAndTransitionsOfAnExploration() throws IOException {
        LayoutGraph graph = new LayoutGraph(copyOfCandleSource().toString());

        assertEquals(5, graph.getStates().size());
        assertEquals(9, graph.getTransitionCount());
        assertEquals(List.of(1, 2, 3, 4, 5), graph.getStates().stream().map(State::getId).collect(Collectors.toList()));
    }

    @Test
    void loadingDoesNotWriteIntoTheFolder() throws IOException {
        Path folder = copyOfCandleSource();

        new LayoutGraph(folder.toString());

        assertEquals(List.of("result.json"), filesIn(folder));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStateTheReplayNeverReachedLeavesAGapInsteadOfShiftingTheOthers() throws IOException {
        // a replayed exploration keeps the source ids, here state 2 was never reached
        JSONObject states = new JSONObject();
        for (long id : new long[] {1, 3}) {
            JSONObject s = new JSONObject();
            s.put("id", id);
            s.put("sourceStateId", id);
            s.put("activityName", ".Main");
            s.put("rawXML", screen(button(0, 50, 100, 450, 200)));
            s.put("screenShot", "");
            states.put(String.valueOf(id), s);
        }
        JSONObject root = new JSONObject();
        root.put("amountStates", 2L);
        root.put("amountTransitions", 0L);
        root.put("states", states);
        root.put("transitions", new JSONObject());
        Path folder = Files.createDirectories(tmp.resolve("es"));
        Files.writeString(folder.resolve("result.json"), root.toJSONString(), StandardCharsets.UTF_8);

        LayoutGraph graph = new LayoutGraph(folder.toString());

        assertEquals(List.of(1, 3), graph.getStates().stream().map(State::getId).collect(Collectors.toList()));
        assertEquals(3, graph.getState(1).getSourceStateId());
    }

    @Test
    void aMissingResultFileFailsInsteadOfGivingAnEmptyGraph() {
        assertThrows(RuntimeException.class, () -> new LayoutGraph(tmp.resolve("nothing-here").toString()));
    }
}
