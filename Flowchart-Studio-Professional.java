package FlowchartStudio;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class FlowchartStudio extends Application {

    private Pane canvas;
    private Group zoomGroup;
    private Object selectedElement = null;
    private final List<ArrowConnector> connectors = new ArrayList<>();
    private static final int GRID_SIZE = 20;
    private static final double CANVAS_SIZE = 3000;
    private double lastMouseX, lastMouseY;

    public enum NodeType { START_END, PROCESS, DECISION }

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();

        // --- ツールバー ---
        ToolBar toolBar = new ToolBar();
        ComboBox<NodeType> typeCombo = new ComboBox<>(FXCollections.observableArrayList(NodeType.values()));
        typeCombo.setValue(NodeType.PROCESS);
        Button addBtn = new Button("ノード追加");
        Button saveBtn = new Button("保存");
        Button loadBtn = new Button("読込");
        Button exportBtn = new Button("画像出力");
        Label zoomLabel = new Label("Zoom: 100%");
        
        toolBar.getItems().addAll(
            new Label("形状:"), typeCombo, addBtn, 
            new Separator(), saveBtn, loadBtn, exportBtn, 
            new Separator(), zoomLabel
        );

        // --- キャンバス設定 ---
        canvas = new Pane();
        canvas.setPrefSize(CANVAS_SIZE, CANVAS_SIZE);
        canvas.setStyle("-fx-background-color: white;");
        drawGridBackground();

        zoomGroup = new Group(canvas);
        ScrollPane scrollPane = new ScrollPane(zoomGroup);
        scrollPane.setPannable(true);

        // ズーム（マウス中心）
        canvas.setOnScroll(e -> {
            if (e.isControlDown()) {
                double zoomFactor = (e.getDeltaY() > 0) ? 1.1 : 0.9;
                double oldScale = zoomGroup.getScaleX();
                double newScale = oldScale * zoomFactor;
                if (newScale > 0.2 && newScale < 5.0) {
                    zoomGroup.setScaleX(newScale);
                    zoomGroup.setScaleY(newScale);
                    zoomLabel.setText(String.format("Zoom: %.0f%%", newScale * 100));
                }
                e.consume();
            }
        });

        // 右クリックでノード追加用メニュー
        ContextMenu canvasMenu = new ContextMenu();
        for (NodeType type : NodeType.values()) {
            MenuItem item = new MenuItem(type + " を追加");
            item.setOnAction(ev -> addNewNode("新規工程", lastMouseX, lastMouseY, type));
            canvasMenu.getItems().add(item);
        }

        canvas.setOnMousePressed(e -> {
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            if (e.getButton() == MouseButton.SECONDARY) {
                if (e.getTarget() == canvas || e.getTarget() instanceof Canvas) {
                    canvasMenu.show(canvas, e.getScreenX(), e.getScreenY());
                }
            } else {
                canvasMenu.hide();
                select(null);
            }
        });

        // ボタンアクション
        addBtn.setOnAction(e -> addNewNode("新規工程", 100, 100, typeCombo.getValue()));
        saveBtn.setOnAction(e -> saveToFile(primaryStage));
        loadBtn.setOnAction(e -> loadFromFile(primaryStage));
        exportBtn.setOnAction(e -> exportAsImage(primaryStage));

        // ショートカットキー
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) deleteSelected();
            if (e.isControlDown() && e.getCode() == KeyCode.D) duplicateSelected();
        });

        root.setTop(toolBar);
        root.setCenter(scrollPane);

        primaryStage.setScene(new Scene(root, 1100, 800));
        primaryStage.setTitle("Flowchart Studio Professional");
        primaryStage.show();
    }

    private void drawGridBackground() {
        Canvas gridCanvas = new Canvas(CANVAS_SIZE, CANVAS_SIZE);
        GraphicsContext gc = gridCanvas.getGraphicsContext2D();
        gc.setStroke(Color.web("#F0F0F0"));
        for (double x = 0; x <= CANVAS_SIZE; x += GRID_SIZE) gc.strokeLine(x, 0, x, CANVAS_SIZE);
        for (double y = 0; y <= CANVAS_SIZE; y += GRID_SIZE) gc.strokeLine(0, y, CANVAS_SIZE, y);
        gridCanvas.setMouseTransparent(true);
        canvas.getChildren().add(gridCanvas);
    }

    private void addNewNode(String text, double x, double y, NodeType type) {
        DraggableNode node = new DraggableNode(text, x, y, type);
        canvas.getChildren().add(node);
    }

    private void duplicateSelected() {
        if (selectedElement instanceof DraggableNode n) {
            addNewNode(n.textLabel.getText(), n.getLayoutX() + 30, n.getLayoutY() + 30, n.type);
        }
    }

    private void deleteSelected() {
        if (selectedElement instanceof DraggableNode node) {
            List<ArrowConnector> toRemove = connectors.stream()
                .filter(c -> c.source == node || c.target == node).toList();
            toRemove.forEach(this::removeConnector);
            canvas.getChildren().remove(node);
        } else if (selectedElement instanceof ArrowConnector conn) {
            removeConnector(conn);
        }
        selectedElement = null;
    }

    private void removeConnector(ArrowConnector c) {
        canvas.getChildren().remove(c);
        connectors.remove(c);
        c.unbind();
    }

    private void select(Object obj) {
        if (selectedElement instanceof DraggableNode n) n.setHighlight(false);
        if (selectedElement instanceof ArrowConnector c) c.setHighlight(false);
        selectedElement = obj;
        if (obj instanceof DraggableNode n) n.setHighlight(true);
        if (obj instanceof ArrowConnector c) c.setHighlight(true);
    }

    // --- 保存・読込・出力ロジック ---

    private void saveToFile(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;
        try {
            StringBuilder sb = new StringBuilder("[\n");
            List<DraggableNode> nodeList = canvas.getChildren().stream()
                .filter(n -> n instanceof DraggableNode).map(n -> (DraggableNode)n).toList();
            for (int i = 0; i < nodeList.size(); i++) {
                DraggableNode n = nodeList.get(i);
                sb.append(String.format(" {\"text\":\"%s\",\"x\":%f,\"y\":%f,\"type\":\"%s\"}%s\n",
                    n.textLabel.getText(), n.getLayoutX(), n.getLayoutY(), n.type, (i < nodeList.size()-1 ? ",":"")));
            }
            sb.append("]");
            Files.writeString(file.toPath(), sb.toString());
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void loadFromFile(Stage stage) {
        FileChooser fc = new FileChooser();
        File file = fc.showOpenDialog(stage);
        if (file == null) return;
        try {
            canvas.getChildren().removeIf(n -> n instanceof DraggableNode || n instanceof ArrowConnector);
            connectors.clear();
            String content = Files.readString(file.toPath()).replace("[","").replace("]","").trim();
            for (String obj : content.split("},")) {
                if (obj.isBlank()) continue;
                addNewNode(extract(obj, "text"), Double.parseDouble(extract(obj, "x")), 
                           Double.parseDouble(extract(obj, "y")), NodeType.valueOf(extract(obj, "type")));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private String extract(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\""+key+"\":\"?([^,\"}]+)\"?").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private void exportAsImage(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = fc.showSaveDialog(stage);
        if (file != null) {
            WritableImage img = canvas.snapshot(new SnapshotParameters(), null);
            try { ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", file); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // --- 内部クラス: ノード ---

    class DraggableNode extends StackPane {
        final NodeType type;
        final Shape shape;
        final Text textLabel;
        final TextField editField = new TextField();
        private double anchorX, anchorY;
        private static DraggableNode connectionSource = null;
        private ContextMenu menu;

        public DraggableNode(String text, double x, double y, NodeType type) {
            this.type = type;
            this.shape = createShape(type);
            this.textLabel = new Text(text);
            this.editField.setVisible(false);
            this.editField.setMaxWidth(80);

            getChildren().addAll(shape, textLabel, editField);
            setLayoutX(x); setLayoutY(y);
            
            createContextMenu();
            setupEvents();
        }

        private void createContextMenu() {
            menu = new ContextMenu();
            MenuItem edit = new MenuItem("編集"); edit.setOnAction(e -> startEdit());
            MenuItem del = new MenuItem("削除"); del.setOnAction(e -> { select(this); deleteSelected(); });
            Menu colors = new Menu("色変更");
            String[][] cols = {{"白","white"},{"黄","lightyellow"},{"青","aliceblue"}};
            for(String[] c : cols) {
                MenuItem ci = new MenuItem(c[0]); ci.setOnAction(e -> shape.setFill(Color.valueOf(c[1])));
                colors.getItems().add(ci);
            }
            menu.getItems().addAll(edit, colors, new SeparatorMenuItem(), del);
        }

        private void startEdit() {
            textLabel.setVisible(false); editField.setText(textLabel.getText());
            editField.setVisible(true); editField.requestFocus();
        }

        private void setupEvents() {
            setOnContextMenuRequested(e -> menu.show(this, e.getScreenX(), e.getScreenY()));
            setOnMousePressed(e -> {
                if (e.getButton() == MouseButton.SECONDARY) {
                    if (connectionSource == null) { connectionSource = this; setHighlight(true); }
                    else if (connectionSource != this) {
                        ArrowConnector c = new ArrowConnector(connectionSource, this);
                        connectors.add(c); canvas.getChildren().add(1, c);
                        connectionSource.setHighlight(false); connectionSource = null;
                    }
                } else {
                    anchorX = e.getX(); anchorY = e.getY(); select(this);
                }
                e.consume();
            });
            setOnMouseDragged(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    setLayoutX(Math.round((getLayoutX() + e.getX() - anchorX) / GRID_SIZE) * GRID_SIZE);
                    setLayoutY(Math.round((getLayoutY() + e.getY() - anchorY) / GRID_SIZE) * GRID_SIZE);
                }
            });
            setOnMouseClicked(e -> { if (e.getClickCount() == 2) startEdit(); });
            editField.setOnAction(e -> { textLabel.setText(editField.getText()); editField.setVisible(false); textLabel.setVisible(true); });
        }

        private Shape createShape(NodeType type) {
            Shape s = switch (type) {
                case START_END -> new Ellipse(50, 25);
                case DECISION -> new Polygon(0, 30, 50, 0, 100, 30, 50, 60);
                case PROCESS -> new Rectangle(100, 50);
            };
            if (s instanceof Rectangle r) { r.setArcWidth(15); r.setArcHeight(15); }
            s.setFill(Color.WHITE); s.setStroke(Color.BLACK); s.setStrokeWidth(1.5);
            return s;
        }

        public void setHighlight(boolean b) { shape.setStroke(b ? Color.DODGERBLUE : Color.BLACK); shape.setStrokeWidth(b ? 3 : 1.5); }
    }

    // --- 内部クラス: 接続線 ---

    class ArrowConnector extends Group {
        DraggableNode source, target;
        private final Line line = new Line();
        private final Polygon arrow = new Polygon(0,0, -10,-5, -10,5);
        private final Text label = new Text("");
        private final TextField edit = new TextField();
        private ContextMenu menu;

        public ArrowConnector(DraggableNode s, DraggableNode t) {
            this.source = s; this.target = t;
            line.setStrokeWidth(2); edit.setVisible(false); edit.setPrefWidth(50);
            getChildren().addAll(line, arrow, label, edit);
            createContextMenu(); rebind();
            
            label.setOnMouseClicked(e -> { if(e.getClickCount()==2) startEdit(); });
            edit.setOnAction(e -> { label.setText(edit.getText()); edit.setVisible(false); label.setVisible(true); });
            setOnContextMenuRequested(e -> menu.show(this, e.getScreenX(), e.getScreenY()));
            setOnMousePressed(e -> { select(this); e.consume(); });
        }

        private void createContextMenu() {
            menu = new ContextMenu();
            MenuItem rev = new MenuItem("反転"); rev.setOnAction(e -> {
                DraggableNode tmp = source; source = target; target = tmp; rebind();
            });
            Menu quick = new Menu("ラベル");
            for(String p : new String[]{"Yes","No","OK"}) {
                MenuItem mi = new MenuItem(p); mi.setOnAction(e -> label.setText(p));
                quick.getItems().add(mi);
            }
            MenuItem del = new MenuItem("削除"); del.setOnAction(e -> { select(this); deleteSelected(); });
            menu.getItems().addAll(rev, quick, new SeparatorMenuItem(), del);
        }

        private void startEdit() {
            label.setVisible(false); edit.setText(label.getText());
            edit.setVisible(true); edit.requestFocus();
        }

        private void rebind() {
            line.startXProperty().bind(source.layoutXProperty().add(50));
            line.startYProperty().bind(source.layoutYProperty().add(25));
            line.endXProperty().bind(target.layoutXProperty().add(50));
            line.endYProperty().bind(target.layoutYProperty().add(25));
            label.xProperty().bind(line.startXProperty().add(line.endXProperty()).divide(2).subtract(10));
            label.yProperty().bind(line.startYProperty().add(line.endYProperty()).divide(2).subtract(10));
            edit.layoutXProperty().bind(label.xProperty()); edit.layoutYProperty().bind(label.yProperty().subtract(15));
            line.endXProperty().addListener(o -> updateArrow()); line.endYProperty().addListener(o -> updateArrow());
            updateArrow();
        }

        private void updateArrow() {
            double angle = Math.atan2(line.getEndY()-line.getStartY(), line.getEndX()-line.getStartX());
            arrow.setTranslateX(line.getEndX() - 30 * Math.cos(angle));
            arrow.setTranslateY(line.getEndY() - 30 * Math.sin(angle));
            arrow.setRotate(Math.toDegrees(angle));
        }

        public void setHighlight(boolean b) { line.setStrokeWidth(b ? 4 : 2); line.setStroke(b ? Color.DODGERBLUE : Color.BLACK); }
        public void unbind() { line.startXProperty().unbind(); line.startYProperty().unbind(); line.endXProperty().unbind(); line.endYProperty().unbind(); }
    }

    public static void main(String[] args) { launch(args); }
}
