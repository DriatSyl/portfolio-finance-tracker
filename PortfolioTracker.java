import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.GeneralPath;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.*;

/**
 * PortfolioTracker.java
 *
 * Eine einzelne Java-Datei, die eine hübsche Swing-GUI für einen einfachen Portfolio-Tracker bereitstellt:
 * - Dashboard mit Kennzahlen-Karten (Heutiger Gesamtwert, Wochen-/Monats-/Jahres-Änderung)
 * - Linienchart (Gesamtwert-Verlauf)
 * - Donut-Chart (Aktien vs. Krypto Anteil)
 * - Snapshots-Tab (Tabelle mit wöchentlichen Einträgen) + Formular zum Hinzufügen
 * - Positionen-Tab (Aktien/Krypto-Positionen, manuelle Preise, P/L, Summen)
 * - Persistenz als CSV unter ~/.portfolio-tracker/snapshots.csv & positions.csv
 *
 * Keine externen Abhängigkeiten. Kompiliert mit Java 11+.
 */
public class PortfolioTracker extends JFrame {
    // Pfade
    private static final Path APP_DIR = Paths.get(System.getProperty("user.home"), ".portfolio-tracker");
    private static final Path SNAPSHOTS_CSV = APP_DIR.resolve("snapshots.csv");
    private static final Path POSITIONS_CSV = APP_DIR.resolve("positions.csv");

    // Formatierungen
    private static final Locale LOCALE = Locale.GERMANY;
    private static final NumberFormat CURRENCY = NumberFormat.getCurrencyInstance(LOCALE);
    private static final DecimalFormat PCT = new DecimalFormat("+#,##0.00%;-#,##0.00%", DecimalFormatSymbols.getInstance(LOCALE));
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Modelle
    private final SnapshotTableModel snapshotModel;
    private final PositionTableModel positionModel;

