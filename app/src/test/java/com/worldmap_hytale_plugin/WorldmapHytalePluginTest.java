package com.suiramdev.worldmap;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorldmapHytalePluginTest {
    @Test
    public void pluginJsonExists() {
        // Verify manifest.json exists in resources
        assertNotNull(getClass().getClassLoader().getResource("manifest.json"));
    }
}
