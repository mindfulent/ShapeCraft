package com.shapecraft.client.screen;

import com.shapecraft.ShapeCraftConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;

/**
 * Shown when trial generations are exhausted or license has expired.
 * Explains AI generation costs and directs users to upgrade + Discord support.
 */
public class TrialExpiredScreen extends Screen {

    // Layout
    private static final int PANEL_PADDING = 20;
    private static final int LINE_HEIGHT = 12;
    private static final int SECTION_SPACING = 16;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;

    // Colors
    private static final int TITLE_COLOR = 0xFFD700;    // Gold
    private static final int TEXT_COLOR = 0xBBBBBB;     // Light gray
    private static final int HIGHLIGHT_COLOR = 0xFFFF55; // Yellow
    private static final int CTA_COLOR = 0x55FF55;      // Green

    // Gradient background
    private static final int GRADIENT_TOP = 0xD0080808;
    private static final int GRADIENT_BOTTOM = 0xC0181818;

    // Beveled border
    private static final int BORDER_LIGHT = 0xFF606060;
    private static final int BORDER_DARK = 0xFF202020;
    private static final int BORDER_INNER = 0xFF505050;

    // Panel bounds
    private int panelLeft, panelTop, panelWidth, panelHeight;

    public TrialExpiredScreen() {
        super(Component.translatable("shapecraft.trial_expired.title"));
    }

    @Override
    protected void init() {
        panelWidth = Math.min(320, this.width - 40);
        panelHeight = 250;
        panelLeft = (this.width - panelWidth) / 2;
        panelTop = (this.height - panelHeight) / 2;

        int centerX = this.width / 2;
        int buttonX = centerX - BUTTON_WIDTH / 2;
        int buttonY = panelTop + panelHeight - PANEL_PADDING - BUTTON_HEIGHT * 3 - 8;

        // "Upgrade" button
        addRenderableWidget(Button.builder(
                Component.translatable("shapecraft.trial_expired.upgrade"),
                button -> {
                    try {
                        Util.getPlatform().openUri(new URI(ShapeCraftConstants.UPGRADE_URL));
                    } catch (Exception ignored) {}
                }
        ).bounds(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // "Need Help? Join Discord" button
        addRenderableWidget(Button.builder(
                Component.translatable("shapecraft.trial_expired.discord"),
                button -> {
                    try {
                        Util.getPlatform().openUri(new URI(ShapeCraftConstants.DISCORD_URL));
                    } catch (Exception ignored) {}
                }
        ).bounds(buttonX, buttonY + BUTTON_HEIGHT + 4, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // "Close" button
        addRenderableWidget(Button.builder(
                Component.translatable("shapecraft.trial_expired.close"),
                button -> Minecraft.getInstance().setScreen(null)
        ).bounds(buttonX, buttonY + (BUTTON_HEIGHT + 4) * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Gradient panel background
        graphics.fillGradient(
                panelLeft, panelTop,
                panelLeft + panelWidth, panelTop + panelHeight,
                GRADIENT_TOP, GRADIENT_BOTTOM
        );

        // Beveled border
        graphics.hLine(panelLeft, panelLeft + panelWidth - 1, panelTop, BORDER_LIGHT);
        graphics.vLine(panelLeft, panelTop, panelTop + panelHeight - 1, BORDER_LIGHT);
        graphics.hLine(panelLeft, panelLeft + panelWidth - 1, panelTop + panelHeight - 1, BORDER_DARK);
        graphics.vLine(panelLeft + panelWidth - 1, panelTop, panelTop + panelHeight - 1, BORDER_DARK);
        graphics.hLine(panelLeft + 1, panelLeft + panelWidth - 2, panelTop + 1, BORDER_INNER);
        graphics.vLine(panelLeft + 1, panelTop + 1, panelTop + panelHeight - 2, BORDER_INNER);

        Minecraft mc = Minecraft.getInstance();
        int centerX = this.width / 2;
        int y = panelTop + PANEL_PADDING;

        // Title (gold, bold)
        graphics.drawCenteredString(mc.font,
                Component.translatable("shapecraft.trial_expired.title").withStyle(s -> s.withBold(true)),
                centerX, y, TITLE_COLOR);
        y += LINE_HEIGHT + SECTION_SPACING;

        // Explanation
        graphics.drawCenteredString(mc.font,
                Component.translatable("shapecraft.trial_expired.body1"),
                centerX, y, TEXT_COLOR);
        y += LINE_HEIGHT;
        graphics.drawCenteredString(mc.font,
                Component.translatable("shapecraft.trial_expired.body2"),
                centerX, y, TEXT_COLOR);
        y += LINE_HEIGHT;
        graphics.drawCenteredString(mc.font,
                Component.translatable("shapecraft.trial_expired.body3").withStyle(s -> s.withColor(HIGHLIGHT_COLOR)),
                centerX, y, HIGHLIGHT_COLOR);
        y += LINE_HEIGHT + SECTION_SPACING;

        // Call to action
        graphics.drawCenteredString(mc.font,
                Component.translatable("shapecraft.trial_expired.cta1"),
                centerX, y, CTA_COLOR);
        y += LINE_HEIGHT;
        graphics.drawCenteredString(mc.font,
                Component.translatable("shapecraft.trial_expired.cta2"),
                centerX, y, CTA_COLOR);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
