package de.intranda.goobi.plugins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.goobi.beans.Step;
import org.junit.jupiter.api.Test;

class EwigExportPluginTest {

    @Test
    void findStepByTitle_titleOfLaterStep_returnsThatStep() {
        List<Step> steps = steps("Export in EWIG", "Upload in EWIG abgeschlossen");

        Step found = EwigExportPlugin.findStepByTitle(steps, "Upload in EWIG abgeschlossen");

        assertEquals(Integer.valueOf(2), found.getId());
    }

    @Test
    void findStepByTitle_unknownTitle_returnsNull() {
        List<Step> steps = steps("Export in EWIG", "Upload in EWIG abgeschlossen");

        assertNull(EwigExportPlugin.findStepByTitle(steps, "Does not exist"));
    }

    @Test
    void findStepByTitle_blankTitle_returnsNull() {
        List<Step> steps = steps("Export in EWIG", "Upload in EWIG abgeschlossen");

        assertNull(EwigExportPlugin.findStepByTitle(steps, "  "));
    }

    private static List<Step> steps(String... titles) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            Step step = new Step();
            step.setId(i + 1);
            step.setTitel(titles[i]);
            steps.add(step);
        }
        return steps;
    }
}
