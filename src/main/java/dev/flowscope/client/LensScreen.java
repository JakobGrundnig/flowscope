package dev.flowscope.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.flowscope.Flowscope;
import dev.flowscope.network.LensActionPayload;
import dev.flowscope.network.LensStatsPayload;
import dev.flowscope.network.OpenLensScreenPayload;
import dev.flowscope.session.ItemBreakdown;
import dev.flowscope.session.RollingRateWindow;
import dev.flowscope.session.SessionState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Live statistics view for one recording. Client-only.
 *
 * Layout is computed in {@link #init()} from {@code width}/{@code height}, which Minecraft already expresses in
 * GUI-scaled pixels. Changing the GUI Scale video setting or resizing the window re-runs init(), so the panel
 * re-fits automatically. When vertical space is short, the graph shrinks and disappears first, then the note is cut
 * to one line; expanded item lists keep at least one row and scroll.
 */
public final class LensScreen extends Screen {
    private static final Identifier ICON_LENS = sprite("icon/lens");
    private static final Identifier ICON_RECORDING = sprite("icon/recording");
    private static final Identifier ICON_STOPPED = sprite("icon/stopped");
    // Nine-slice frames (sizes/borders in the .png.mcmeta files) and 8x8 header icons.
    private static final Identifier PANEL = sprite("widget/lens_panel");
    private static final Identifier TITLE_BAR = sprite("widget/lens_title");
    private static final Identifier INSET = sprite("widget/lens_inset");
    private static final Identifier HEADER = sprite("widget/lens_header");
    private static final Identifier HEADER_HOVER = sprite("widget/lens_header_hover");
    private static final Identifier HDR_IN = sprite("icon/hdr_in");
    private static final Identifier HDR_OUT = sprite("icon/hdr_out");
    private static final Identifier HDR_NET = sprite("icon/hdr_net");
    private static final Identifier HDR_MADE = sprite("icon/hdr_made");
    private static final Identifier HDR_USED = sprite("icon/hdr_used");

    private static final int MAX_PANEL_WIDTH = 280;
    private static final int MARGIN = 4;
    private static final int PAD = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MIN_GRAPH_HEIGHT = 16;
    private static final int MAX_GRAPH_HEIGHT = 56;
    private static final int ROW_H = 18;

    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_MUTED = 0xFFA8B4B8;
    private static final int COL_INC = 0xFF55D17A;
    private static final int COL_DEC = 0xFFE86A5A;
    private static final int COL_NET = 0xFF7FD8E8;
    private static final int COL_MADE = 0xFFB89CF0;
    private static final int COL_USED = 0xFFE8A050;
    private static final int COL_WARN = 0xFFE8C860;
    private static final int COL_GRAPH_AXIS = 0xFF3A4A52;
    private static final int COL_GRAPH_GRID = 0xFF18222A;
    private static final int COL_GRAPH_LABEL = 0xFF5B6E78;
    private static final int COL_INC_CAP = 0xFFA8F5BE;
    private static final int COL_DEC_CAP = 0xFFFFB0A4;
    private static final int COL_ROW_HOVER = 0x30FFFFFF;
    private static final int COL_SCROLL_TRACK = 0xFF263238;
    private static final int COL_SCROLL_THUMB = 0xFF7A8C94;

    /**
     * One row of a list, converted once per stats update: an item (stack set, fluid null, amount in items) or a fluid
     * (fluid set, amount in mB).
     */
    private record ItemRow(ItemStack stack, FluidResource fluid, Component name, long amount) {
        boolean isFluid() {
            return fluid != null;
        }
    }

    /** One collapsible section (header row + optional item list). */
    private static final class Section {
        final int category;
        final int color;
        boolean visible = true;
        boolean expanded;
        List<ItemRow> rows = List.of();
        int moreItems;
        long moreAmount;
        int scroll;
        // layout
        int headerY;
        int listY;
        int listH;

        Section(int category, int color) {
            this.category = category;
            this.color = color;
        }

        int contentRows() {
            return Math.max(1, rows.size() + (moreItems > 0 ? 1 : 0));
        }
    }

    private final int sessionId;
    private final Component faceName;
    private final int sampleIntervalTicks;
    private final int maxSeconds;

    private LensStatsPayload stats;
    private long statsReceivedMillis;
    private boolean closedSent;
    private boolean listsRequested;

    private final Section secIn = new Section(ItemBreakdown.IN, COL_INC);
    private final Section secOut = new Section(ItemBreakdown.OUT, COL_DEC);
    private final Section secNet = new Section(ItemBreakdown.NET, COL_NET);
    private final Section secMade = new Section(ItemBreakdown.PRODUCED, COL_MADE);
    private final Section secUsed = new Section(ItemBreakdown.CONSUMED, COL_USED);
    private final Section[] sections = {secIn, secOut, secNet, secMade, secUsed};

    // Layout (scaled GUI pixels), recomputed by init().
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int titleH;
    private int processLabelY = -1;
    private int graphY;
    private int graphH;
    private int warningY;
    private List<FormattedCharSequence> warningLines = List.of();
    private Button restartButton;
    private Button stopButton;
    /** Inputs the current layout was computed from; a change triggers a re-layout. */
    private int layoutKey = Integer.MIN_VALUE;

    public LensScreen(OpenLensScreenPayload open) {
        super(Component.translatable("flowscope.screen.title", open.blockName()));
        this.sessionId = open.sessionId();
        this.faceName = open.allSides()
                ? Component.translatable("flowscope.face.all")
                : Component.translatable("flowscope.face." + open.face().getSerializedName());
        this.sampleIntervalTicks = open.sampleIntervalTicks();
        this.maxSeconds = open.maxSeconds();
    }

    public int sessionId() {
        return sessionId;
    }

    public void acceptStats(LensStatsPayload payload) {
        this.stats = payload;
        this.statsReceivedMillis = Util.getMillis();
        if (!payload.items().isEmpty()) {
            for (Section s : sections) {
                fill(s, payload.items(s.category));
            }
        }
        if (!payload.breakdownAvailable()) {
            for (Section s : sections) {
                s.expanded = false;
            }
        }
        if (restartButton != null && computeLayoutKey() != layoutKey) {
            rebuildWidgets();
        }
        updateButtons();
    }

    private static void fill(Section section, ItemBreakdown.Summary summary) {
        List<ItemRow> rows = new ArrayList<>(summary.entries().size());
        for (ItemBreakdown.Entry e : summary.entries()) {
            if (e.resource() instanceof FluidResource fluid) {
                rows.add(new ItemRow(ItemStack.EMPTY, fluid, fluid.getHoverName(), e.amount()));
            } else if (e.resource() instanceof ItemResource item) {
                ItemStack stack = item.toStack();
                rows.add(new ItemRow(stack, null, stack.getHoverName(), e.amount()));
            }
        }
        section.rows = rows;
        section.moreItems = summary.moreItems();
        section.moreAmount = summary.moreAmount();
    }

    private boolean isEventMode() {
        return stats != null && stats.eventMode();
    }

    private boolean isProcessing() {
        return stats != null && stats.processing();
    }

    private boolean isRecording() {
        return stats == null || SessionState.byId(stats.state()) == SessionState.RECORDING;
    }

    private boolean breakdownAvailable() {
        return stats != null && stats.breakdownAvailable();
    }

    // ------------------------------------------------------------------ layout

    private int computeLayoutKey() {
        int key = (isEventMode() ? 1 : 0) + (isProcessing() ? 2 : 0) + (stats != null && stats.hiddenTransfers() ? 4 : 0);
        for (Section s : sections) {
            key = key * 31 + (s.expanded ? s.contentRows() : 0);
        }
        return key;
    }

    @Override
    protected void init() {
        layoutKey = computeLayoutKey();
        int lh = font.lineHeight;
        int metricH = lh + 3;
        panelW = Math.max(120, Math.min(MAX_PANEL_WIDTH, width - MARGIN * 2));
        int innerW = panelW - PAD * 2;

        boolean processing = isProcessing();
        secMade.visible = processing;
        secUsed.visible = processing;
        if (!processing) {
            secMade.expanded = false;
            secUsed.expanded = false;
        }

        String warningKey = stats != null && stats.hiddenTransfers() ? "flowscope.screen.hidden_note"
                : isEventMode() ? "flowscope.screen.event_note" : "flowscope.screen.approximate";
        String warningShortKey = isEventMode() ? "flowscope.screen.event_note_short" : "flowscope.screen.approximate_short";
        warningLines = font.split(Component.translatable(warningKey), innerW);

        int headers = 0;
        int expanded = 0;
        for (Section s : sections) {
            if (s.visible) {
                headers++;
                if (s.expanded) {
                    expanded++;
                }
            }
        }

        titleH = Math.max(lh, 16) + 4;
        int top = titleH + PAD + (lh + 2) * 3 + 4;              // title, status, side/sample, mode
        int processLabelH = processing ? lh + 4 : 0;
        int bottom = 4 + 6 + BUTTON_HEIGHT + PAD;                 // gap before note, gap before buttons, buttons
        int fixed = top + metricH * headers + processLabelH + 4 + bottom + warningLines.size() * lh;

        int minLists = expanded * (ROW_H + 2);
        int available = height - MARGIN * 2 - fixed;
        if (available < minLists + MIN_GRAPH_HEIGHT + 4 && warningLines.size() > 1) {
            int before = warningLines.size();
            warningLines = font.split(Component.translatable(warningShortKey), innerW);
            int saved = (before - warningLines.size()) * lh;
            available += saved;
            fixed -= saved;
        }

        // Item lists get space before the graph: one row each first, then more rows round-robin.
        int listSpace = Math.max(minLists, available);
        int[] rowsGiven = new int[sections.length];
        int remaining = listSpace - expanded * 2;
        for (int i = 0; i < sections.length; i++) {
            Section s = sections[i];
            s.listH = 0;
            if (s.visible && s.expanded) {
                rowsGiven[i] = 1;
                remaining -= ROW_H;
            }
        }
        boolean grew = true;
        while (grew && remaining >= ROW_H) {
            grew = false;
            for (int i = 0; i < sections.length && remaining >= ROW_H; i++) {
                Section s = sections[i];
                if (s.visible && s.expanded && rowsGiven[i] < s.contentRows()) {
                    rowsGiven[i]++;
                    remaining -= ROW_H;
                    grew = true;
                }
            }
        }
        int usedByLists = 0;
        for (int i = 0; i < sections.length; i++) {
            if (rowsGiven[i] > 0) {
                sections[i].listH = rowsGiven[i] * ROW_H + 2;
                usedByLists += sections[i].listH;
            }
        }
        int leftover = available - usedByLists;
        graphH = leftover >= MIN_GRAPH_HEIGHT + 4 ? Math.min(MAX_GRAPH_HEIGHT, leftover - 4) : 0;
        panelH = fixed + usedByLists + (graphH > 0 ? graphH + 4 : 0);

        panelX = (width - panelW) / 2;
        panelY = Math.max(MARGIN, (height - panelH) / 2);

        // Vertical positions, top to bottom.
        int y = panelY + top;
        processLabelY = -1;
        for (Section s : sections) {
            if (!s.visible) {
                continue;
            }
            if (s == secMade) {
                processLabelY = y + 2;
                y += processLabelH;
            }
            s.headerY = y;
            y += metricH;
            s.listY = y;
            y += s.listH;
            clampScroll(s);
        }
        y += 4;
        graphY = y;
        if (graphH > 0) {
            y += graphH + 4;
        }
        warningY = y + 4;

        int buttonY = panelY + panelH - PAD - BUTTON_HEIGHT;
        int buttonW = (innerW - 4) / 2;
        restartButton = addRenderableWidget(new LensButton(panelX + PAD, buttonY, buttonW, BUTTON_HEIGHT,
                Component.translatable("flowscope.screen.restart"), b -> send(LensActionPayload.RESTART)));
        stopButton = addRenderableWidget(new LensButton(panelX + PAD + buttonW + 4, buttonY, innerW - buttonW - 4,
                BUTTON_HEIGHT, Component.translatable("flowscope.screen.stop"), b -> onStopOrClose()));
        updateButtons();
    }

    private void updateButtons() {
        if (restartButton == null) {
            return;
        }
        boolean recording = isRecording();
        // Restart also works after the recording ended: the server starts a fresh one on the same block.
        restartButton.active = true;
        stopButton.setMessage(Component.translatable(recording ? "flowscope.screen.stop" : "flowscope.screen.close"));
    }

    private void onStopOrClose() {
        if (isRecording()) {
            send(LensActionPayload.STOP);
        } else {
            onClose();
        }
    }

    private void send(int action) {
        if (minecraft.getConnection() == null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new LensActionPayload(sessionId, action));
    }

    /** Called when this screen is replaced by a fresh screen for the same recording; the server already knows. */
    public void suppressCloseNotice() {
        closedSent = true;
    }

    @Override
    public void removed() {
        // Closing the view (Esc, click outside, another screen) only tells the server to stop sending updates;
        // the recording keeps running until it times out, is stopped, or another block is recorded.
        if (!closedSent) {
            closedSent = true;
            send(LensActionPayload.CLOSE);
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        // Sampling happens on the integrated server; pausing would freeze the recording.
        return false;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // A click outside the panel closes the window (the recording keeps running).
        if (event.x() < panelX || event.x() >= panelX + panelW || event.y() < panelY || event.y() >= panelY + panelH) {
            onClose();
            return true;
        }
        if (event.button() == 0 && breakdownAvailable()) {
            Section hit = headerAt(event.x(), event.y());
            if (hit != null) {
                hit.expanded = !hit.expanded;
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                boolean want = false;
                for (Section s : sections) {
                    want |= s.visible && s.expanded;
                }
                if (want != listsRequested) {
                    listsRequested = want;
                    send(want ? LensActionPayload.LISTS_SHOWN : LensActionPayload.LISTS_HIDDEN);
                }
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        Section s = listAt(x, y);
        if (s != null && scrollY != 0) {
            s.scroll -= (int) Math.signum(scrollY) * ROW_H;
            clampScroll(s);
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private Section headerAt(double x, double y) {
        if (x < panelX + PAD || x >= panelX + panelW - PAD) {
            return null;
        }
        int h = font.lineHeight + 3;
        for (Section s : sections) {
            if (s.visible && y >= s.headerY && y < s.headerY + h) {
                return s;
            }
        }
        return null;
    }

    private Section listAt(double x, double y) {
        if (x < panelX + PAD || x >= panelX + panelW - PAD) {
            return null;
        }
        for (Section s : sections) {
            if (s.visible && s.expanded && s.listH > 0 && y >= s.listY && y < s.listY + s.listH) {
                return s;
            }
        }
        return null;
    }

    private static void clampScroll(Section s) {
        int content = s.contentRows() * ROW_H;
        int view = Math.max(0, s.listH - 2);
        s.scroll = Math.max(0, Math.min(s.scroll, Math.max(0, content - view)));
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int lh = font.lineHeight;
        int x0 = panelX;
        int y0 = panelY;
        int x1 = panelX + panelW;
        int innerX = x0 + PAD;
        int innerW = panelW - PAD * 2;

        // Panel
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, x0, y0, panelW, panelH);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, TITLE_BAR, x0 + 2, y0 + 2, panelW - 4, titleH - 1);

        // Title bar: icon + ellipsised title
        int iconY = y0 + (titleH - 16) / 2 + 1;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, ICON_LENS, innerX - 2, iconY, 16, 16);
        int titleX = innerX + 18;
        g.text(font, ellipsize(title, x1 - PAD - titleX), titleX, y0 + (titleH - lh) / 2 + 1, COL_TEXT);

        int y = y0 + titleH + PAD;

        // Status line
        boolean recording = isRecording();
        g.blitSprite(RenderPipelines.GUI_TEXTURED, recording ? ICON_RECORDING : ICON_STOPPED, innerX, y - 1, lh, lh);
        Component status;
        if (recording) {
            status = Component.translatable("flowscope.screen.recording",
                    formatTime(displayElapsedMs()), formatTime(maxMs()));
        } else {
            status = Component.translatable(SessionState.byId(stats.state()).translationKey());
        }
        g.text(font, ellipsize(status, innerW - lh - 3), innerX + lh + 3, y, recording ? COL_TEXT : COL_WARN);
        y += lh + 2;

        Component info = Component.translatable("flowscope.screen.face_sample", faceName, formatInterval());
        g.text(font, ellipsize(info, innerW), innerX, y, COL_MUTED);
        y += lh + 2;

        boolean eventMode = isEventMode();
        Component mode = Component.translatable(eventMode
                ? "flowscope.screen.mode_event" : "flowscope.screen.mode_snapshot");
        g.text(font, ellipsize(mode, innerW), innerX, y, eventMode ? COL_NET : COL_WARN);

        // Sections
        boolean collecting = stats == null || stats.samples() == 0;
        boolean toggles = breakdownAvailable();
        Section hovered = toggles ? headerAt(mouseX, mouseY) : null;
        if (processLabelY >= 0) {
            g.text(font, ellipsize(Component.translatable("flowscope.screen.processing"), innerW),
                    innerX, processLabelY, COL_MUTED);
        }
        for (Section s : sections) {
            if (!s.visible) {
                continue;
            }
            sectionHeader(g, s, innerX, innerW, collecting, toggles, hovered == s);
            if (s.expanded && s.listH > 0) {
                drawList(g, s, innerX, innerW, mouseX, mouseY);
            }
        }

        // Graph
        if (graphH > 0) {
            drawGraph(g, innerX, graphY, innerW, graphH);
        }

        // Mode note
        int wy = warningY;
        for (FormattedCharSequence line : warningLines) {
            g.text(font, line, innerX, wy, COL_MUTED);
            wy += lh;
        }

        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private String labelKey(Section s) {
        boolean ev = isEventMode();
        return switch (s.category) {
            case ItemBreakdown.IN -> ev ? "flowscope.screen.event.increase" : "flowscope.screen.increase";
            case ItemBreakdown.OUT -> ev ? "flowscope.screen.event.decrease" : "flowscope.screen.decrease";
            case ItemBreakdown.NET -> ev ? "flowscope.screen.event.net" : "flowscope.screen.net";
            case ItemBreakdown.PRODUCED -> "flowscope.screen.produced";
            default -> "flowscope.screen.consumed";
        };
    }

    /** Signed items-per-minute value shown in a section header. */
    private double headerRate(Section s) {
        if (stats == null) {
            return 0;
        }
        return switch (s.category) {
            case ItemBreakdown.IN -> stats.increasePerMin();
            case ItemBreakdown.OUT -> -stats.decreasePerMin();
            case ItemBreakdown.PRODUCED -> stats.producedPerMin();
            case ItemBreakdown.CONSUMED -> -stats.consumedPerMin();
            default -> stats.increasePerMin() + stats.producedPerMin() - stats.decreasePerMin() - stats.consumedPerMin();
        };
    }

    /** Signed mB-per-minute value shown in a section header (fluids are never added to item counts). */
    private double headerFluidRate(Section s) {
        if (stats == null) {
            return 0;
        }
        return switch (s.category) {
            case ItemBreakdown.IN -> stats.fluidInPerMin();
            case ItemBreakdown.OUT -> -stats.fluidOutPerMin();
            case ItemBreakdown.PRODUCED -> stats.fluidMadePerMin();
            case ItemBreakdown.CONSUMED -> -stats.fluidUsedPerMin();
            default -> stats.fluidInPerMin() + stats.fluidMadePerMin() - stats.fluidOutPerMin() - stats.fluidUsedPerMin();
        };
    }

    private boolean showItems() {
        return stats == null || stats.hasItems() || !stats.hasFluids();
    }

    private boolean showFluids() {
        return stats != null && stats.hasFluids();
    }

    private static String signed(double v) {
        return (v >= 0 ? "+" : "-") + formatRate(Math.abs(v));
    }

    private void sectionHeader(GuiGraphicsExtractor g, Section s, int x, int w, boolean collecting, boolean toggles,
            boolean hovered) {
        int lh = font.lineHeight;
        int y = s.headerY;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, hovered ? HEADER_HOVER : HEADER, x, y - 1, w, lh + 2);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, headerIcon(s), x + 3, y, 8, 8);
        int labelX = x + 14;
        if (toggles) {
            String arrow = s.expanded ? "▼" : "▶";
            g.text(font, arrow, labelX, y, COL_MUTED);
            labelX += font.width(arrow) + 3;
        }
        // Items and fluids side by side, each in its own unit: "+150 items/min · +2.5k mB/min".
        double rate = headerRate(s);
        double fluidRate = headerFluidRate(s);
        Component value;
        if (collecting) {
            value = Component.translatable("flowscope.screen.collecting");
        } else {
            net.minecraft.network.chat.MutableComponent parts = Component.empty();
            if (showItems()) {
                parts.append(Component.translatable("flowscope.screen.per_min", signed(rate)));
            }
            if (showFluids()) {
                if (showItems()) {
                    parts.append(" · ");
                }
                parts.append(Component.translatable("flowscope.screen.per_min_fluid", signed(fluidRate)));
            }
            value = stats.warmingUp() ? Component.translatable("flowscope.screen.warming", parts) : parts;
        }
        double signRate = showItems() ? rate : fluidRate;
        int valueColor = collecting ? COL_MUTED
                : s.category == ItemBreakdown.NET ? (signRate >= 0 ? COL_INC : COL_DEC) : s.color;
        int vw = font.width(value);
        g.text(font, ellipsize(Component.translatable(labelKey(s)), x + w - 3 - vw - 4 - labelX), labelX, y, COL_TEXT);
        g.text(font, value, x + w - 3 - vw, y, valueColor);
    }

    private static Identifier headerIcon(Section s) {
        return switch (s.category) {
            case ItemBreakdown.IN -> HDR_IN;
            case ItemBreakdown.OUT -> HDR_OUT;
            case ItemBreakdown.PRODUCED -> HDR_MADE;
            case ItemBreakdown.CONSUMED -> HDR_USED;
            default -> HDR_NET;
        };
    }

    private void drawList(GuiGraphicsExtractor g, Section s, int x, int w, int mouseX, int mouseY) {
        int lh = font.lineHeight;
        int top = s.listY;
        int bottom = s.listY + s.listH;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, INSET, x, top, w, bottom - top);

        int contentH = s.contentRows() * ROW_H;
        int viewH = s.listH - 2;
        boolean scrollable = contentH > viewH;
        int rowW = scrollable ? w - 4 : w;
        boolean net = s.category == ItemBreakdown.NET;

        g.enableScissor(x, top + 1, x + w, bottom - 1);
        int rowY = top + 1 - s.scroll;
        if (!listsRequested || stats == null || (s.rows.isEmpty() && s.moreItems == 0)) {
            Component empty = Component.translatable(listsRequested && stats != null && stats.samples() > 0
                    ? "flowscope.screen.list_empty" : "flowscope.screen.list_loading");
            g.text(font, ellipsize(empty, rowW - 6), x + 4, rowY + (ROW_H - lh) / 2, COL_MUTED);
        } else {
            long elapsedMs = Math.max(1, stats.elapsedMs());
            boolean mouseInList = mouseX >= x && mouseX < x + rowW && mouseY >= top && mouseY < bottom;
            for (ItemRow row : s.rows) {
                if (rowY + ROW_H > top && rowY < bottom) {
                    boolean fluid = row.isFluid();
                    if (mouseInList && mouseY >= rowY && mouseY < rowY + ROW_H) {
                        g.fill(x, rowY, x + rowW, rowY + ROW_H, COL_ROW_HOVER);
                        // Tooltip plus the exact amount (the row shows it compacted, e.g. 4.5k).
                        long amt = row.amount();
                        ChatFormatting tipColor = net
                                ? (amt >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED) : ChatFormatting.GRAY;
                        Component exactLine = Component.translatable(fluid
                                        ? "flowscope.screen.exact_amount_fluid" : "flowscope.screen.exact_amount",
                                (net && amt >= 0 ? "+" : "") + exact(amt)).withStyle(tipColor);
                        if (fluid) {
                            g.setTooltipForNextFrame(font, List.of(row.name(), exactLine), java.util.Optional.empty(),
                                    mouseX, mouseY);
                        } else {
                            List<Component> tip = new ArrayList<>(Screen.getTooltipFromItem(minecraft, row.stack()));
                            tip.add(exactLine);
                            g.setTooltipForNextFrame(font, tip, row.stack().getTooltipImage(), row.stack(), mouseX, mouseY);
                        }
                    }
                    if (fluid) {
                        drawFluidIcon(g, row.fluid(), x + 2, rowY + 1);
                    } else {
                        g.item(row.stack(), x + 2, rowY + 1);
                    }
                    long amount = row.amount();
                    String sign = net ? (amount >= 0 ? "+" : "-") : "";
                    String right = fluid
                            ? sign + formatMb(Math.abs(amount))
                            : (net ? sign + formatCount(Math.abs(amount)) : formatCount(amount));
                    if (stats.elapsedMs() >= 1000) {
                        double perMin = Math.abs(amount) * 60_000.0 / elapsedMs;
                        right += "  " + sign + formatRate(perMin) + (fluid ? " mB/min" : "/min");
                    }
                    int color = net ? (amount >= 0 ? COL_INC : COL_DEC) : s.color;
                    int rw = font.width(right);
                    int textY = rowY + (ROW_H - lh) / 2 + 1;
                    g.text(font, right, x + rowW - 3 - rw, textY, color);
                    int nameX = x + 22;
                    g.text(font, ellipsize(row.name(), x + rowW - 3 - rw - 6 - nameX), nameX, textY, COL_TEXT);
                }
                rowY += ROW_H;
            }
            if (s.moreItems > 0) {
                // Only the count: the remaining entries can mix items and mB, which must not be added up.
                Component more = Component.translatable("flowscope.screen.list_more", s.moreItems);
                g.text(font, ellipsize(more, rowW - 6), x + 4, rowY + (ROW_H - lh) / 2 + 1, COL_MUTED);
            }
        }
        g.disableScissor();

        if (scrollable) {
            int trackX = x + w - 3;
            g.fill(trackX, top + 1, trackX + 2, bottom - 1, COL_SCROLL_TRACK);
            int thumbH = Math.max(8, viewH * viewH / contentH);
            int maxScroll = contentH - viewH;
            int thumbY = top + 1 + (maxScroll == 0 ? 0 : (viewH - thumbH) * s.scroll / maxScroll);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, COL_SCROLL_THUMB);
        }
    }

    private void drawGraph(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, INSET, x, y, w, h);
        for (int k = 1; k < 6; k++) {   // one gridline every 10 s
            int gx = x + 1 + k * (w - 2) / 6;
            g.fill(gx, y + 1, gx + 1, y + h - 1, COL_GRAPH_GRID);
        }
        int mid = y + h / 2;
        g.fill(x + 1, mid, x + w - 1, mid + 1, COL_GRAPH_AXIS);
        if (stats == null) {
            return;
        }
        int[] up = stats.graphIncrease();
        int[] down = stats.graphDecrease();
        int n = Math.min(RollingRateWindow.SECONDS, Math.min(up.length, down.length));
        if (n == 0) {
            return;
        }
        long max = 1;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, Math.max(up[i], down[i]));
        }
        int half = h / 2 - 2;
        int inner = w - 2;
        for (int i = 0; i < n; i++) {
            int bx0 = x + 1 + i * inner / n;
            int bx1 = x + 1 + (i + 1) * inner / n;
            if (bx1 - bx0 > 2) {
                bx1--; // small gap between bars when there is room
            }
            if (up[i] > 0) {
                int bh = Math.max(1, (int) ((long) up[i] * half / max));
                g.fill(bx0, mid - bh, bx1, mid, COL_INC);
                g.fill(bx0, mid - bh, bx1, mid - bh + 1, COL_INC_CAP);
            }
            if (down[i] > 0) {
                int bh = Math.max(1, (int) ((long) down[i] * half / max));
                g.fill(bx0, mid + 1, bx1, mid + 1 + bh, COL_DEC);
                g.fill(bx0, mid + bh, bx1, mid + 1 + bh, COL_DEC_CAP);
            }
        }
        Component label = Component.translatable(stats.hasItems() || !stats.hasFluids()
                ? "flowscope.screen.graph_label" : "flowscope.screen.graph_label_fluid");
        if (h >= font.lineHeight * 2 + 4 && font.width(label) + 4 < w) {
            g.text(font, label, x + 4, y + 3, COL_GRAPH_LABEL);
        }
    }

    // ------------------------------------------------------------------ helpers

    private long maxMs() {
        return stats != null ? stats.maxMs() : maxSeconds * 1000L;
    }

    /** Elapsed time, advanced locally between the ~2 s server updates so the clock does not jump. */
    private long displayElapsedMs() {
        if (stats == null) {
            return 0;
        }
        long elapsed = stats.elapsedMs();
        if (isRecording()) {
            elapsed += Util.getMillis() - statsReceivedMillis;
        }
        return Math.min(elapsed, maxMs());
    }

    private Component formatInterval() {
        // The measured average time between safety-net reads (shorter when small inventories are read every tick);
        // until a measurement exists, the configured interval.
        if (stats == null || stats.effectiveIntervalMs() <= 0) {
            return Component.translatable("flowscope.screen.interval_ticks", sampleIntervalTicks);
        }
        return Component.literal(String.format(Locale.ROOT, "%.2f s", stats.effectiveIntervalMs() / 1000.0));
    }

    private static String formatTime(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    private static final String[] SUFFIXES = {"k", "M", "B", "T", "Q"};

    /** Rates (items/min): below 1000 as before (one decimal under 100), larger values compact. */
    private static String formatRate(double v) {
        double a = Math.abs(v);
        if (a < 999.5) {
            return a < 100 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.0f", v);
        }
        return compact(v);
    }

    /** Counts: "×N", compact above 999. */
    private static String formatCount(long v) {
        return "×" + (Math.abs((double) v) < 1000 ? Long.toString(v) : compact(v));
    }

    /**
     * Compact form with one decimal and k/M/B/T/Q suffix (4451 -> 4.5k, 4000 -> 4k, 999,950 -> 1M).
     * Locale-independent; keeps the sign of negative values.
     */
    static String compact(double v) {
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        double unit = 1000;
        for (int i = 0; i < SUFFIXES.length; i++) {
            double scaled = Math.round(a / unit * 10) / 10.0;   // rounded to one decimal
            if (scaled < 1000 || i == SUFFIXES.length - 1) {
                String num = scaled == Math.rint(scaled)
                        ? Long.toString((long) scaled)
                        : String.format(Locale.ROOT, "%.1f", scaled);
                return sign + num + SUFFIXES[i];
            }
            unit *= 1000;
        }
        return sign + a;
    }

    /** Fluid amounts: "250 mB", "12.5k mB". */
    private static String formatMb(long v) {
        return (Math.abs((double) v) < 1000 ? Long.toString(v) : compact(v)) + " mB";
    }

    /**
     * Draws a fluid as a 16x16 icon: its still texture, tinted like in the world. Falls back to the fluid's bucket
     * if the fluid has no usable model.
     */
    private void drawFluidIcon(GuiGraphicsExtractor g, FluidResource fluid, int x, int y) {
        try {
            net.minecraft.client.renderer.block.FluidModel model = minecraft.getModelManager().getFluidStateModelSet()
                    .get(fluid.getFluid().defaultFluidState());
            if (model != null && model.stillMaterial() != null) {
                int color = 0xFFFFFFFF;
                if (model.fluidTintSource() != null) {
                    color = 0xFF000000 | model.fluidTintSource().colorAsStack(fluid.toStack(1000));
                }
                g.blitSprite(RenderPipelines.GUI_TEXTURED, model.stillMaterial().sprite(), x, y, 16, 16, color);
                return;
            }
        } catch (RuntimeException ignored) {
            // fall through to the bucket icon
        }
        ItemStack bucket = new ItemStack(fluid.getFluid().getBucket());
        if (!bucket.isEmpty()) {
            g.item(bucket, x, y);
        }
    }

    /** Exact number with thousands separators, for tooltips. */
    private static String exact(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private FormattedCharSequence ellipsize(Component text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        String ellipsis = "...";
        int room = Math.max(0, maxWidth - font.width(ellipsis));
        String cut = font.plainSubstrByWidth(text.getString(), room);
        return Component.literal(cut + ellipsis).withStyle(text.getStyle()).getVisualOrderText();
    }

    private static Identifier sprite(String path) {
        return Identifier.fromNamespaceAndPath(Flowscope.MODID, path);
    }
}
