package com.shapecraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shapecraft.ShapeCraft;
import com.shapecraft.ShapeCraftConstants;
import com.shapecraft.block.DoorDebugState;
import com.shapecraft.block.PoolBlock;
import com.shapecraft.block.PoolBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

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
                        // /shapecraft debug — door debug tool
                        .then(Commands.literal("debug")
                                .requires(source -> source.hasPermission(4))
                                .executes(ShapeCraftCommand::executeDebugShow)
                                .then(Commands.literal("hclosed")
                                        .then(Commands.argument("offset", IntegerArgumentType.integer(0, 270))
                                                .executes(ShapeCraftCommand::executeDebugHClosed)))
                                .then(Commands.literal("hopen")
                                        .then(Commands.argument("offset", IntegerArgumentType.integer(0, 270))
                                                .executes(ShapeCraftCommand::executeDebugHOpen))))
        );
    }

    private static boolean isValidOffset(int offset) {
        return offset == 0 || offset == 90 || offset == 180 || offset == 270;
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

    private static int executeDebugShow(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        ServerPlayer player = source.getPlayer();

        StringBuilder sb = new StringBuilder();
        sb.append("Door Debug (texture rotation is automatic):");
        sb.append("\n  hitboxClosedOffset: ").append(DoorDebugState.hitboxClosedOffset);
        sb.append("\n  hitboxOpenOffset: ").append(DoorDebugState.hitboxOpenOffset);

        if (player != null) {
            HitResult hit = player.pick(5.0, 0.0f, false);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = blockHit.getBlockPos();
                BlockState state = player.level().getBlockState(pos);
                if (state.getBlock() instanceof PoolBlock poolBlock) {
                    Direction facing = state.getValue(PoolBlock.FACING);
                    boolean open = state.getValue(PoolBlock.OPEN);
                    int slot = poolBlock.getSlotIndex();

                    sb.append("\n\nLooking at: slot ").append(slot);
                    sb.append(", FACING=").append(facing);
                    sb.append(", OPEN=").append(open);

                    BlockEntity be = player.level().getBlockEntity(pos);
                    if (be instanceof PoolBlockEntity poolBe) {
                        sb.append(", isDoor=").append(poolBe.isDoor());
                        sb.append(", blockType=").append(poolBe.getBlockType());
                    }

                    int hitboxOffset = open ? DoorDebugState.hitboxOpenOffset : DoorDebugState.hitboxClosedOffset;
                    Direction hitboxLookup = DoorDebugState.rotateFacing(facing, hitboxOffset);
                    sb.append("\n  Effective hitbox lookup: ").append(hitboxLookup);
                } else {
                    sb.append("\n\nNot looking at a ShapeCraft block.");
                }
            } else {
                sb.append("\n\nNot looking at a block.");
            }
        }

        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int executeDebugHClosed(CommandContext<CommandSourceStack> context) {
        int offset = IntegerArgumentType.getInteger(context, "offset");
        if (!isValidOffset(offset)) {
            context.getSource().sendFailure(Component.literal("Offset must be 0, 90, 180, or 270."));
            return 0;
        }
        DoorDebugState.hitboxClosedOffset = offset;
        context.getSource().sendSuccess(() -> Component.literal(
                "hitboxClosedOffset = " + offset + " (effective immediately)."), false);
        return 1;
    }

    private static int executeDebugHOpen(CommandContext<CommandSourceStack> context) {
        int offset = IntegerArgumentType.getInteger(context, "offset");
        if (!isValidOffset(offset)) {
            context.getSource().sendFailure(Component.literal("Offset must be 0, 90, 180, or 270."));
            return 0;
        }
        DoorDebugState.hitboxOpenOffset = offset;
        context.getSource().sendSuccess(() -> Component.literal(
                "hitboxOpenOffset = " + offset + " (effective immediately)."), false);
        return 1;
    }
}
