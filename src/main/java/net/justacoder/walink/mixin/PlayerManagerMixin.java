package net.justacoder.walink.mixin;

import net.justacoder.walink.WALMain;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;
import java.util.function.Predicate;

@Mixin(PlayerList.class)
public abstract class PlayerManagerMixin {

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V", at = @At("HEAD"))
    private void onChatMessageSent(PlayerChatMessage message, Predicate<ServerPlayer> isFiltered, @Nullable ServerPlayer senderPlayer, ChatType.Bound chatType, CallbackInfo ci) {
        if (senderPlayer != null) {
            WALMain.messageReceivedMC(senderPlayer.getName().getString(), message.signedContent());
            return;
        }
        WALMain.messageReceivedMC(null, message.signedContent());
    }

    @Inject(method = "broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Ljava/util/function/Function;Z)V", at = @At("HEAD"))
    private void onBroadcast(Component message, Function<ServerPlayer, Component> playerMessages, boolean overlay, CallbackInfo ci) {
        WALMain.messageReceivedMC(null, message.getString());
    }

}
