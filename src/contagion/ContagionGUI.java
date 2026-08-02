package contagion;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ContagionGUI — Swing front-end for the Contagion pandemic simulator.
 *
 * <p>This class is owned entirely by the GUI author. It binds to the teammate
 * model/engine classes ({@link Country}, {@link TravelRoute}, {@link Disease},
 * {@link CureSystem}, {@code PandemicNetwork}, {@code SpreadSimulator},
 * {@code GraphTools}) <em>defensively</em>:</p>
 *
 * <ul>
 *   <li>Classes that already expose a usable API ({@code Country},
 *       {@code TravelRoute}, {@code Disease}, {@code CureSystem}) are called
 *       directly at compile time, always wrapped in try/catch at the boundary.</li>
 *   <li>Classes that are currently empty shells ({@code PandemicNetwork},
 *       {@code SpreadSimulator}, {@code GraphTools}) are bound via reflection.
 *       The GUI probes for method names it expects; if a method exists it is
 *       used, otherwise the affected panel shows a clear "unavailable" message.
 *       This means the GUI auto-lights-up as teammates fill those classes in,
 *       with no code change here.</li>
 * </ul>
 *
 * <p>Design goals: compiles against whatever exists today, launches, matches the
 * wireframe, shows REAL data where the class is ready, and shows visible
 * on-screen status messages (never a crash) where a class/method is missing.</p>
 */
public class ContagionGUI extends JFrame {

    // ---- Palette -----------------------------------------------------------
    private static final Color BG          = new Color(0x1B1F24);
    private static final Color PANEL_BG    = new Color(0x24292F);
    private static final Color MAP_BG      = new Color(0x0E141B);
    private static final Color TEXT        = new Color(0xE6EDF3);
    private static final Color MUTED       = new Color(0x9DA7B0);
    private static final Color INFECTED    = new Color(0xE74C3C); // red
    private static final Color EXPOSED     = new Color(0xF39C12); // amber
    private static final Color HEALTHY     = new Color(0x95A5A6); // gray
    private static final Color ROUTE       = new Color(0x3B4754);
    private static final Color SELECT_RING = new Color(0x58A6FF);
    private static final Color WARN        = new Color(0xF0B429);
    private static final Color OK          = new Color(0x3FB950);
    private static final Color BAD         = new Color(0xE5534B);

    /** Live-ness of each model integration, for the status readout. */
    private enum Status { LIVE, PLACEHOLDER, UNAVAILABLE }

    // ---- Real model instances (bound directly where the API exists) --------
    private Disease disease;         // real class, usable API
    private CureSystem cureSystem;   // real class, usable API

    // ---- Engine instances (empty shells today; called via reflection) ------
    private Object network;          // PandemicNetwork
    private Object simulator;        // SpreadSimulator
    private Object graphTools;       // GraphTools

    // ---- GUI-side view model ----------------------------------------------
    /** Country nodes drawn on the map. Wrap REAL {@link Country} objects. */
    private final List<CountryNode> nodes = new ArrayList<>();
    private final List<RouteEdge> edges = new ArrayList<>();
    private boolean countriesArePlaceholder = false;

    /** Recorded infected-count per day, for the infection curve. */
    private final List<Integer> infectionHistory = new ArrayList<>();

    // ---- Simulation / timer state (GUI-owned scaffolding) ------------------
    private javax.swing.Timer timer;    // NOTE: swing Timer, fires on the EDT
    private int localDay = 0;           // fallback when simulator has no day
    private boolean running = false;
    private int speedMultiplier = 1;    // 1x .. fast-forward
    private static final int BASE_DELAY_MS = 700;
    private int selectedCountryId = -1;

    // ---- Integration status map (rendered in the status bar) ---------------
    private final Map<String, Status> integrations = new LinkedHashMap<>();

    // ---- UI components referenced across methods ---------------------------
    private MapPanel mapPanel;
    private JLabel dnaLabel;
    private JLabel dayLabel;
    private JLabel diseaseLabel;
    private JButton playPauseBtn;
    private JLabel statInfected;
    private JLabel statDeaths;
    private JLabel statCountriesHit;
    private JProgressBar cureBar;
    private JLabel cureStatusLabel;
    private CurvePanel curvePanel;
    private JLabel statusBar;
    private JTextArea consoleLog;

    // =======================================================================
    //  Startup
    // =======================================================================

