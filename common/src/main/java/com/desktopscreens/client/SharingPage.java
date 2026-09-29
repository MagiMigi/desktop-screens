package com.desktopscreens.client;

import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ShareScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The page in the settings (opened from a screen in the world) where its owner shares it with other players, by
 * name: a switch between this screen's list and the list for all their screens, the Sound switch (for all their
 * sharing), a name box with suggestions from the players online, and the list with a remove button per name. Changes go to the server ({@link ScreenSharing}), and
 * the list shows them once the server has made them. The HUD is hidden while a screen is used, so the server's
 * messages can't be seen here; the page checks names itself and says what's wrong right under the box.
 */
final class SharingPage {
    private static final int SUGGESTIONS = 3, ROW = 18;

    private final Minecraft mc;
    private final ScreenGroups.Group screen;
    private final Runnable back;
    /** Which list the page edits: all the owner's screens, or just this one. */
    private boolean allScreens;
    /** The switch was flipped: the page must be built again for the other list. */
    private boolean switched;
    private String typed = "";
    private int introY;
    private EditBox nameBox;
    private final List<Button> suggestionButtons = new ArrayList<>();
    private final List<String> suggestionNames = new ArrayList<>();
    private List<FormattedCharSequence> intro = List.of();
    /** The names the list shows, to notice when the server changed it. */
    private List<String> shown = List.of();
    private final List<String> rowNames = new ArrayList<>();
    private Component status;
    private int statusY, listLabelY, rowsY, moreY, more;

    SharingPage(Minecraft mc, ScreenGroups.Group screen, Runnable back) {
        this.mc = mc;
        this.screen = screen;
        this.back = back;
    }

    /** Whom this screen is shared with, one way or the other: for the "Sharing (n)..." button. */
    static int count(Minecraft mc, ScreenGroups.Group screen) {
        List<String> names = new ArrayList<>(SharingInfo.myAllScreens());
        for (ScreenBlockEntity.Watcher w : screen.shared(mc.level)) {
            if (!contains(names, w.name())) names.add(w.name());
        }
        return names.size();
    }

