package com.shapecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class ShapeCraftCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("shapecraft")
                        // /shapecraft <description> — generate a block
                        .then(Commands.argument("description", StringArgumentType.greedyString())
                                .executes(ShapeCraftCommand::executeGenerate))
                        // /shapecraft info — show remaining generations
                        .then(Commands.literal("info")
                                .executes(ShapeCraftCommand::executeInfo))
                        // /shapecraft status — op only, license/pool status
                        .then(Commands.literal("status")
                                .requires(source -> source.hasPermission(4))
                                .executes(ShapeCraftCommand::executeStatus))
                        // /shapecraft activate <code> — op only, activate license
                        .then(Commands.literal("activate")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("code", StringArgumentType.string())
                                        .executes(ShapeCraftCommand::executeActivate)))
                        // /shapecraft reload — op only, reload config
                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(4))
                                .executes(ShapeCraftCommand::executeReload))
        );
    }

    private static int executeGenerate(CommandContext<CommandSourceStack> context) {
        String description = StringArgumentType.getString(context, "description");
        var source = context.getSource();

        if (description.length() > ShapeCraftConstants.MAX_PROMPT_LENGTH) {
            source.sendFailure(Component.literal(
                    "Description too long (max " + ShapeCraftConstants.MAX_PROMPT_LENGTH + " characters)."));
            return 0;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("This command can only be used by players."));
            return 0;
        }

        // Check pool capacity
        var pool = ShapeCraft.getInstance().getBlockPoolManager();
        if (pool.getNextAvailable() < 0) {
            source.sendFailure(Component.literal(
                    "Block pool is full (" + ShapeCraftConstants.DEFAULT_POOL_SIZE + "/" + ShapeCraftConstants.DEFAULT_POOL_SIZE + "). No slots available."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Generating block: " + description + "..."), false);

        // Submit to generation manager
        ShapeCraft.getInstance().getGenerationManager()
                .submit(source.getServer(), player, description);
        return 1;
    }

    private static int executeInfo(CommandContext<CommandSourceStack> context) {
        var pool = ShapeCraft.getInstance().getBlockPoolManager();
        var lm = ShapeCraft.getInstance().getLicenseManager();
        var player = context.getSource().getPlayer();

        String dailyRemaining = player != null
                ? String.valueOf(lm.getDailyCapTracker().getRemainingToday(player.getUUID()))
                : "N/A";

        String info = String.format("ShapeCraft v%s\n  Pool: %d/%d blocks\n  License: %s\n  Daily remaining: %s",
                ShapeCraftConstants.MOD_VERSION,
                pool.getAssignedCount(),
                ShapeCraftConstants.DEFAULT_POOL_SIZE,
                lm.getState(),
                dailyRemaining);
        context.getSource().sendSuccess(() -> Component.literal(info), false);
        return 1;
    }

    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        var pool = ShapeCraft.getInstance().getBlockPoolManager();
        var lm = ShapeCraft.getInstance().getLicenseManager();

        String generationsStr = switch (lm.getState()) {
            case TRIAL -> lm.getTrialGenerationsRemaining() + " trial remaining";
            case ACTIVE -> lm.getMonthlyUsed() + "/" + ShapeCraftConstants.MONTHLY_GENERATIONS + " monthly";
            default -> lm.getState().toString();
        };

        String status = String.format(
                "ShapeCraft Status\n  Version: %s\n  Protocol: %d\n  Pool: %d/%d slots\n  License: %s\n  Generations: %s\n  Server ID: %s\n  Last validated: %s",
                ShapeCraftConstants.MOD_VERSION,
                ShapeCraftConstants.PROTOCOL_VERSION,
                pool.getAssignedCount(),
                ShapeCraftConstants.DEFAULT_POOL_SIZE,
                lm.getState(),
                generationsStr,
                lm.getServerId() != null ? lm.getServerId().substring(0, Math.min(16, lm.getServerId().length())) + "..." : "N/A",
                lm.getLastValidated() != null ? lm.getLastValidated().toString() : "never");
        context.getSource().sendSuccess(() -> Component.literal(status), false);
        return 1;
    }

    private static int executeActivate(CommandContext<CommandSourceStack> context) {
        String code = StringArgumentType.getString(context, "code");
        ShapeCraft.getInstance().getLicenseManager().activate(code);
        context.getSource().sendSuccess(
                () -> Component.literal("Activating ShapeCraft license..."), true);
        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(
                () -> Component.literal("Config reload not yet implemented."), true);
        return 1;
    }
}