    public static void main(String[] args) {
        // Keep everything on the Swing EDT.
        SwingUtilities.invokeLater(() -> {
            try {
                ContagionGUI gui = new ContagionGUI();
                gui.setVisible(true);
            } catch (Throwable fatal) {
                // Safe exit point: never a raw stack-trace crash on the user.
                fatal.printStackTrace();
                JOptionPane.showMessageDialog(
                        null,
                        "Contagion GUI could not start.\n\n" + fatal
                                + "\n\nSee console for details.",
                        "Fatal startup error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public ContagionGUI() {
        super("Contagion — Pandemic Simulator");

        // ---- Construct the model layer defensively. None of this may throw
        //      the app down; every failure is recorded as a status instead. --
        bootstrapModel();

        // ---- Build the window ---------------------------------------------
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 720));
        setLocationByPlatform(true);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(6, 6));

        add(buildTopBar(),   BorderLayout.NORTH);
        add(buildLeftPanel(), BorderLayout.WEST);
        add(buildMapPanel(),  BorderLayout.CENTER);
        add(buildRightPanel(), BorderLayout.EAST);
        add(buildStatusBar(), BorderLayout.SOUTH);

        // ---- Timer scaffolding (GUI-owned) --------------------------------
        timer = new javax.swing.Timer(BASE_DELAY_MS, e -> onTick());

        // Seed the very first history sample and paint.
        recordHistorySample();
        refreshAll();
        logStartupSummary();
    }

    /**
     * Construct the model objects, recording an integration status for each and
     * never letting a failure escape. Direct calls for classes with a real API;
     * reflection construction for the empty engine shells.
     */
    private void bootstrapModel() {
        //TODO: replace with the Disease the engine owns

        try {
            disease = new Disease("Novel Pathogen X", 0.35, 0.05);
            integrations.put("Disease (name/rates)", Status.LIVE);
        } catch (Throwable t) {
            disease = null;
            integrations.put("Disease (name/rates)", Status.UNAVAILABLE);
        }

        // CureSystem — real class, fully usable. This drives the LIVE cure bar.
        try {
            cureSystem = new CureSystem();
            integrations.put("CureSystem (cure race)", Status.LIVE);
        } catch (Throwable t) {
            cureSystem = null;
            integrations.put("CureSystem (cure race)", Status.UNAVAILABLE);
        }

        // Empty engine shells — construct by reflection so a missing/renamed
        // class can never break compilation or startup here.
        network    = tryConstruct("PandemicNetwork");
        simulator  = tryConstruct("SpreadSimulator");
        graphTools = tryConstruct("GraphTools");

        loadTopology();

        // Day counter integration status.
        integrations.put("SpreadSimulator (day/step)",
                (simulator != null && findMethod(simulator, "nextDay", 0) != null)
                        ? Status.LIVE : Status.UNAVAILABLE);

        // Graph tools integration status.
        integrations.put("GraphTools (analysis)",
                (graphTools != null && graphTools.getClass().getDeclaredMethods().length > 0)
                        ? Status.LIVE : Status.UNAVAILABLE);

        // DNA points — probe Disease for a points getter (none today).
        integrations.put("Disease DNA points",
                (disease != null && findMethod(disease, "getDnaPoints", 0) != null)
                        || (disease != null && findMethod(disease, "getDNAPoints", 0) != null)
                        ? Status.LIVE : Status.PLACEHOLDER);
    }

    /**
     * Populate {@link #nodes} and {@link #edges}. Tries, via reflection, to pull
     * real countries/routes from PandemicNetwork. When that yields nothing
     * (the class is currently empty), falls back to a small placeholder network
     * of REAL {@link Country}/{@link TravelRoute} objects with GUI-assigned
     * layout coordinates, and flags it clearly.
     */
    private void loadTopology() {
        nodes.clear();
        edges.clear();
        countriesArePlaceholder = false;

        // ---- Attempt live topology from PandemicNetwork -------------------
        // TODO(bind): use whatever accessor the engine settles on, e.g.
        //   network.getCountries() -> List<Country>, network.getRoutes() -> List<TravelRoute>.
        List<?> liveCountries = asList(invoke(network, "getCountries", 0),
                                       invoke(network, "countries", 0),
                                       invoke(network, "getNodes", 0));
        if (liveCountries != null && !liveCountries.isEmpty()) {
            int i = 0;
            for (Object o : liveCountries) {
                if (o instanceof Country c) {
                    // Try to read real coordinates if the engine ever adds them.
                    Integer mx = asInt(invoke(c, "getMapX", 0));
                    Integer my = asInt(invoke(c, "getMapY", 0));
                    double nx, ny;
                    if (mx != null && my != null) {
                        nx = mx / 1000.0;
                        ny = my / 1000.0;
                    } else {
                        // No real coords available -> lay out on a ring.
                        double a = 2 * Math.PI * i / Math.max(1, liveCountries.size());
                        nx = 0.5 + 0.34 * Math.cos(a);
                        ny = 0.5 + 0.34 * Math.sin(a);
                        countriesArePlaceholder = true; // positions are GUI-assigned
                    }
                    nodes.add(new CountryNode(c, nx, ny));
                    i++;
                }
            }
            List<?> liveRoutes = asList(invoke(network, "getRoutes", 0),
                                        invoke(network, "routes", 0));
            if (liveRoutes != null) {
                for (Object o : liveRoutes) {
                    if (o instanceof TravelRoute r) {
                        int a = indexOfCountry(r.getStartCountry());
                        int b = indexOfCountry(r.getEndCountry());
                        if (a >= 0 && b >= 0) edges.add(new RouteEdge(a, b));
                    }
                }
            }
            integrations.put("PandemicNetwork (map topology)",
                    countriesArePlaceholder ? Status.PLACEHOLDER : Status.LIVE);
            return;
        }

        // ---- Fallback: placeholder network of REAL Country/TravelRoute ----
        seedPlaceholderTopology();
        integrations.put("PandemicNetwork (map topology)", Status.PLACEHOLDER);
        countriesArePlaceholder = true;
    }

    // =========================================================================
    // ⚠⚠⚠  TEMP DATA — DELETE ONCE PandemicNetwork IS IMPLEMENTED AI WROTE THIS ⚠⚠⚠
    // =========================================================================
    // Everything below this banner (seedPlaceholderTopology + the equirectX/Y
    // helpers) exists ONLY because PandemicNetwork currently exposes no
    // countries/routes/positions. It builds a small stand-in network out of
    // REAL Country/TravelRoute objects so the map isn't empty during GUI dev.
    //
    // DELETE THIS ENTIRE BLOCK (down to the closing banner below) the moment
    // PandemicNetwork gets a real getCountries()/getRoutes() (and ideally
    // Country gets real getMapX()/getMapY()) — loadTopology() above already
    // prefers the live path and will stop calling this automatically once
    // reflection finds real data, but the dead code should still be removed.
    // =========================================================================

    /**
     * TEMP / PLACEHOLDER — delete once PandemicNetwork is implemented.
     * Builds a small demo network from REAL {@link Country} and
     * {@link TravelRoute} objects with GUI-computed positions (see
     * {@link #equirectX} / {@link #equirectY}). Only ever called because
     * PandemicNetwork exposes no data yet; the map banner + status bar +
     * console log all flag this as placeholder at runtime.
     */
    private void seedPlaceholderTopology() {
        // TEMP: name, population, geographic centroid (lat, lon) in degrees.
        // Real-world centroids (not guessed), projected via equirectX/equirectY
        // below purely so the placeholder layout looks map-shaped. Still temp —
        // delete this table along with the rest of this block.
        Object[][] seed = {
                {"USA",        331_000_000,  39.8, -98.6},
                {"Brazil",     213_000_000, -14.2, -51.9},
                {"UK",          67_000_000,  55.3,  -3.4},
                {"Nigeria",    206_000_000,   9.1,   8.7},
                {"Egypt",      102_000_000,  26.8,  30.8},
                {"India",    1_380_000_000,  20.6,  78.9},
                {"China",    1_402_000_000,  35.9, 104.2},
                {"Australia",   25_000_000, -25.3, 133.8},
        };
        for (Object[] s : seed) {
            Country c = new Country((String) s[0], (Integer) s[1]);
            double lat = (Double) s[2], lon = (Double) s[3];
            nodes.add(new CountryNode(c, equirectX(lon), equirectY(lat)));
        }
        // Patient zero — set through the real setter so isInfected() is truthful.
        if (!nodes.isEmpty()) nodes.get(0).country.setInfected(true);

        // Real TravelRoute objects (distance is illustrative km — temp).
        int[][] links = {{0,2},{2,3},{2,4},{3,4},{4,5},{5,6},{6,7},{1,3},{0,1},{5,7}};
        for (int[] lk : links) {
            Country a = nodes.get(lk[0]).country;
            Country b = nodes.get(lk[1]).country;
            new TravelRoute(a, b, 1000 + (int) (a.getPopulation() % 5000)); // real object
            edges.add(new RouteEdge(lk[0], lk[1]));
        }
    }

    /**
     * TEMP / PLACEHOLDER — delete once Country exposes real getMapX()/getMapY()
     * (or PandemicNetwork otherwise supplies positions). Standard equirectangular
     * projection: longitude -180..180 -> normalized x 0..1.
     */
    private static double equirectX(double lonDegrees) {
        return (lonDegrees + 180.0) / 360.0;
    }

    /**
     * TEMP / PLACEHOLDER — delete once Country exposes real getMapX()/getMapY()
     * (or PandemicNetwork otherwise supplies positions). Standard equirectangular
     * projection: latitude -90..90 -> normalized y 0..1 (north at top).
     */
    private static double equirectY(double latDegrees) {
        return (90.0 - latDegrees) / 180.0;
    }

    // =========================================================================
    // ⚠⚠⚠  END TEMP DATA  ⚠⚠⚠
    // =========================================================================

    // =======================================================================
    //  Top bar
    // =======================================================================

    private JComponent buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBackground(PANEL_BG);
        bar.setBorder(new EmptyBorder(8, 12, 8, 12));

        JLabel title = new JLabel("☣  CONTAGION");
        title.setForeground(TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        bar.add(title, BorderLayout.WEST);

        // Transport controls (center).
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        controls.setOpaque(false);
        playPauseBtn = new JButton("▶ Play");
        playPauseBtn.addActionListener(e -> togglePlay());
        JButton fastBtn = new JButton("⏩ Fast-forward");
        fastBtn.addActionListener(e -> cycleSpeed());
        dayLabel = new JLabel();
        dayLabel.setForeground(TEXT);
        dayLabel.setFont(dayLabel.getFont().deriveFont(Font.BOLD, 14f));
        controls.add(playPauseBtn);
        controls.add(fastBtn);
        controls.add(Box.createHorizontalStrut(10));
        controls.add(dayLabel);
        bar.add(controls, BorderLayout.CENTER);

        // DNA + disease info (east).
        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        dnaLabel = new JLabel();
        dnaLabel.setForeground(WARN);
        dnaLabel.setFont(dnaLabel.getFont().deriveFont(Font.BOLD, 14f));
        dnaLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        diseaseLabel = new JLabel();
        diseaseLabel.setForeground(MUTED);
        diseaseLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
        info.add(dnaLabel);
        info.add(diseaseLabel);
        bar.add(info, BorderLayout.EAST);

        return bar;
    }

    // =======================================================================
    //  Left panel — Evolve + Graph Tools
    // =======================================================================

    private JComponent buildLeftPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(PANEL_BG);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(220, 0));