    void addWidgets(Consumer<AbstractWidget> add, Font font, int x, int w, int height) {
        int y = 20;
        switched = false;
        int soundWidth = 60;
        add.accept(CycleButton.booleanBuilder(Component.translatable("desktopscreens.share.scope_all"),
                        Component.translatable("desktopscreens.share.scope_screen"))
                .withInitialValue(allScreens)
                .displayOnlyValue()
                .withTooltip(value -> Tooltip.create(Component.translatable("desktopscreens.share.scope.tooltip")))
                .create(x, y, w - soundWidth - 4, 20, Component.translatable("desktopscreens.share.scope"), (button, value) -> {
                    allScreens = value;
                    status = null;
                    switched = true;
                }));
        DesktopConfig.load();
        add.accept(CycleButton.onOffBuilder(DesktopConfig.shareSound)
                .withTooltip(value -> Tooltip.create(Component.translatable("desktopscreens.share.sound.tooltip")))
                .create(x + w - soundWidth, y, soundWidth, 20, Component.translatable("desktopscreens.share.sound"), (button, value) -> {
                    DesktopConfig.shareSound = value;
                    DesktopConfig.save();
                }));
        y += 24;
        introY = y;
        intro = font.split(Component.translatable(allScreens ? "desktopscreens.share.intro_all" : "desktopscreens.share.intro_screen"), w);
        y += intro.size() * 10 + 4;
        nameBox = new EditBox(font, x + 1, y + 1, w - 48, 18, Component.translatable("desktopscreens.share.name"));
        nameBox.setMaxLength(ShareScreen.MAX_NAME);
        nameBox.setFilter(text -> text.matches("[A-Za-z0-9_]*"));
        nameBox.setValue(typed);
        nameBox.setHint(Component.translatable("desktopscreens.share.name").withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setResponder(text -> {
            typed = text;
            status = null;
            updateSuggestions();
        });
        add.accept(nameBox);
        add.accept(Button.builder(Component.translatable("desktopscreens.share.add"), button -> addTyped())
                .bounds(x + w - 44, y, 44, 20).build());
        y += 22;
        statusY = y + 2;
        y += 12;
        suggestionButtons.clear();
        for (int i = 0; i < SUGGESTIONS; i++) {
            int index = i;
            Button b = Button.builder(Component.empty(), button -> share(suggestionNames.get(index))).bounds(x, y, w, 16).build();
            add.accept(b);
            suggestionButtons.add(b);
            y += ROW;
        }
        updateSuggestions();
        y += 4;

        listLabelY = y;
        y += 12;
        List<String> names = sharedNames();
        shown = names;
        rowNames.clear();
        int fit = Math.max(1, (height - 26 - y) / ROW); // above the Back button
        int rows = names.size() <= fit ? names.size() : fit - 1;
        rowsY = y;
        for (int i = 0; i < rows; i++) {
            String name = names.get(i);
            rowNames.add(name);
            add.accept(Button.builder(Component.translatable("desktopscreens.share.remove"), button -> remove(name))
                    .bounds(x + w - 56, y, 56, 16).build());
            y += ROW;
        }
        more = names.size() - rows;
        moreY = y + 4;
        add.accept(Button.builder(Component.translatable("gui.back"), button -> back.run()).bounds(x, height - 24, w, 20).build());
    }

    /** The text around the widgets. Drawn before them. */
    void render(GuiGraphics g, Font font, int x) {
        for (int i = 0; i < intro.size(); i++) g.drawString(font, intro.get(i), x, introY + i * 10, 0xB0B0B0);
        if (status != null) g.drawString(font, status, x, statusY, 0xFF7070);
        g.drawString(font, Component.translatable(shown.isEmpty() ? "desktopscreens.share.nobody" : "desktopscreens.share.list"),
                x, listLabelY, 0xE0E0E0);
        for (int i = 0; i < rowNames.size(); i++) g.drawString(font, rowNames.get(i), x, rowsY + i * ROW + 4, 0xFFFFFF);
        if (more > 0) g.drawString(font, Component.translatable("desktopscreens.share.more", more), x, moreY, 0xB0B0B0);
    }

    /**
     * True once the page must be built again: the switch was flipped, or the list differs from what the page shows
     * (the server made a change).
     */
    boolean changed() {
        return switched || !sharedNames().equals(shown);
    }

    /** Enter in the name box adds the name. True if it was for this page. */
    boolean enter() {
        if (nameBox == null || !nameBox.isFocused()) return false;
        addTyped();
        return true;
    }

    /** The list the page edits now. */
    private List<String> sharedNames() {
        if (allScreens) return SharingInfo.myAllScreens();
        List<String> names = new ArrayList<>();
        for (ScreenBlockEntity.Watcher w : screen.shared(mc.level)) names.add(w.name());
        return names;
    }

    /** Online players (not you, not on the list yet) whose names start with what's typed. */
    private void updateSuggestions() {
        suggestionNames.clear();
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> shared = sharedNames();
        if (mc.getConnection() != null && mc.player != null) {
            List<String> online = new ArrayList<>();
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().getName();
                if (info.getProfile().getId().equals(mc.player.getUUID()) || contains(shared, name)) continue;
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) online.add(name);
            }
            online.sort(String.CASE_INSENSITIVE_ORDER);
            suggestionNames.addAll(online.subList(0, Math.min(SUGGESTIONS, online.size())));
        }
        for (int i = 0; i < suggestionButtons.size(); i++) {
            Button b = suggestionButtons.get(i);
            b.visible = i < suggestionNames.size();
            if (b.visible) {
                b.setMessage(Component.translatable("desktopscreens.share.suggestion", suggestionNames.get(i)));
                b.setTooltip(Tooltip.create(Component.translatable("desktopscreens.share.suggestion.tooltip", suggestionNames.get(i))));
            }
        }
    }

    private void addTyped() {
        if (!typed.isBlank()) share(typed.trim());
    }

    /** Checks what the server would check, so the answer shows here; then asks the server. */
    private void share(String name) {
        if (mc.getConnection() == null || mc.player == null) return;
        PlayerInfo info = null;
        for (PlayerInfo p : mc.getConnection().getOnlinePlayers()) {
            if (p.getProfile().getName().equalsIgnoreCase(name)) info = p;
        }
        List<String> shared = sharedNames();
        if (info == null) {
            status = Component.translatable("desktopscreens.share.not_online", name);
        } else if (info.getProfile().getId().equals(mc.player.getUUID())) {
            status = Component.translatable("desktopscreens.share.yourself");
        } else if (contains(shared, name)) {
            status = Component.translatable("desktopscreens.share.already", info.getProfile().getName());
        } else if (shared.size() >= ScreenSharing.MAX_WATCHERS) {
            status = Component.translatable("desktopscreens.share.full", ScreenSharing.MAX_WATCHERS);
        } else {
            send(info.getProfile().getName(), true);
            typed = "";
            nameBox.setValue(""); // also clears the status, through the responder
        }
    }

    private void remove(String name) {
        send(name, false);
    }

    private void send(String name, boolean add) {
        mc.getConnection().send(new ServerboundCustomPayloadPacket(new ShareScreen(screen.anchor(), name, add, allScreens)));
    }

    private static boolean contains(List<String> names, String name) {
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
