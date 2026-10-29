package dev.coffeeclient.mixin;

import dev.coffeeclient.core.CoffeeClient;
import dev.coffeeclient.module.modules.render.Nametags;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {

    @Inject(method = "getNameTag", at = @At("HEAD"), cancellable = true)
    private void onGetNameTag(T entity, CallbackInfoReturnable<Component> cir) {
        if (dev.coffeeclient.module.modules.render.Ambience.hidesNameTag(entity)) {
            cir.setReturnValue(null);
            return;
        }
        Nametags nametags = CoffeeClient.modules().get(Nametags.class);
        if (nametags != null && nametags.isEnabled() && entity instanceof LivingEntity && nametags.handles(entity)) {
            // Nametags owns this entity's label, including when it deliberately hides it (bots, friends, culling).
            cir.setReturnValue(null);
        }
    }
}