package dev.coffeeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.coffeeclient.module.modules.misc.InvLeak;
import dev.coffeeclient.module.modules.player.InventoryPlus;
import dev.coffeeclient.module.modules.player.Organizer;
import dev.coffeeclient.module.modules.misc.SlotHighlight;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Organizer: consume the sort key over a container and clear sorting when the screen closes.
 * Inventory+: drop shift-clicks into a full side and drags with an unstackable item.
 * SlotHighlight: slot highlights and search dimming, and keys going to its search bar.
 * InvLeak: your slots, the cursor and drag preview drawn disguised, and no tooltip over your slots.
 */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenMixin {
	@Shadow
	protected Slot hoveredSlot;

	@Shadow
	@Final
	protected AbstractContainerMenu menu;

	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$fullContainerGuard(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
		if (InventoryPlus.blocksQuickMove(menu, slot, input)) ci.cancel();
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$noUnstackableDrag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
		if (InventoryPlus.blocksDrag(menu)) cir.setReturnValue(true);
	}

	/** While the search bar is typed in, keys are its (E, Q or a number would act on the screen). */
	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$searchKeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (event.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && SlotHighlight.typing()) {
			SlotHighlight.box().keyPressed(event);
			cir.setReturnValue(true);
		}
	}

	@WrapOperation(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;", ordinal = 0))
	private ItemStack coffeeclient$invLeakSlot(Slot slot, Operation<ItemStack> original) {
		return InvLeak.container(original.call(slot), slot);
	}

	@WrapOperation(method = "extractSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;copyWithCount(I)Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack coffeeclient$invLeakDrag(ItemStack stack, int count, Operation<ItemStack> original) {
		return InvLeak.carried(original.call(stack, count));
	}

	@ModifyVariable(method = "extractFloatingItem", at = @At("HEAD"), argsOnly = true)
	private ItemStack coffeeclient$invLeakCarried(ItemStack stack) {
		return InvLeak.carried(stack);
	}

	// Slot overlays read the real item, so they stay off your slots while InvLeak disguises them.
	@Inject(method = "extractSlot", at = @At("HEAD"))
	private void coffeeclient$slotBehind(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		if (!InvLeak.ownSlot(slot)) SlotHighlight.behind(graphics, slot);
	}

	@Inject(method = "extractSlot", at = @At("RETURN"))
	private void coffeeclient$slotDim(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		if (InvLeak.ownSlot(slot)) return;
		dev.coffeeclient.module.modules.render.BetterTooltips.slotIcon(graphics, slot);
		SlotHighlight.after(graphics, slot);
	}

	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$invLeakTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (InvLeak.hidesTooltip(hoveredSlot)) ci.cancel();
	}

	/** BetterTooltips' lock key: the held tooltip instead of the hovered one. */
	@Inject(method = "extractTooltip", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$lockedTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (dev.coffeeclient.module.modules.render.BetterTooltips.lockedTooltip(graphics, hoveredSlot, mouseX, mouseY)) ci.cancel();
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$sortKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		Organizer module = Organizer.instance();
		if (module != null && module.onSortKey((AbstractContainerScreen<?>) (Object) this, hoveredSlot, event.key())) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void coffeeclient$sortClick(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
		Organizer module = Organizer.instance();
		if (module != null && module.onSortKey((AbstractContainerScreen<?>) (Object) this, hoveredSlot, event.button())) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void coffeeclient$onClose(CallbackInfo ci) {
		Organizer.onScreenClosed();
	}
}