        panel.add(sectionLabel("EVOLVE"));
        // TODO(bind): wire to the real mutation API once it exists, e.g.
        //   simulator.evolveTransmission() / disease.setInfectionRate(...).
        panel.add(actionButton("＋ Transmission", () ->
                evolve("Transmission", "evolveTransmission")));
        panel.add(actionButton("＋ Resistance", () ->
                evolve("Resistance", "evolveResistance")));
        panel.add(actionButton("＋ Symptoms", () ->
                evolve("Symptoms", "evolveSymptoms")));

        panel.add(Box.createVerticalStrut(16));
        panel.add(sectionLabel("GRAPH TOOLS"));
        // TODO(bind): wire to real GraphTools methods once implemented, e.g.
        //   graphTools.nextOutbreak(network) / spreadBackbone(...) / cureDistance(...).
        panel.add(actionButton("Next outbreak", () ->
                runGraphTool("Next outbreak", "nextOutbreak")));
        panel.add(actionButton("Spread backbone", () ->
                runGraphTool("Spread backbone", "spreadBackbone")));
        panel.add(actionButton("Cure distance", () ->
                runGraphTool("Cure distance", "cureDistance")));

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        l.setBorder(new EmptyBorder(4, 2, 6, 2));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JButton actionButton(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        b.setFocusPainted(false);
        b.addActionListener(e -> {
            try {
                action.run();
            } catch (Throwable t) {
                setStatus("Action failed: " + t, BAD);
            }
        });
        return b;
    }

