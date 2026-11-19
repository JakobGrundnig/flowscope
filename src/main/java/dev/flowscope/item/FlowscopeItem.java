package dev.flowscope.item;

import dev.flowscope.session.LensSessionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Handheld lens. Using it on a block starts a temporary, server-side recording of that face's item inventory.
 *
 * onItemUseFirst runs before the block's own interaction (so a chest does not open instead) but after
 * NeoForge's RightClickBlock event, so protection/claim mods that cancel that event also block the lens.
 */
public class FlowscopeItem extends Item {
    public FlowscopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (context.getLevel().isClientSide()) {
            // The server decides; the client only claims the click so the block's GUI does not open.
            return InteractionResult.SUCCESS;
        }
        if (context.getPlayer() instanceof ServerPlayer player && context.getLevel() instanceof ServerLevel level) {
            LensSessionManager.tryStart(player, level, context.getClickedPos(), context.getClickedFace());
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Using the lens in the air reopens the screen of the current (or last finished) recording. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            LensSessionManager.reopen(serverPlayer);
        }
        return InteractionResult.SUCCESS_SERVER;
    }
}
