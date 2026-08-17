package com.hhy.dreamingrecall.client.screen;

import com.hhy.dreamingrecall.client.library.ClientArchiveEntry;
import com.hhy.dreamingrecall.client.library.ClientArchiveLibrary;
import com.hhy.dreamingrecall.client.library.ClientArchiveScan;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ReplayLibraryScreen extends Screen {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Screen parent;
    private ArchiveList archiveList;
    private Button openButton;
    private Button refreshButton;
    private Component status = Component.translatable("screen.dreamingrecall.archives.loading");
    private int scanGeneration;

    public ReplayLibraryScreen(Screen parent) {
        super(Component.translatable("screen.dreamingrecall.archives.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int listBottom = Math.max(80, height - 64);
        archiveList = addRenderableWidget(new ArchiveList(minecraft, width, listBottom - 32, 32, 38));
        int buttonY = height - 28;
        int center = width / 2;
        openButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.dreamingrecall.archives.open"),
                        button -> openSelected()
                )
                .bounds(center - 206, buttonY, 100, 20)
                .build());
        refreshButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.dreamingrecall.archives.refresh"),
                        button -> refresh()
                )
                .bounds(center - 102, buttonY, 100, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.dreamingrecall.archives.folder"),
                        button -> Util.getPlatform().openPath(ClientArchiveLibrary.importedArchiveRoot(gameDirectory()))
                )
                .bounds(center + 2, buttonY, 100, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.archives.folder")))
                .build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.done"),
                        button -> onClose()
                )
                .bounds(center + 106, buttonY, 100, 20)
                .build());
        openButton.active = false;
        refresh();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.drawCenteredString(font, status, width / 2, height - 46, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private void refresh() {
        int generation = ++scanGeneration;
        refreshButton.active = false;
        openButton.active = false;
        status = Component.translatable("screen.dreamingrecall.archives.loading");
        UUID selectedId = archiveList.selectedArchiveId();
        CompletableFuture
                .supplyAsync(() -> ClientArchiveLibrary.scan(gameDirectory()), Util.ioPool())
                .whenComplete((scan, failure) -> minecraft.execute(() -> {
                    if (generation != scanGeneration || minecraft.screen != this) {
                        return;
                    }
                    refreshButton.active = true;
                    if (failure != null) {
                        archiveList.replaceArchives(List.of(), null);
                        status = Component.translatable(
                                "screen.dreamingrecall.archives.scan_failed",
                                rootMessage(failure)
                        );
                        return;
                    }
                    archiveList.replaceArchives(scan.archives(), selectedId);
                    updateStatus(scan);
                    selectionChanged();
                }));
    }

    private void updateStatus(ClientArchiveScan scan) {
        if (scan.archives().isEmpty() && scan.errors().isEmpty()) {
            status = Component.translatable("screen.dreamingrecall.archives.empty");
        } else if (!scan.errors().isEmpty()) {
            status = Component.translatable(
                    "screen.dreamingrecall.archives.summary_errors",
                    scan.archives().size(),
                    scan.errors().size()
            );
        } else {
            status = Component.translatable("screen.dreamingrecall.archives.summary", scan.archives().size());
        }
    }

    private void selectionChanged() {
        ArchiveEntry selected = archiveList.getSelected();
        openButton.active = selected != null && selected.compatible();
        if (selected != null && !selected.compatible()) {
            status = Component.translatable(
                    "screen.dreamingrecall.archives.incompatible",
                    selected.entry.manifest().minecraftVersion(),
                    SharedConstants.getCurrentVersion().getName()
            );
        }
    }

    private void openSelected() {
        ArchiveEntry selected = archiveList.getSelected();
        if (selected != null && selected.compatible()) {
            minecraft.setScreen(new ReplayTimelineScreen(this, selected.entry));
        }
    }

    private Path gameDirectory() {
        return minecraft.gameDirectory.toPath();
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private final class ArchiveList extends ObjectSelectionList<ArchiveEntry> {
        private ArchiveList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
        }

        private void replaceArchives(List<ClientArchiveEntry> archives, UUID selectedId) {
            clearEntries();
            ArchiveEntry selected = null;
            for (ClientArchiveEntry archive : archives) {
                ArchiveEntry entry = new ArchiveEntry(archive);
                addEntry(entry);
                if (selectedId != null && selectedId.equals(archive.manifest().archiveId())) {
                    selected = entry;
                }
            }
            setSelected(selected);
        }

        private UUID selectedArchiveId() {
            ArchiveEntry selected = getSelected();
            return selected == null ? null : selected.entry.manifest().archiveId();
        }

        @Override
        public int getRowWidth() {
            return Math.min(680, width - 32);
        }

        @Override
        protected int getScrollbarPosition() {
            return getRowRight() + 6;
        }
    }

    private final class ArchiveEntry extends ObjectSelectionList.Entry<ArchiveEntry> {
        private final ClientArchiveEntry entry;

        private ArchiveEntry(ClientArchiveEntry entry) {
            this.entry = entry;
        }

        @Override
        public void render(
                GuiGraphics graphics,
                int index,
                int top,
                int left,
                int rowWidth,
                int rowHeight,
                int mouseX,
                int mouseY,
                boolean hovered,
                float partialTick
        ) {
            String name = font.plainSubstrByWidth(entry.displayName(), rowWidth - 12);
            graphics.drawString(font, name, left + 4, top + 4, compatible() ? 0xFFFFFFFF : 0xFFFF7777, false);
            String created = DATE_FORMAT.format(Instant.ofEpochMilli(entry.manifest().createdEpochMillis()));
            String detail = Component.translatable(
                    "screen.dreamingrecall.archives.row",
                    entry.sourceLabel(),
                    created,
                    entry.manifest().minecraftVersion(),
                    formatDuration(entry.durationNanos()),
                    entry.segmentCount()
            ).getString();
            graphics.drawString(
                    font,
                    font.plainSubstrByWidth(detail, rowWidth - 12),
                    left + 4,
                    top + 17,
                    0xFFAAAAAA,
                    false
            );
            Component state = entry.complete()
                    ? Component.translatable("screen.dreamingrecall.archives.complete")
                    : Component.translatable("screen.dreamingrecall.archives.recoverable");
            int stateColor = entry.errorCount() > 0 ? 0xFFFF7777 : entry.complete() ? 0xFF77DD88 : 0xFFFFCC66;
            graphics.drawString(font, state, left + rowWidth - font.width(state) - 6, top + 4, stateColor, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                archiveList.setSelected(this);
                selectionChanged();
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(entry.displayName());
        }

        private boolean compatible() {
            return SharedConstants.getCurrentVersion().getName().equals(entry.manifest().minecraftVersion());
        }
    }

    static String formatDuration(long nanos) {
        long totalSeconds = Math.max(0, nanos / 1_000_000_000L);
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }
}