    // =======================================================================
    //  Center — Map panel
    // =======================================================================

    private JComponent buildMapPanel() {
        mapPanel = new MapPanel();
        mapPanel.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(ROUTE), "World Map",
                TitledBorder.LEFT, TitledBorder.TOP, null, MUTED));
        return mapPanel;
    }

    /** Custom-painted world map. */
    private class MapPanel extends JPanel {
        MapPanel() {
            setBackground(MAP_BG);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int id = countryAt(e.getX(), e.getY());
                    selectedCountryId = id;               // -1 clears selection
                    refreshRightPanel();
                    repaint();
                }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            // No network + no countries -> show the unavailable message.
            if (nodes.isEmpty()) {
                drawCenteredMessage(g,
                        "Map data unavailable — waiting on PandemicNetwork");
                return;
            }

            // Placeholder banner so it's obvious the topology isn't live.
            if (countriesArePlaceholder) {
                g.setColor(WARN);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g.drawString("⚠ TEMP DATA — delete once PandemicNetwork is implemented "
                        + "(real Country/TravelRoute objects, GUI-computed layout)",
                        14, 24);
            }

            // Routes first (under the nodes).
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(ROUTE);
            for (RouteEdge e : edges) {
                Point a = pixelOf(nodes.get(e.a));
                Point b = pixelOf(nodes.get(e.b));
                g.drawLine(a.x, a.y, b.x, b.y);
            }

            // Nodes.
            for (int i = 0; i < nodes.size(); i++) {
                CountryNode n = nodes.get(i);
                Point p = pixelOf(n);
                int r = nodeRadius(n);

                Color fill;
                try {
                    if (n.country.isInfected()) fill = INFECTED;
                    else if (isExposed(i))      fill = EXPOSED;  // view-only heuristic
                    else                        fill = HEALTHY;
                } catch (Throwable t) {
                    fill = HEALTHY;
                }

                if (i == selectedCountryId) {
                    g.setColor(SELECT_RING);
                    g.fillOval(p.x - r - 4, p.y - r - 4, 2 * (r + 4), 2 * (r + 4));
                }
                g.setColor(fill);
                g.fillOval(p.x - r, p.y - r, 2 * r, 2 * r);
                g.setColor(new Color(0, 0, 0, 120));
                g.drawOval(p.x - r, p.y - r, 2 * r, 2 * r);

                // Short label.
                String label = shortName(n.safeName());
                g.setColor(TEXT);
                g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
                g.drawString(label, p.x - r, p.y - r - 4);
            }

            drawLegend(g);
        }

        private void drawLegend(Graphics2D g) {
            int x = 14, y = getHeight() - 58;
            g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            legendDot(g, x, y,      INFECTED, "Infected");
            legendDot(g, x, y + 18, EXPOSED,  "Exposed (visual: adjacent to infected)");
            legendDot(g, x, y + 36, HEALTHY,  "Healthy");
        }

        private void legendDot(Graphics2D g, int x, int y, Color c, String text) {
            g.setColor(c);
            g.fillOval(x, y - 9, 11, 11);
            g.setColor(MUTED);
            g.drawString(text, x + 18, y);
        }

        private void drawCenteredMessage(Graphics2D g, String msg) {
            g.setColor(WARN);
            g.setFont(getFont().deriveFont(Font.BOLD, 14f));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(msg);
            g.drawString(msg, (getWidth() - tw) / 2, getHeight() / 2);
        }

        /** Pixel position of a node given its normalized coords + panel size. */
        private Point pixelOf(CountryNode n) {
            int m = 40;
            int w = Math.max(1, getWidth() - 2 * m);
            int h = Math.max(1, getHeight() - 2 * m);
            return new Point(m + (int) (n.nx * w), m + (int) (n.ny * h));
        }

        private int nodeRadius(CountryNode n) {
            long pop;
            try { pop = n.country.getPopulation(); } catch (Throwable t) { pop = 0; }
            // sqrt scale, clamped, so big countries read bigger without dominating.
            int r = (int) (7 + Math.sqrt(Math.max(0, pop) / 5_000_000.0));
            return Math.max(7, Math.min(22, r));
        }
    }

    /**
     * Map a click to the nearest country index within a hit radius, else -1.
     * Uses the same normalized->pixel transform as the painter.
     */
    private int countryAt(int px, int py) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        int m = 40;
        int w = Math.max(1, mapPanel.getWidth() - 2 * m);
        int h = Math.max(1, mapPanel.getHeight() - 2 * m);
        for (int i = 0; i < nodes.size(); i++) {
            CountryNode n = nodes.get(i);
            int x = m + (int) (n.nx * w);
            int y = m + (int) (n.ny * h);
            double d = Math.hypot(px - x, py - y);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return bestDist <= 28 ? best : -1;
    }

    /** View-only "exposed" heuristic: healthy but adjacent to an infected node. */
    private boolean isExposed(int idx) {
        for (RouteEdge e : edges) {
            int other = -1;
            if (e.a == idx) other = e.b;
            else if (e.b == idx) other = e.a;
            if (other >= 0) {
                try {
                    if (nodes.get(other).country.isInfected()) return true;
                } catch (Throwable ignored) { }
            }
        }
        return false;
    }

    // =======================================================================
    //  Right panel — stats, cure bar, infection curve
    // =======================================================================

    private JComponent buildRightPanel() {
        JPanel panel = new JPanel();
        panel.setBackground(PANEL_BG);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(280, 0));

        panel.add(sectionLabel("STATISTICS"));
        statInfected     = statRow(panel, "Infected:");
        statDeaths       = statRow(panel, "Deaths:");
        statCountriesHit = statRow(panel, "Countries hit:");

        panel.add(Box.createVerticalStrut(16));
        panel.add(sectionLabel("CURE RACE"));
        cureBar = new JProgressBar(0, 100);
        cureBar.setStringPainted(true);
        cureBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        cureBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        cureBar.setForeground(OK);
        panel.add(cureBar);
        cureStatusLabel = new JLabel();
        cureStatusLabel.setForeground(MUTED);
        cureStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        cureStatusLabel.setFont(cureStatusLabel.getFont().deriveFont(11f));
        panel.add(cureStatusLabel);

        panel.add(Box.createVerticalStrut(16));
        panel.add(sectionLabel("INFECTION CURVE"));
        curvePanel = new CurvePanel();
        curvePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        curvePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        curvePanel.setPreferredSize(new Dimension(260, 120));
        panel.add(curvePanel);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JLabel statRow(JPanel parent, String caption) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        JLabel cap = new JLabel(caption);
        cap.setForeground(MUTED);
        JLabel val = new JLabel("—");
        val.setForeground(TEXT);
        val.setHorizontalAlignment(SwingConstants.RIGHT);
        val.setFont(val.getFont().deriveFont(Font.BOLD, 13f));
        row.add(cap, BorderLayout.WEST);
        row.add(val, BorderLayout.EAST);
        parent.add(row);
        return val;
    }

    /** Tiny infection-over-time line chart. */
    private class CurvePanel extends JPanel {
        CurvePanel() { setBackground(MAP_BG); }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            if (infectionHistory.size() < 2) {
                g.setColor(MUTED);
                g.setFont(getFont().deriveFont(11f));
                g.drawString("Curve builds as days pass…", 8, getHeight() / 2);
                return;
            }
            int max = 1;
            for (int v : infectionHistory) max = Math.max(max, v);
            int w = getWidth(), h = getHeight();
            int n = infectionHistory.size();

            g.setColor(INFECTED);
            g.setStroke(new BasicStroke(2f));
            int prevX = 0, prevY = h;
            for (int i = 0; i < n; i++) {
                int x = (int) (i * (w - 8) / (double) (n - 1)) + 4;
                int y = h - 6 - (int) (infectionHistory.get(i) * (h - 14) / (double) max);
                if (i > 0) g.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        }
    }

    // =======================================================================
    //  Status bar (bottom) + console log
    // =======================================================================

    private JComponent buildStatusBar() {
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(PANEL_BG);
        south.setBorder(new EmptyBorder(4, 12, 6, 12));

        statusBar = new JLabel(" ");
        statusBar.setForeground(TEXT);
        south.add(statusBar, BorderLayout.NORTH);

        consoleLog = new JTextArea(4, 20);
        consoleLog.setEditable(false);
        consoleLog.setBackground(MAP_BG);
        consoleLog.setForeground(MUTED);
        consoleLog.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane sp = new JScrollPane(consoleLog);
        sp.setPreferredSize(new Dimension(0, 78));
        sp.setBorder(BorderFactory.createLineBorder(ROUTE));
        south.add(sp, BorderLayout.CENTER);

        return south;
    }

    private void setStatus(String msg, Color color) {
        statusBar.setForeground(color);
        statusBar.setText(msg);
    }

    private void log(String line) {
        consoleLog.append(line + "\n");
        consoleLog.setCaretPosition(consoleLog.getDocument().getLength());
    }

    private void logStartupSummary() {
        log("=== Contagion GUI integration status ===");
        int live = 0, ph = 0, un = 0;
        for (Map.Entry<String, Status> e : integrations.entrySet()) {
            String tag = switch (e.getValue()) {
                case LIVE        -> { live++; yield "[LIVE]       "; }
                case PLACEHOLDER -> { ph++;   yield "[PLACEHOLDER]"; }
                case UNAVAILABLE -> { un++;   yield "[UNAVAILABLE]"; }
            };
            log(tag + " " + e.getKey());
        }
        setStatus(String.format(
                "Integrations — %d live, %d placeholder, %d unavailable. "
                        + "Placeholder/unavailable pieces are labelled on-screen.",
                live, ph, un),
                un > 0 ? WARN : (ph > 0 ? WARN : OK));
    }

    // =======================================================================
    //  Actions
    // =======================================================================

    private void togglePlay() {
        running = !running;
        if (running) {
            timer.start();
            playPauseBtn.setText("⏸ Pause");
            setStatus("Simulation running at " + speedMultiplier + "×", OK);
        } else {
            timer.stop();
            playPauseBtn.setText("▶ Play");
            setStatus("Paused.", MUTED);
        }
    }

    private void cycleSpeed() {
        speedMultiplier = switch (speedMultiplier) {
            case 1 -> 2;
            case 2 -> 4;
            default -> 1;
        };
        timer.setDelay(Math.max(60, BASE_DELAY_MS / speedMultiplier));
        setStatus("Speed: " + speedMultiplier + "×", TEXT);
    }

    /** One simulation step. Prefers the real simulator; falls back locally. */
    private void onTick() {
        // 1) Advance the day. Try the real engine first (reflection-guarded).
        Object advanced = invoke(simulator, "nextDay", 0);
        if (advanced == null) advanced = invoke(simulator, "step", 0);
        if (advanced == null) {
            // Fallback so play/pause is visibly alive without the engine.
            localDay++;
            advancePlaceholderSpread(); // clearly-marked placeholder progression
        }

        // 2) Advance the LIVE cure bar via the real CureSystem method.
        //    TODO(bind): the engine should own cure progression; until then the
        //    GUI nudges it so the real getProgress() is visibly wired.
        try {
            if (cureSystem != null && !cureSystem.isComplete()) {
                cureSystem.developCure(1.5 * speedMultiplier);
            }
        } catch (Throwable ignored) { }

        recordHistorySample();
        refreshAll();
    }

    /** Placeholder spread: infect one new adjacent country per tick. */
    private void advancePlaceholderSpread() {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            try {
                if (!nodes.get(i).country.isInfected() && isExposed(i)) candidates.add(i);
            } catch (Throwable ignored) { }
        }
        if (!candidates.isEmpty()) {
            int pick = candidates.get((int) (Math.random() * candidates.size()));
            try { nodes.get(pick).country.setInfected(true); } catch (Throwable ignored) { }
        }
    }

    private void evolve(String trait, String method) {
        Object r = invoke(simulator, method, 0);
        if (r == null) r = invoke(disease, method, 0);
        if (r != null) {
            setStatus("Evolved " + trait + " (engine).", OK);
        } else {
            setStatus(trait + " evolution unavailable — waiting on SpreadSimulator/Disease API.",
                    WARN);
            log("[evolve] " + trait + ": no engine method '" + method + "' yet.");
        }
        refreshAll();
    }

    private void runGraphTool(String label, String method) {
        //   graphTools.nextOutbreak(network) @todo
        Object r = invoke(graphTools, method, 1, network);
        if (r == null) r = invoke(graphTools, method, 0);
        if (r != null) {
            setStatus(label + ": " + String.valueOf(r), OK);
            log("[graph] " + label + " -> " + r);
        } else {
            setStatus(label + " unavailable — waiting on GraphTools." + method + "().", WARN);
            log("[graph] " + label + ": no method '" + method + "' on GraphTools yet.");
        }
    }

    // =======================================================================
    //  Refresh / rendering of live data
    // =======================================================================

    private void refreshAll() {
        refreshTopBar();
        refreshRightPanel();
        if (mapPanel != null) mapPanel.repaint();
    }

    /**
     * Day counter
     */

    private void refreshTopBar() {
        Integer engineDay = asInt(invoke(simulator, "getDay", 0));
        if (engineDay == null) engineDay = asInt(invoke(simulator, "getCurrentDay", 0));
        if (engineDay != null) {
            dayLabel.setText("Day " + engineDay);
        } else {
            dayLabel.setText("Day " + localDay + "  (local)");
        }

        Integer dna = asInt(invoke(disease, "getDnaPoints", 0));
        if (dna == null) dna = asInt(invoke(disease, "getDNAPoints", 0));
        if (dna != null) {
            dnaLabel.setText("🧬 DNA: " + dna);
        } else {
            dnaLabel.setText("🧬 DNA: n/a (no Disease getter)");
        }

        // Disease name + rates — real getters (LIVE).
        if (disease != null) {
            try {
                diseaseLabel.setText(String.format("%s · inf %.0f%% · rec %.0f%%",
                        disease.getName(),
                        disease.getInfectionRate() * 100,
                        disease.getRecoveryRate() * 100));
            } catch (Throwable t) {
                diseaseLabel.setText("Disease data unavailable");
            }
        } else {
            diseaseLabel.setText("Disease unavailable");
        }
    }

    private void refreshRightPanel() {
        // ---- Infected + countries-hit from real Country.isInfected() -------
        if (nodes.isEmpty()) {
            statInfected.setText("unavailable");
            statCountriesHit.setText("unavailable");
        } else {
            long infectedPop = 0;
            int hit = 0;
            boolean ok = true;
            for (CountryNode n : nodes) {
                try {
                    if (n.country.isInfected()) {
                        hit++;
                        infectedPop += n.country.getPopulation();
                    }
                } catch (Throwable t) { ok = false; }
            }
            if (ok) {
                statInfected.setText(formatCount(infectedPop));
                statCountriesHit.setText(hit + " / " + nodes.size());
            } else {
                statInfected.setText("read error");
                statCountriesHit.setText("read error");
            }
        }

        // ---- Deaths — no model source today --------------------------------
        Object deaths = invoke(simulator, "getDeaths", 0);
        statDeaths.setText(deaths != null ? String.valueOf(deaths)
                : "unavailable (no SpreadSimulator)");
        statDeaths.setForeground(deaths != null ? TEXT : WARN);

        // ---- Cure bar from real CureSystem.getProgress() (LIVE) ------------
        if (cureSystem != null) {
            try {
                int pct = (int) Math.round(cureSystem.getProgress());
                cureBar.setValue(pct);
                cureBar.setString(pct + "%");
                cureStatusLabel.setForeground(MUTED);
                cureStatusLabel.setText(cureSystem.isComplete()
                        ? "Cure complete — CureSystem.getProgress() (live)"
                        : "Live from CureSystem.getProgress()");
            } catch (Throwable t) {
                cureBar.setValue(0);
                cureStatusLabel.setForeground(WARN);
                cureStatusLabel.setText("Cure progress read error");
            }
        } else {
            cureBar.setValue(0);
            cureBar.setString("n/a");
            cureStatusLabel.setForeground(WARN);
            cureStatusLabel.setText("Cure unavailable — CureSystem missing");
        }

        if (curvePanel != null) curvePanel.repaint();
    }

    /** Record the current infected-country count for the infection curve. */
    private void recordHistorySample() {
        // TODO(bind): prefer simulator.getInfectionHistory() if it appears.
        int infected = 0;
        for (CountryNode n : nodes) {
            try { if (n.country.isInfected()) infected++; } catch (Throwable ignored) { }
        }
        infectionHistory.add(infected);
        if (infectionHistory.size() > 240) infectionHistory.remove(0);
    }

    // =======================================================================
    //  Reflection helpers (used only for the empty engine shells)
    // =======================================================================

    /** Construct {@code contagion.<simpleName>} via its no-arg constructor, or null. */
    private Object tryConstruct(String simpleName) {
        try {
            Class<?> c = Class.forName("contagion." + simpleName);
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            log("[bootstrap] could not construct " + simpleName + ": " + t);
            return null;
        }
    }

    /** Find a public/declared method by name and parameter count, or null. */
    private Method findMethod(Object target, String name, int argCount) {
        if (target == null) return null;
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argCount) return m;
        }
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argCount) return m;
        }
        return null;
    }

    /** Invoke {@code target.name(args)} by reflection; returns null on any failure. */
    private Object invoke(Object target, String name, int argCount, Object... args) {
        Method m = findMethod(target, name, argCount);
        if (m == null) return null;
        try {
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- Small conversion utilities ---------------------------------------

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return null;
    }

    private static List<?> asList(Object... candidates) {
        for (Object o : candidates) {
            if (o instanceof List<?> l) return l;
        }
        return null;
    }

    private int indexOfCountry(Country c) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).country == c) return i;
        }
        return -1;
    }

    private static String shortName(String name) {
        if (name == null) return "?";
        return name.length() <= 8 ? name : name.substring(0, 8) + "…";
    }

    private static String formatCount(long v) {
        if (v >= 1_000_000_000L) return String.format("%.1fB", v / 1_000_000_000.0);
        if (v >= 1_000_000L)     return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1_000L)         return String.format("%.1fK", v / 1_000.0);
        return String.valueOf(v);
    }

    // =======================================================================
    //  View-model records
    // =======================================================================

    /** A drawable country: a REAL {@link Country} plus normalized map coords. */
    private static final class CountryNode {
        final Country country;
        final double nx, ny; // 0..1 within the map area
        CountryNode(Country country, double nx, double ny) {
            this.country = country; this.nx = nx; this.ny = ny;
        }
        String safeName() {
            try { return country.getName(); } catch (Throwable t) { return "?"; }
        }
    }

    /** An edge between two node indices (mirrors a real {@link TravelRoute}). */
    private static final class RouteEdge {
        final int a, b;
        RouteEdge(int a, int b) { this.a = a; this.b = b; }
    }
}
