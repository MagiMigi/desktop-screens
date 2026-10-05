package com.desktopscreens.client;

import com.desktopscreens.Net;
import com.desktopscreens.ScreenBlockEntity;
import com.desktopscreens.ScreenSharing;
import com.desktopscreens.ShareScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiPageButtonList;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The page in the settings (opened from a screen in the world) where its owner shares it with other players, by
 * name: a switch between this screen's list and the list for all their screens, the Sound switch (for all their
 * sharing), a name box with suggestions from the players online, and the list with a remove button per name. Changes
 * go to the server ({@link ScreenSharing}), and the list shows them once the server has made them. The HUD is hidden
 * while a screen is used, so the server's messages can't be seen here; the page checks names itself and says what's
 * wrong right under the box.
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
    private GuiTextField nameBox;
    private final List<Widget.Button> suggestionButtons = new ArrayList<>();
    private final List<String> suggestionNames = new ArrayList<>();
    private List<String> intro = Collections.emptyList();
    /** The names the list shows, to notice when the server changed it. */
    private List<String> shown = Collections.emptyList();
    private final List<String> rowNames = new ArrayList<>();
    private ITextComponent status;
    private int statusY, listLabelY, rowsY, moreY, more;

    SharingPage(Minecraft mc, ScreenGroups.Group screen, Runnable back) {
        this.mc = mc;
        this.screen = screen;
        this.back = back;
    }

    /** Whom this screen is shared with, one way or the other: for the "Sharing (n)..." button. */
    static int count(Minecraft mc, ScreenGroups.Group screen) {
        List<String> names = new ArrayList<>(SharingInfo.myAllScreens());
        for (ScreenBlockEntity.Watcher w : shared(mc, screen)) {
            if (!contains(names, w.name)) names.add(w.name);
        }
        return names.size();
    }

    /** The players the screen's owner shares it with (its own list). */
    private static List<ScreenBlockEntity.Watcher> shared(Minecraft mc, ScreenGroups.Group screen) {
        TileEntity te = mc.world.getTileEntity(screen.anchor());
        return te instanceof ScreenBlockEntity ? ((ScreenBlockEntity) te).shared() : Collections.emptyList();
    }

    /** The name box, for the screen to draw and give keys and clicks to. */
    GuiTextField nameBox() {
        return nameBox;
    }

    void addWidgets(Consumer<GuiButton> add, FontRenderer font, int x, int w, int height) {
        int y = 20;
        switched = false;
        int soundWidth = 60;
        add.accept(new Widget.Cycle<>(x, y, w - soundWidth - 4, null, () -> Arrays.asList(true, false),
                value -> new TextComponentTranslation(value ? "desktopscreens.share.scope_all" : "desktopscreens.share.scope_screen"),
                allScreens, value -> {
                    allScreens = value;
                    status = null;
                    switched = true;
                }).tooltip(new TextComponentTranslation("desktopscreens.share.scope.tooltip")));
        DesktopConfig.load();
        add.accept(new Widget.Cycle<>(x + w - soundWidth, y, soundWidth, new TextComponentTranslation("desktopscreens.share.sound"),
                () -> Arrays.asList(true, false), on -> new TextComponentTranslation(on ? "options.on" : "options.off"),
                DesktopConfig.shareSound, value -> {
                    DesktopConfig.shareSound = value;
                    DesktopConfig.save();
                }).tooltip(new TextComponentTranslation("desktopscreens.share.sound.tooltip")));
        y += 24;
        introY = y;
        intro = font.listFormattedStringToWidth(new TextComponentTranslation(allScreens ? "desktopscreens.share.intro_all"
                : "desktopscreens.share.intro_screen").getFormattedText(), w);
        y += intro.size() * 10 + 4;
        // Built again when the server changes the list: the box stays where the typing was.
        boolean focused = nameBox != null && nameBox.isFocused();
        nameBox = new GuiTextField(0, font, x + 1, y + 1, w - 48, 18);
        nameBox.setFocused(focused);
        nameBox.setValidator(text -> text != null && text.matches("[A-Za-z0-9_]*"));
        nameBox.setMaxStringLength(ShareScreen.MAX_NAME);
        nameBox.setText(typed);
        nameBox.setGuiResponder(new GuiPageButtonList.GuiResponder() {
            @Override
            public void setEntryValue(int id, boolean value) {}

            @Override
            public void setEntryValue(int id, float value) {}

            @Override
            public void setEntryValue(int id, String value) {
                typed = value;
                status = null;
                updateSuggestions();
            }
        });
        add.accept(new Widget.Button(x + w - 44, y, 44, new TextComponentTranslation("desktopscreens.share.add"), this::addTyped));
        y += 22;
        statusY = y + 2;
        y += 12;
        suggestionButtons.clear();
        for (int i = 0; i < SUGGESTIONS; i++) {
            int index = i;
            Widget.Button b = new Widget.Button(x, y, w, new TextComponentTranslation(""), () -> share(suggestionNames.get(index)));
            b.height = 16;
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
            Widget.Button remove = new Widget.Button(x + w - 56, y, 56, new TextComponentTranslation("desktopscreens.share.remove"), () -> remove(name));
            remove.height = 16;
            add.accept(remove);
            y += ROW;
        }
        more = names.size() - rows;
        moreY = y + 4;
        add.accept(new Widget.Button(x, height - 24, w, new TextComponentTranslation("gui.back"), back));
    }

    /** The text around the widgets. Drawn before them. */
    void render(FontRenderer font, int x) {
        for (int i = 0; i < intro.size(); i++) font.drawStringWithShadow(intro.get(i), x, introY + i * 10, 0xB0B0B0);
        if (status != null) font.drawStringWithShadow(status.getFormattedText(), x, statusY, 0xFF7070);
        font.drawStringWithShadow(new TextComponentTranslation(shown.isEmpty() ? "desktopscreens.share.nobody" : "desktopscreens.share.list")
                .getFormattedText(), x, listLabelY, 0xE0E0E0);
        for (int i = 0; i < rowNames.size(); i++) font.drawStringWithShadow(rowNames.get(i), x, rowsY + i * ROW + 4, 0xFFFFFF);
        if (more > 0) font.drawStringWithShadow(new TextComponentTranslation("desktopscreens.share.more", more).getFormattedText(), x, moreY, 0xB0B0B0);
        if (nameBox.getText().isEmpty() && !nameBox.isFocused()) {
            // The hint the newer versions' box shows while it's empty.
            font.drawStringWithShadow(TextFormatting.DARK_GRAY + new TextComponentTranslation("desktopscreens.share.name").getFormattedText(),
                    nameBox.x + 4, nameBox.y + 5, 0xFFFFFF);
        }
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
        for (ScreenBlockEntity.Watcher w : shared(mc, screen)) names.add(w.name);
        return names;
    }

    /** Online players (not you, not on the list yet) whose names start with what's typed. */
    private void updateSuggestions() {
        suggestionNames.clear();
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> shared = sharedNames();
        if (mc.getConnection() != null && mc.player != null) {
            List<String> online = new ArrayList<>();
            for (NetworkPlayerInfo info : mc.getConnection().getPlayerInfoMap()) {
                String name = info.getGameProfile().getName();
                if (info.getGameProfile().getId().equals(mc.player.getUniqueID()) || contains(shared, name)) continue;
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) online.add(name);
            }
            online.sort(String.CASE_INSENSITIVE_ORDER);
            suggestionNames.addAll(online.subList(0, Math.min(SUGGESTIONS, online.size())));
        }
        for (int i = 0; i < suggestionButtons.size(); i++) {
            Widget.Button b = suggestionButtons.get(i);
            b.visible = i < suggestionNames.size();
            if (b.visible) {
                b.displayString = new TextComponentTranslation("desktopscreens.share.suggestion", suggestionNames.get(i)).getFormattedText();
                b.tooltip(new TextComponentTranslation("desktopscreens.share.suggestion.tooltip", suggestionNames.get(i)));
            }
        }
    }

    private void addTyped() {
        if (!typed.trim().isEmpty()) share(typed.trim());
    }

    /** Checks what the server would check, so the answer shows here; then asks the server. */
    private void share(String name) {
        if (mc.getConnection() == null || mc.player == null) return;
        NetworkPlayerInfo info = null;
        for (NetworkPlayerInfo p : mc.getConnection().getPlayerInfoMap()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) info = p;
        }
        List<String> shared = sharedNames();
        if (info == null) {
            status = new TextComponentTranslation("desktopscreens.share.not_online", name);
        } else if (info.getGameProfile().getId().equals(mc.player.getUniqueID())) {
            status = new TextComponentTranslation("desktopscreens.share.yourself");
        } else if (contains(shared, name)) {
            status = new TextComponentTranslation("desktopscreens.share.already", info.getGameProfile().getName());
        } else if (shared.size() >= ScreenSharing.MAX_WATCHERS) {
            status = new TextComponentTranslation("desktopscreens.share.full", ScreenSharing.MAX_WATCHERS);
        } else {
            send(info.getGameProfile().getName(), true);
            typed = "";
            nameBox.setText("");
            status = null;
            updateSuggestions();
        }
    }

    private void remove(String name) {
        send(name, false);
    }

    private void send(String name, boolean add) {
        Net.CHANNEL.sendToServer(new ShareScreen(screen.anchor(), name, add, allScreens));
    }

    private static boolean contains(List<String> names, String name) {
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
