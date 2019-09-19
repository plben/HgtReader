package net.benpl.hgt.reader;

import org.openstreetmap.osmosis.core.pipeline.common.TaskConfiguration;
import org.openstreetmap.osmosis.core.pipeline.common.TaskManager;
import org.openstreetmap.osmosis.core.pipeline.common.TaskManagerFactory;
import org.openstreetmap.osmosis.core.pipeline.v0_6.RunnableSourceManager;

import java.io.File;

class HgtFileReaderFactory extends TaskManagerFactory {
    @Override
    protected TaskManager createTaskManagerImpl(TaskConfiguration taskConfig) {
        String filePath = getStringArgument(taskConfig, "file");
        int interval = getIntegerArgument(taskConfig, "interval", 25);

        String elevKey = getStringArgument(taskConfig, "elev-key", "ele");
        String contourKey = getStringArgument(taskConfig, "contour-key", "contour");
        String contourVal = getStringArgument(taskConfig, "contour-val", "elevation");
        String contourExtKey = getStringArgument(taskConfig, "contour-ext-key", "contour_ext");
        String contourExtMajor = getStringArgument(taskConfig, "contour-ext-major", "elevation_major");
        String contourExtMedium = getStringArgument(taskConfig, "contour-ext-medium", "elevation_medium");
        String contourExtMinor = getStringArgument(taskConfig, "contour-ext-minor", "elevation_minor");

        HgtFileReader task;
        task = new HgtFileReader(filePath, interval, elevKey, contourKey, contourVal, contourExtKey, contourExtMajor, contourExtMedium, contourExtMinor);

        return new RunnableSourceManager(taskConfig.getId(), task, taskConfig.getPipeArgs());
    }
}
