package net.benpl.hgt.reader;

import org.openstreetmap.osmosis.core.pipeline.common.TaskManagerFactory;
import org.openstreetmap.osmosis.core.plugin.PluginLoader;

import java.util.HashMap;
import java.util.Map;

public class HgtFileReaderPluginLoader implements PluginLoader {
    @Override
    public Map<String, TaskManagerFactory> loadTaskFactories() {
        HgtFileReaderFactory hgtFileReaderFactory = new HgtFileReaderFactory();
        HashMap<String, TaskManagerFactory> factories = new HashMap<>();
        factories.put("hgtfile-reader", hgtFileReaderFactory);
        factories.put("rhgt", hgtFileReaderFactory);
        return factories;
    }
}
