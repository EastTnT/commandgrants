package com.example.commandgrants.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.network.chat.Component;

import com.example.commandgrants.client.CommandGrantsClient;
import com.example.commandgrants.client.GrantEditorScreen;

/**
 * Adds the grant editor to the screen that replaced "Open to LAN".
 *
 * <p>The button goes into the header, pinned to the left, rather than into the footer: the footer is a
 * {@link HeaderAndFooterLayout} frame that centres everything in it, so a second child there would be
 * drawn straight on top of vanilla's Apply/Cancel row instead of beside it. The header frame has the
 * same centring rule, which is why the button asks for {@code alignHorizontallyLeft} explicitly.
 *
 * <p>Injecting right after the title header guarantees the frames exist and that the screen's own
 * widgets are added first — {@code init()} only calls {@code layout.visitWidgets} at the very end, so
 * anything added before that point is registered, arranged and rendered normally.
 *
 * <p>The button is disabled rather than hidden for players who cannot edit, so the feature is visible
 * without offering a control that the server would refuse anyway.
 */
@Environment(EnvType.CLIENT)
@Mixin(MultiplayerOptionsScreen.class)
public abstract class MultiplayerOptionsScreenMixin {
    @Shadow
    @Final
    private HeaderAndFooterLayout layout;

    @Inject(method = "init",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/HeaderAndFooterLayout;addTitleHeader(Lnet/minecraft/network/chat/Component;Lnet/minecraft/client/gui/Font;)V",
                    shift = At.Shift.AFTER))
    private void commandgrants$addEditorButton(CallbackInfo info) {
        Minecraft minecraft = Minecraft.getInstance();
        // Only meaningful where a server this client controls exists; a remote server's grants are
        // administered with /commandgrants by its operators instead.
        if (minecraft.getSingleplayerServer() == null) {
            return;
        }
        Button editor = Button.builder(Component.translatable("commandgrants.editor.open"),
                        button -> GrantEditorScreen.open((MultiplayerOptionsScreen) (Object) this))
                .width(150)
                .build();
        editor.active = CommandGrantsClient.canEdit();
        this.layout.addToHeader(editor, settings -> settings
                .alignHorizontallyLeft()
                .paddingLeft(CommandGrantsClient.HEADER_MARGIN));
    }
}
