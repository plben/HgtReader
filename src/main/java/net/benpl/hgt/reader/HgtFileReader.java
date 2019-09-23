package net.benpl.hgt.reader;

import org.jaitools.imageutils.ImageUtils;
import org.jaitools.media.jai.contour.ContourDescriptor;
import org.jaitools.media.jai.contour.ContourRIF;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.util.AffineTransformation;
import org.openstreetmap.osmosis.core.container.v0_6.BoundContainer;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.*;
import org.openstreetmap.osmosis.core.sort.v0_6.EntityByTypeThenIdComparator;
import org.openstreetmap.osmosis.core.sort.v0_6.EntityContainerComparator;
import org.openstreetmap.osmosis.core.sort.v0_6.EntitySorter;
import org.openstreetmap.osmosis.core.task.v0_6.RunnableSource;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;

import javax.media.jai.*;
import javax.media.jai.registry.RIFRegistry;
import java.awt.image.renderable.RenderedImageFactory;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HgtFileReader implements RunnableSource {

    private static final Logger LOG = Logger.getLogger(HgtFileReader.class.getName());

    static {
        try {
            OperationRegistry registry = JAI.getDefaultInstance().getOperationRegistry();
            registry.registerDescriptor(new ContourDescriptor());
            RenderedImageFactory rif = new ContourRIF();
            RIFRegistry.register(registry, "Contour", "org.jaitools.media.jai", rif);
        } catch (Exception ignored) {
        }
    }

    private final File hgtFile;
    private final int interval;
    private final String elevKey;
    private final String contourKey;
    private final String contourVal;
    private final String contourExtKey;
    private final String contourExtMajor;
    private final String contourExtMedium;
    private final String contourExtMinor;

    private Sink sink;

    private Date timestamp;
    private OsmUser osmUser;

    private long wayId;
    private long nodeId;

    private double maxLon;
    private double minLon;
    private double maxLat;
    private double minLat;

    private int pixels;
    private double resolution;
    private AffineTransformation jtsTransformation;

    HgtFileReader(String filePath, int interval, String elevKey, String contourKey, String contourVal, String contourExtKey, String contourExtMajor, String contourExtMedium, String contourExtMinor) {
        this.hgtFile = new File(filePath);
        this.interval = interval;
        this.elevKey = elevKey;
        this.contourKey = contourKey;
        this.contourVal = contourVal;
        this.contourExtKey = contourExtKey;
        this.contourExtMajor = contourExtMajor;
        this.contourExtMedium = contourExtMedium;
        this.contourExtMinor = contourExtMinor;
    }

    @Override
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void run() {
        EntitySorter sorter = null;

        try {
            Short[] words = loadHgtFile();

            LOG.log(Level.INFO, String.format("minLon: %f, maxLon: %f", minLon - resolution / 2, maxLon + resolution / 2));
            LOG.log(Level.INFO, String.format("minLat: %f, maxLat: %f", minLat - resolution / 2, maxLat + resolution / 2));

            TiledImage tiledImage = buildImage(words);

            Collection<LineString> lines = buildContourLines(tiledImage);

            initOsmVariables();

            LOG.log(Level.INFO, "Write to output stream ... BEGIN");

            sorter = new EntitySorter(new EntityContainerComparator(new EntityByTypeThenIdComparator()), false);
            sorter.setSink(sink);
            sorter.initialize(Collections.emptyMap());
            sorter.process(new BoundContainer(new Bound(maxLon + resolution / 2, minLon - resolution / 2, maxLat + resolution / 2, minLat - resolution / 2, "https://www.benpl.net/thegoat/about.html")));

            for (LineString line : lines) {
                Integer elev = ((Double) line.getUserData()).intValue();
                if (elev < 50 || elev > 9000) continue;

                line.apply(jtsTransformation);

                handleLineString(line, elev, sorter);
            }

            sorter.complete();

            LOG.log(Level.INFO, "Write to output stream ... END");

        } catch (Exception e) {
            throw new Error(e);
        } finally {
            if (sorter != null) sorter.close();
        }
    }

    /**
     * Loads HGT file into buffer, and initializes relevant global variables
     */
    private Short[] loadHgtFile() throws IOException {
        if (!hgtFile.isFile()) throw new Error("File " + hgtFile.getAbsolutePath() + " not exist");

        String filename = hgtFile.getName().toLowerCase();

        if (!filename.endsWith(".hgt") || filename.length() != 11)
            throw new Error(String.format("File name %s invalid. It should look like [N28E086.hgt].", hgtFile.getName()));

        char ch0 = filename.charAt(0);
        char ch3 = filename.charAt(3);
        minLat = Integer.parseInt(filename.substring(1, 3));
        minLon = Integer.parseInt(filename.substring(4, 7));
        if ((ch0 != 'n' && ch0 != 's') || (ch3 != 'w' && ch3 != 'e') || minLat > 90 || minLon > 180) {
            throw new Error(String.format("File name %s invalid. It should look like [N28E086.hgt].", hgtFile.getName()));
        } else {
            if (ch0 == 's') minLat = -minLat;
            if (ch3 == 'w') minLon = -minLon;
        }

        maxLon = minLon + 1;
        maxLat = minLat + 1;

        int secs;
        byte[] bytes;
        Short[] words;

        long size = hgtFile.length();
        if (size == (3601 * 3601 * 2)) {
            pixels = 3601;
            secs = 1;
        } else if (size == (1201 * 1201 * 2)) {
            pixels = 1201;
            secs = 3;
        } else {
            throw new Error(hgtFile.getAbsolutePath() + " invalid file size");
        }

        bytes = new byte[pixels * pixels * 2];
        words = new Short[pixels * pixels];

        resolution = secs / 3600.0;

        //
        // Load HGT file
        //
        LOG.log(Level.INFO, String.format("Load %s ... BEGIN", hgtFile.getAbsolutePath()));

        try (DataInputStream dis = new DataInputStream(new FileInputStream(hgtFile))) {
            dis.readFully(bytes);

            for (int i = 0; i < words.length; i++) {
                words[i] = (short) (((bytes[2 * i] << 8) & 0xff00) | (bytes[2 * i + 1] & 0x00ff));
            }
        }

        //
        // GRID TO GEO
        //
        jtsTransformation = new AffineTransformation(resolution, 0, minLon, 0, -resolution, maxLat);

        LOG.log(Level.INFO, String.format("Load %s ... END", hgtFile.getAbsolutePath()));

        return words;
    }

    /**
     * Converts HGT to tiled image
     */
    private TiledImage buildImage(Short[] words) {
        LOG.log(Level.INFO, "Convert to tiled image ... BEGIN");
        TiledImage tiledImage = ImageUtils.createImageFromArray(words, pixels, pixels);
        LOG.log(Level.INFO, "Convert to tiled image ... END");

        return tiledImage;
    }

    /**
     * Converts tiled image to contour lines
     */
    private Collection<LineString> buildContourLines(TiledImage tiledImage) {
        LOG.log(Level.INFO, "Convert to contour lines ... BEGIN");

        ParameterBlockJAI pb = new ParameterBlockJAI("Contour");
        pb.setSource("source0", tiledImage);
        pb.setParameter("band", 0);
        pb.setParameter("simplify", Boolean.TRUE);
        pb.setParameter("interval", interval);
        pb.setParameter("nodata", Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.MAX_VALUE, -32768));

        RenderedOp dest = JAI.create("Contour", pb);

        @SuppressWarnings("unchecked")
        Collection<LineString> lines = (Collection<LineString>) dest.getProperty(ContourDescriptor.CONTOUR_PROPERTY_NAME);
        dest.dispose();

        LOG.log(Level.INFO, "Convert to contour lines ... END");

        return lines;
    }

    /**
     * Initializes dummy OSM variables (starts from high numbers to avoid conflict with official OSM ids)
     */
    private void initOsmVariables() {
        long lon = (long) minLon + 180L;
        long lat = (long) minLat + 90L;

        long wayBlockSize = (long) Math.pow(4, 10) * 10;
        long nodeBlockSize = (long) Math.pow(4, 10) * 100;

        wayId = 10000000L + (lon * 180L + lat) * wayBlockSize;
        nodeId = 10000000L + (360L * 180L) * wayBlockSize + (lon * 180L + lat) * nodeBlockSize;

        timestamp = new Date();
        osmUser = new OsmUser(888888, "dummyUser");
    }

    /**
     * Extracts LineString, builds OSM way and writes to output sink
     */
    private void handleLineString(LineString line, Integer elev, Sink sink) {
        int points = line.getNumPoints();

        if (points < 2) return;

        Coordinate[] coordinates = line.getCoordinates();

        Coordinate coordinate;
        Node osmNode;

        List<WayNode> wayNodes = new ArrayList<>();

        long ndId;

        for (int i = 0; i < points; i++) {
            if (i == points - 1 && line.isClosed()) {
                WayNode wayNode = wayNodes.get(0);
                wayNodes.add(new WayNode(wayNode.getNodeId(), wayNode.getLatitude(), wayNode.getLongitude()));
                break;
            }

            ndId = nodeId;
            nodeId++;

            coordinate = coordinates[i];

            osmNode = new Node(
                    new CommonEntityData(ndId, 1, timestamp, osmUser, 0),
                    coordinate.y,   // latitude
                    coordinate.x);  // longitude
            sink.process(new NodeContainer(osmNode));

            wayNodes.add(new WayNode(ndId, coordinate.y, coordinate.x));
        }

        Way osmWay = new Way(new CommonEntityData(wayId, 1, timestamp, osmUser, 0), wayNodes);
        wayId++;

        osmWay.getTags().add(new Tag(elevKey, elev.toString()));
        osmWay.getTags().add(new Tag(contourKey, contourVal));
        if (elev % 500 == 0) {
            osmWay.getTags().add(new Tag(contourExtKey, contourExtMajor));
        } else if (elev % 100 == 0) {
            osmWay.getTags().add(new Tag(contourExtKey, contourExtMedium));
        } else {
            osmWay.getTags().add(new Tag(contourExtKey, contourExtMinor));
        }

        sink.process(new WayContainer(osmWay));
    }

}