    // UI-Referenzen fürs Dashboard
    private JLabel lblTotal;
    private JLabel lblWeek;
    private JLabel lblMonth;
    private JLabel lblYear;
    private LineChartPanel lineChart;
    private DonutChartPanel donutChart;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            setSystemLookAndFeel();
            ensureStorage();
            new PortfolioTracker().setVisible(true);
        });
    }

    public PortfolioTracker() {
        super("Portfolio Finanztracker");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 720));
        setLocationRelativeTo(null);

        // Modelle laden
        List<Snapshot> snapshots = DataStore.loadSnapshots();
        List<Position> positions = DataStore.loadPositions();
        snapshotModel = new SnapshotTableModel(snapshots);
        positionModel = new PositionTableModel(positions);

        // Tabs
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Dashboard", buildDashboard());
        tabs.addTab("Snapshots", buildSnapshotsTab());
        tabs.addTab("Positionen", buildPositionsTab());

        setContentPane(tabs);
        refreshDashboard();
    }

    private static void setSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
    }

    private static void ensureStorage() {
        try {
            if (Files.notExists(APP_DIR)) Files.createDirectories(APP_DIR);
            if (Files.notExists(SNAPSHOTS_CSV)) {
                // CSV mit Header anlegen
                Files.write(SNAPSHOTS_CSV,
                        List.of("date,total,stocks,crypto"),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
            }
            if (Files.notExists(POSITIONS_CSV)) {
                Files.write(POSITIONS_CSV,
                        List.of("category,name,ticker,units,buyPrice,currentPrice"),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Konnte Speicherordner nicht anlegen:\n" + e.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------- UI: Dashboard ---------------------------------

    private JPanel buildDashboard() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel top = new JPanel(new GridLayout(1, 4, 12, 12));
        lblTotal = createMetricCard("Aktueller Gesamtwert", "—");
        lblWeek = createMetricCard("Wöchentliche Änderung", "—");
        lblMonth = createMetricCard("Monatliche Änderung", "—");
        lblYear = createMetricCard("Jährliche Änderung", "—");
        top.add(lblTotal.getParent());
        top.add(lblWeek.getParent());
        top.add(lblMonth.getParent());
        top.add(lblYear.getParent());

        JPanel center = new JPanel(new GridLayout(1, 2, 12, 12));
        lineChart = new LineChartPanel(snapshotModel::getSnapshots);
        donutChart = new DonutChartPanel(() -> latestOrPositionsStocks(), () -> latestOrPositionsCrypto());
        center.add(wrapCard(lineChart, "Gesamtwert Verlauf"));
        center.add(wrapCard(donutChart, "Aktien vs. Krypto"));

        JPanel bottom = buildAddSnapshotPanel();

        root.add(top, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildAddSnapshotPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(12, 0, 0, 0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(230, 230, 230)),
                        new EmptyBorder(12, 12, 12, 12))));

        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel lblTitle = new JLabel("Neuen Snapshot hinzufügen");
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD, 14f));
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 8;
        panel.add(lblTitle, gc);

        gc.gridwidth = 1;
        gc.gridy = 1; gc.gridx = 0; panel.add(new JLabel("Datum (yyyy-MM-dd)"), gc);
        JTextField tfDate = new JTextField(DATE_FMT.format(LocalDate.now()));
        gc.gridx = 1; gc.weightx = 1; panel.add(tfDate, gc);

        gc.gridy = 2; gc.gridx = 0; gc.weightx = 0; panel.add(new JLabel("Gesamtwert"), gc);
        JTextField tfTotal = new JTextField();
        gc.gridx = 1; gc.weightx = 1; panel.add(tfTotal, gc);

        gc.gridy = 1; gc.gridx = 2; gc.weightx = 0; panel.add(new JLabel("Aktien"), gc);
        JTextField tfStocks = new JTextField();
        gc.gridx = 3; gc.weightx = 1; panel.add(tfStocks, gc);

        gc.gridy = 2; gc.gridx = 2; gc.weightx = 0; panel.add(new JLabel("Krypto"), gc);
        JTextField tfCrypto = new JTextField();
        gc.gridx = 3; gc.weightx = 1; panel.add(tfCrypto, gc);

        JButton btnAdd = new JButton("Speichern");
        btnAdd.addActionListener(e -> {
            try {
                LocalDate d = LocalDate.parse(tfDate.getText().trim(), DATE_FMT);
                double total = parseNumber(tfTotal.getText());
                double stocks = parseNumber(tfStocks.getText());
                double crypto = parseNumber(tfCrypto.getText());
                if (total < 0 || stocks < 0 || crypto < 0) throw new IllegalArgumentException("Negative Werte sind nicht erlaubt.");
                if (Math.abs((stocks + crypto) - total) > 0.01) {
                    int choice = JOptionPane.showConfirmDialog(this,
                            "Hinweis: Aktien + Krypto "+CURRENCY.format(stocks+crypto)+" ≠ Gesamt " + CURRENCY.format(total) +
                                    "\nTrotzdem speichern?", "Abweichung", JOptionPane.YES_NO_OPTION);
                    if (choice != JOptionPane.YES_OPTION) return;
                }
                Snapshot s = new Snapshot(d, total, stocks, crypto);
                snapshotModel.addSnapshot(s);
                DataStore.saveSnapshots(snapshotModel.getSnapshots());
                refreshDashboard();
                lineChart.repaint();
                donutChart.repaint();
                JOptionPane.showMessageDialog(this, "Snapshot gespeichert.");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Eingabe prüfen: " + ex.getMessage(),
                        "Fehler", JOptionPane.ERROR_MESSAGE);
            }
        });
        gc.gridy = 1; gc.gridx = 4; gc.weightx = 0; panel.add(btnAdd, gc);

        JButton btnExport = new JButton("CSV öffnen");
        btnExport.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(SNAPSHOTS_CSV.toFile());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "CSV konnte nicht geöffnet werden: " + ex.getMessage());
            }
        });
        gc.gridy = 2; gc.gridx = 4; panel.add(btnExport, gc);

        return panel;
    }

    private JPanel buildSnapshotsTab() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JTable table = new JTable(snapshotModel);
        table.setFillsViewportHeight(true);
        table.setRowHeight(28);
        table.setAutoCreateRowSorter(true);
        alignRightColumns(table, new int[]{1,2,3});
        TableRowSorter<?> sorter = (TableRowSorter<?>) table.getRowSorter();
        sorter.toggleSortOrder(0); // sort by date
        JScrollPane scroll = new JScrollPane(table);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnSave = new JButton("Änderungen speichern");
        btnSave.addActionListener(e -> {
            DataStore.saveSnapshots(snapshotModel.getSnapshots());
            refreshDashboard();
            lineChart.repaint();
            donutChart.repaint();
            JOptionPane.showMessageDialog(this, "Snapshots gespeichert.");
        });
        JButton btnAdd = new JButton("Zeile hinzufügen");
        btnAdd.addActionListener(e -> {
            snapshotModel.addSnapshot(new Snapshot(LocalDate.now(), 0, 0, 0));
        });
        JButton btnRemove = new JButton("Ausgewählte löschen");
        btnRemove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                int modelRow = table.convertRowIndexToModel(row);
                snapshotModel.removeAt(modelRow);
            }
        });
        buttons.add(btnSave);
        buttons.add(btnAdd);
        buttons.add(btnRemove);

        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildPositionsTab() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JTable table = new JTable(positionModel);
        table.setFillsViewportHeight(true);
        table.setRowHeight(28);
        table.setAutoCreateRowSorter(true);
        alignRightColumns(table, new int[]{3,4,5,6,7});

        // Dropdown-Editor für Kategorie
        JComboBox<Category> catCombo = new JComboBox<>(Category.values());
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(catCombo));

        // Tooltips für P/L
        table.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row >= 0 && col == 7) {
                    int modelRow = table.convertRowIndexToModel(row);
                    Position p = positionModel.getPositions().get(modelRow);
                    double pnl = (p.currentPrice - p.buyPrice) * p.units;
                    table.setToolTipText("Absoluter P/L: " + CURRENCY.format(pnl));
                } else {
                    table.setToolTipText(null);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(table);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnSave = new JButton("Änderungen speichern");
        btnSave.addActionListener(e -> {
            DataStore.savePositions(positionModel.getPositions());
            donutChart.repaint();
            refreshDashboard();
            JOptionPane.showMessageDialog(this, "Positionen gespeichert.");
        });
        JButton btnAdd = new JButton("Position hinzufügen");
        btnAdd.addActionListener(e -> {
            positionModel.addPosition(new Position(Category.STOCK, "", "", 0, 0, 0));
        });
        JButton btnRemove = new JButton("Ausgewählte löschen");
        btnRemove.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                positionModel.removeAt(table.convertRowIndexToModel(row));
            }
        });
        JButton btnOpenCsv = new JButton("CSV öffnen");
        btnOpenCsv.addActionListener(e -> {
            try { Desktop.getDesktop().open(POSITIONS_CSV.toFile()); }
            catch (IOException ex) { JOptionPane.showMessageDialog(this, "CSV konnte nicht geöffnet werden: " + ex.getMessage()); }
        });

        buttons.add(btnSave); buttons.add(btnAdd); buttons.add(btnRemove); buttons.add(btnOpenCsv);

        // Summenleiste
        JPanel totals = new JPanel(new GridLayout(1, 4, 12, 12));
        totals.setBorder(new EmptyBorder(12, 0, 0, 0));
        totals.add(makeTotalCard("Positionswert gesamt", () -> CURRENCY.format(positionModel.totalValue())));
        totals.add(makeTotalCard("Aktien gesamt", () -> CURRENCY.format(positionModel.totalBy(Category.STOCK))));
        totals.add(makeTotalCard("Krypto gesamt", () -> CURRENCY.format(positionModel.totalBy(Category.CRYPTO))));
        totals.add(makeTotalCard("Durchschn. Kaufkurs (gew.)", () -> safeFmt(positionModel.weightedAvgBuy())));

        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.NORTH);
        root.add(totals, BorderLayout.SOUTH);
        return root;
    }

    private JPanel makeTotalCard(String title, SupplierString valueSupplier) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(230,230,230)), new EmptyBorder(10,10,10,10)));
        JLabel t = new JLabel(title);
        t.setFont(t.getFont().deriveFont(Font.PLAIN, 12f));
        JLabel v = new JLabel(valueSupplier.get());
        v.setFont(v.getFont().deriveFont(Font.BOLD, 16f));
        card.add(t, BorderLayout.NORTH);
        card.add(v, BorderLayout.CENTER);

        // Auto-Refresh bei Modelländerung (einfach: klick zum Aktualisieren)
        card.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { v.setText(valueSupplier.get()); }});
        return card;
    }

    private static void alignRightColumns(JTable table, int[] cols) {
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c : cols) table.getColumnModel().getColumn(c).setCellRenderer(right);
    }

    private JLabel createMetricCard(String title, String initialValue) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(225,225,225)), new EmptyBorder(12, 12, 12, 12)));
        card.setBackground(new Color(250, 250, 252));

        JLabel lblTitle = new JLabel(title);
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.PLAIN, 12f));
        JLabel lblValue = new JLabel(initialValue);
        lblValue.setFont(lblValue.getFont().deriveFont(Font.BOLD, 20f));

        card.add(lblTitle, BorderLayout.NORTH);
        card.add(lblValue, BorderLayout.CENTER);

        return lblValue; // Wir geben das Label zurück, behalten aber das Panel als Parent
    }

    private JPanel wrapCard(JComponent content, String title) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(225,225,225)), new EmptyBorder(12, 12, 12, 12)));
        JLabel lbl = new JLabel(title);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 14f));
        card.add(lbl, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private void refreshDashboard() {
        Optional<Snapshot> latest = snapshotModel.latest();
        lblTotal.setText(latest.map(s -> CURRENCY.format(s.total)).orElse("—"));

        lblWeek.setText(formatDelta(snapshotModel.deltaRelativeDays(7)));
        lblMonth.setText(formatDelta(snapshotModel.deltaRelativeMonths(1)));
        lblYear.setText(formatDelta(snapshotModel.deltaRelativeYears(1)));
    }

    private String formatDelta(Optional<Delta> d) {
        if (d.isEmpty()) return "—";
        Delta x = d.get();
        String signCurrency = (x.diff >= 0 ? "+" : "") + CURRENCY.format(x.diff);
        return signCurrency + "  (" + PCT.format(x.pct) + ")";
    }

    private double latestOrPositionsStocks() {
        return snapshotModel.latest().map(s -> s.stocks)
                .orElseGet(() -> positionModel.totalBy(Category.STOCK));
    }
    private double latestOrPositionsCrypto() {
        return snapshotModel.latest().map(s -> s.crypto)
                .orElseGet(() -> positionModel.totalBy(Category.CRYPTO));
    }

    private static double parseNumber(String s) {
        if (s == null || s.isBlank()) return 0.0;
        s = s.trim().replace(".", ""); // Tausenderpunkte entfernen
        s = s.replace(',', '.'); // deutsches Komma -> Punkt
        return Double.parseDouble(s);
    }

    private static String safeFmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "—";
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(LOCALE)).format(v);
    }

    // ------------------------- Datenklassen & Modelle -------------------------

    enum Category { STOCK, CRYPTO }

    static class Snapshot {
        final LocalDate date;
        double total;
        double stocks;
        double crypto;
        Snapshot(LocalDate date, double total, double stocks, double crypto) {
            this.date = date; this.total = total; this.stocks = stocks; this.crypto = crypto;
        }
    }

    static class Position {
        Category category;
        String name;
        String ticker;
        double units;
        double buyPrice;
        double currentPrice;
        Position(Category category, String name, String ticker, double units, double buyPrice, double currentPrice) {
            this.category = category; this.name = name; this.ticker = ticker;
            this.units = units; this.buyPrice = buyPrice; this.currentPrice = currentPrice;
        }
        double value() { return units * currentPrice; }
        double pnlPct() {
            if (buyPrice == 0) return 0;
            return (currentPrice - buyPrice) / buyPrice;
        }
    }

    static class Delta { double diff; double pct; Delta(double d, double p){diff=d;pct=p;} }

    static class SnapshotTableModel extends AbstractTableModel {
        private final List<Snapshot> list;
        private final String[] cols = {"Datum", "Gesamt", "Aktien", "Krypto"};
        SnapshotTableModel(List<Snapshot> list) { this.list = new ArrayList<>(list); sortByDate(); }
        List<Snapshot> getSnapshots() { return list; }
        public int getRowCount() { return list.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }
        public Class<?> getColumnClass(int c) { return c==0?String.class:Double.class; }
        public boolean isCellEditable(int r, int c) { return true; }
        public Object getValueAt(int r, int c) {
            Snapshot s = list.get(r);
            return switch (c) {
                case 0 -> s.date.format(DATE_FMT);
                case 1 -> s.total;
                case 2 -> s.stocks;
                case 3 -> s.crypto;
                default -> null;
            };
        }
        public void setValueAt(Object aValue, int r, int c) {
            Snapshot s = list.get(r);
            try {
                switch (c) {
                    case 0 -> s.total = s.total; // Datum unten ändern
                    case 1 -> s.total = toDouble(aValue);
                    case 2 -> s.stocks = toDouble(aValue);
                    case 3 -> s.crypto = toDouble(aValue);
                }
                if (c==0) {
                    LocalDate d = LocalDate.parse(String.valueOf(aValue), DATE_FMT);
                    list.set(r, new Snapshot(d, s.total, s.stocks, s.crypto));
                    sortByDate();
                }
                fireTableRowsUpdated(0, getRowCount()-1);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, "Ungültige Eingabe: " + ex.getMessage());
            }
        }
        private void sortByDate(){ list.sort(Comparator.comparing(s -> s.date)); }
        private double toDouble(Object o){
            if (o instanceof Number) return ((Number)o).doubleValue();
            return parseNumber(String.valueOf(o));
        }
        void addSnapshot(Snapshot s){ list.add(s); sortByDate(); fireTableDataChanged(); }
        void removeAt(int idx){ list.remove(idx); fireTableDataChanged(); }

        Optional<Snapshot> latest(){ return list.isEmpty()?Optional.empty():Optional.of(list.get(list.size()-1)); }
        Optional<Snapshot> previous(){ return list.size()<2?Optional.empty():Optional.of(list.get(list.size()-2)); }

        Optional<Delta> deltaRelativeDays(int days){
            if (list.isEmpty()) return Optional.empty();
            Snapshot ref = list.get(list.size()-1);
            LocalDate target = ref.date.minusDays(days);
            Snapshot close = closestTo(target);
            if (close==null || close==ref) return Optional.empty();
            return makeDelta(ref.total, close.total);
        }
        Optional<Delta> deltaRelativeMonths(int months){
            if (list.isEmpty()) return Optional.empty();
            Snapshot ref = list.get(list.size()-1);
            LocalDate target = ref.date.minusMonths(months);
            Snapshot close = closestTo(target);
            if (close==null || close==ref) return Optional.empty();
            return makeDelta(ref.total, close.total);
        }
        Optional<Delta> deltaRelativeYears(int years){
            if (list.isEmpty()) return Optional.empty();
            Snapshot ref = list.get(list.size()-1);
            LocalDate target = ref.date.minusYears(years);
            Snapshot close = closestTo(target);
            if (close==null || close==ref) return Optional.empty();
            return makeDelta(ref.total, close.total);
        }
        private Optional<Delta> makeDelta(double now, double then){
            double diff = now - then;
            double pct = (then==0)?0:(diff/then);
            return Optional.of(new Delta(diff, pct));
        }
        private Snapshot closestTo(LocalDate target){
            long best = Long.MAX_VALUE; Snapshot bestS = null;
            for (Snapshot s: list){
                long d = Math.abs(ChronoUnit.DAYS.between(target, s.date));
                if (d < best){ best = d; bestS = s; }
            }
            return bestS;
        }
    }

    static class PositionTableModel extends AbstractTableModel {
        private final List<Position> list;
        private final String[] cols = {"Kategorie", "Name", "Ticker", "Stück", "Kaufkurs", "Akt. Kurs", "Wert", "P/L %"};
        PositionTableModel(List<Position> positions){ this.list = new ArrayList<>(positions); }
        List<Position> getPositions(){ return list; }
        public int getRowCount(){ return list.size(); }
        public int getColumnCount(){ return cols.length; }
        public String getColumnName(int c){ return cols[c]; }
        public Class<?> getColumnClass(int c){
            return switch (c){
                case 0 -> Category.class;
                case 3,4,5,6,7 -> Double.class;
                default -> String.class;
            };
        }
        public boolean isCellEditable(int r, int c){ return c <= 5; }
        public Object getValueAt(int r, int c){
            Position p = list.get(r);
            return switch (c){
                case 0 -> p.category;
                case 1 -> p.name;
                case 2 -> p.ticker;
                case 3 -> p.units;
                case 4 -> p.buyPrice;
                case 5 -> p.currentPrice;
                case 6 -> p.value();
                case 7 -> p.pnlPct();
                default -> null;
            };
        }
        public void setValueAt(Object v, int r, int c){
            Position p = list.get(r);
            try{
                switch (c){
                    case 0 -> p.category = (Category) v;
                    case 1 -> p.name = String.valueOf(v);
                    case 2 -> p.ticker = String.valueOf(v);
                    case 3 -> p.units = toDouble(v);
                    case 4 -> p.buyPrice = toDouble(v);
                    case 5 -> p.currentPrice = toDouble(v);
                }
                fireTableRowsUpdated(r, r);
            } catch (Exception ex){
                JOptionPane.showMessageDialog(null, "Ungültige Eingabe: " + ex.getMessage());
            }
        }
        void addPosition(Position p){ list.add(p); fireTableDataChanged(); }
        void removeAt(int idx){ list.remove(idx); fireTableDataChanged(); }

        double totalValue(){ return list.stream().mapToDouble(Position::value).sum(); }
        double totalBy(Category cat){ return list.stream().filter(p->p.category==cat).mapToDouble(Position::value).sum(); }
        double weightedAvgBuy(){
            double sumVal = 0, sumQty = 0;
            for (Position p: list){ sumVal += p.buyPrice * p.units; sumQty += p.units; }
            return sumQty==0?Double.NaN:sumVal/sumQty;
        }
        private double toDouble(Object o){
            if (o instanceof Number) return ((Number)o).doubleValue();
            return parseNumber(String.valueOf(o));
        }
    }

    static class DataStore {
        static List<Snapshot> loadSnapshots(){
            List<Snapshot> list = new ArrayList<>();
            if (Files.notExists(SNAPSHOTS_CSV)) return list;
            try (BufferedReader br = Files.newBufferedReader(SNAPSHOTS_CSV, StandardCharsets.UTF_8)){
                String line; boolean first=true;
                while ((line = br.readLine()) != null){
                    if (first){ first=false; continue; }
                    String[] t = line.split(",");
                    if (t.length < 4) continue;
                    LocalDate d = LocalDate.parse(t[0].trim(), DATE_FMT);
                    double total = parseNumber(t[1]);
                    double stocks = parseNumber(t[2]);
                    double crypto = parseNumber(t[3]);
                    list.add(new Snapshot(d, total, stocks, crypto));
                }
                list.sort(Comparator.comparing(s->s.date));
            } catch (IOException e){ e.printStackTrace(); }
            return list;
        }
        static void saveSnapshots(List<Snapshot> list){
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(SNAPSHOTS_CSV, StandardCharsets.UTF_8))){
                pw.println("date,total,stocks,crypto");
                for (Snapshot s: list){
                    pw.printf(Locale.ROOT, "%s,%.2f,%.2f,%.2f%n", s.date.format(DATE_FMT), s.total, s.stocks, s.crypto);
                }
            } catch (IOException e){ e.printStackTrace(); }
        }
        static List<Position> loadPositions(){
            List<Position> list = new ArrayList<>();
            if (Files.notExists(POSITIONS_CSV)) return list;
            try (BufferedReader br = Files.newBufferedReader(POSITIONS_CSV, StandardCharsets.UTF_8)){
                String line; boolean first=true;
                while ((line = br.readLine()) != null){
                    if (first){ first=false; continue; }
                    String[] t = line.split(",");
                    if (t.length < 6) continue;
                    Category cat = Category.valueOf(t[0].trim());
                    String name = t[1].trim();
                    String ticker = t[2].trim();
                    double units = parseNumber(t[3]);
                    double buy = parseNumber(t[4]);
                    double curr = parseNumber(t[5]);
                    list.add(new Position(cat, name, ticker, units, buy, curr));
                }
            } catch (IOException e){ e.printStackTrace(); }
            return list;
        }
        static void savePositions(List<Position> list){
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(POSITIONS_CSV, StandardCharsets.UTF_8))){
                pw.println("category,name,ticker,units,buyPrice,currentPrice");
                for (Position p: list){
                    pw.printf(Locale.ROOT, "%s,%s,%s,%.6f,%.4f,%.4f%n",
                            p.category.name(), escapeCsv(p.name), escapeCsv(p.ticker), p.units, p.buyPrice, p.currentPrice);
                }
            } catch (IOException e){ e.printStackTrace(); }
        }
        private static String escapeCsv(String s){
            if (s==null) return "";
            if (s.contains(",") || s.contains("\"")){
                s = s.replace("\"", "\"\"");
                return "\""+s+"\"";
            }
            return s;
        }
    }

    // ------------------------- Charts ----------------------------------------

    static class LineChartPanel extends JPanel {
        private final SupplierSnapshots supplier;
        LineChartPanel(SupplierSnapshots supplier){ this.supplier = supplier; setPreferredSize(new Dimension(500, 340)); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(); int h = getHeight();
            int left = 60, right = 20, top = 20, bottom = 40;
            int x0 = left, y0 = h - bottom, x1 = w - right, y1 = top;

            // Hintergrund
            g2.setColor(new Color(252,252,255)); g2.fillRect(0,0,w,h);

            List<Snapshot> list = new ArrayList<>(supplier.get());
            if (list.isEmpty()){
                drawNoData(g2, w, h);
                g2.dispose(); return;
            }

            // Bereich und Skalen
            double minVal = list.stream().mapToDouble(s->s.total).min().orElse(0);
            double maxVal = list.stream().mapToDouble(s->s.total).max().orElse(1);
            if (minVal==maxVal){ maxVal = minVal + 1; }

            LocalDate minDate = list.get(0).date;
            LocalDate maxDate = list.get(list.size()-1).date;
            long days = Math.max(1, ChronoUnit.DAYS.between(minDate, maxDate));

            // Achsen
            g2.setColor(new Color(230,230,235));
            g2.drawLine(x0, y0, x1, y0);
            g2.drawLine(x0, y0, x0, y1);

            // Grid + Labels (Y)
            g2.setFont(getFont().deriveFont(11f));
            int gridLines = 5;
            for (int i=0; i<=gridLines; i++){
                int yy = y0 - i * (y0 - y1) / gridLines;
                g2.setColor(new Color(240,240,245));
                g2.drawLine(x0, yy, x1, yy);
                double val = minVal + i * (maxVal - minVal) / gridLines;
                g2.setColor(new Color(120,120,130));
                g2.drawString(formatCurrency(val), 6, yy+4);
            }

            // Datenpfad
            GeneralPath path = new GeneralPath();
            for (int i=0; i<list.size(); i++){
                Snapshot s = list.get(i);
                double x = x0 + (ChronoUnit.DAYS.between(minDate, s.date) * 1.0 / days) * (x1 - x0);
                double norm = (s.total - minVal) / (maxVal - minVal);
                double y = y0 - norm * (y0 - y1);
                if (i==0) path.moveTo(x, y); else path.lineTo(x, y);
            }

            // Linie
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(new Color(60,120,255));
            g2.draw(path);

            // Punkte
            g2.setColor(new Color(60,120,255));
            for (Snapshot s: list){
                double x = x0 + (ChronoUnit.DAYS.between(minDate, s.date) * 1.0 / days) * (x1 - x0);
                double norm = (s.total - minVal) / (maxVal - minVal);
                double y = y0 - norm * (y0 - y1);
                g2.fillOval((int)x-3,(int)y-3,6,6);
            }

            // X-Achse Labels (Min/Max + Mitte)
            g2.setColor(new Color(120,120,130));
            g2.drawString(minDate.format(DATE_FMT), x0, y0+18);
            g2.drawString(maxDate.format(DATE_FMT), x1-70, y0+18);
            LocalDate mid = minDate.plusDays(days/2);
            g2.drawString(mid.format(DATE_FMT), (x0+x1)/2-40, y0+18);

            g2.dispose();
        }
        private void drawNoData(Graphics2D g2, int w, int h){
            g2.setColor(new Color(245,245,250));
            g2.fillRoundRect(8,8,w-16,h-16,16,16);
            g2.setColor(new Color(140,140,150));
            String s = "Noch keine Daten – füge einen Snapshot hinzu.";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, (w - fm.stringWidth(s))/2, h/2);
        }
        private String formatCurrency(double v){
            return NumberFormat.getCurrencyInstance(LOCALE).format(v);
        }
    }

    static class DonutChartPanel extends JPanel {
        private final SupplierDouble stocksSupplier;
        private final SupplierDouble cryptoSupplier;
        DonutChartPanel(SupplierDouble stocks, SupplierDouble crypto){ this.stocksSupplier = stocks; this.cryptoSupplier = crypto; setPreferredSize(new Dimension(400, 340)); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(252,252,255)); g2.fillRect(0,0,w,h);

            double stocks = Math.max(0, stocksSupplier.get());
            double crypto = Math.max(0, cryptoSupplier.get());
            double total = stocks + crypto;
            if (total <= 0){
                drawNoData(g2, w, h);
                g2.dispose(); return;
            }

            int size = Math.min(w, h) - 60;
            int x = (w - size)/2;
            int y = (h - size)/2;

            double angleStocks = 360 * (stocks/total);
            double angleCrypto = 360 - angleStocks;

            Shape outer = new Arc2D.Double(x, y, size, size, 90, -angleStocks, Arc2D.PIE);
            Shape outer2 = new Arc2D.Double(x, y, size, size, 90-angleStocks, -angleCrypto, Arc2D.PIE);

            g2.setColor(new Color(60,120,255)); g2.fill(outer);
            g2.setColor(new Color(255,165,60)); g2.fill(outer2);

            // Loch
            int hole = (int)(size*0.56);
            g2.setColor(new Color(252,252,255));
            g2.fillOval(x + (size-hole)/2, y + (size-hole)/2, hole, hole);

            // Legende & Werte
            g2.setColor(new Color(40,40,45));
            g2.setFont(getFont().deriveFont(Font.BOLD, 18f));
            String totalStr = CURRENCY.format(total);
            drawCentered(g2, totalStr, w/2, y + size/2 - 6);

            g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            String s1 = "Aktien: " + CURRENCY.format(stocks) + "  (" + PCT.format(stocks/total) + ")";
            String s2 = "Krypto: " + CURRENCY.format(crypto) + "  (" + PCT.format(crypto/total) + ")";
            drawCentered(g2, s1, w/2, y + size/2 + 16);
            drawCentered(g2, s2, w/2, y + size/2 + 32);

            g2.dispose();
        }
        private void drawNoData(Graphics2D g2, int w, int h){
            g2.setColor(new Color(245,245,250));
            g2.fillRoundRect(8,8,w-16,h-16,16,16);
            g2.setColor(new Color(140,140,150));
            String s = "Keine Aufteilung vorhanden.";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, (w - fm.stringWidth(s))/2, h/2);
        }
        private void drawCentered(Graphics2D g2, String s, int cx, int y){
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, cx - fm.stringWidth(s)/2, y);
        }
    }

    // ------------------------- Supplier Functional ----------------------------

    interface SupplierSnapshots { List<Snapshot> get(); }
    interface SupplierDouble { double get(); }
    interface SupplierString { String get(); }
}
