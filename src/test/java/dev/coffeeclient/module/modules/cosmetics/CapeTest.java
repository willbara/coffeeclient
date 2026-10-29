package dev.coffeeclient.module.modules.cosmetics;

import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapeTest {
	private static final Identifier DEFAULT_WINGS = Identifier.withDefaultNamespace("textures/entity/equipment/wings/elytra.png");
	private static final ClientAsset.Texture BODY = tex("textures/entity/player/wide/steve.png");
	private static final ClientAsset.Texture CAPE = tex("textures/entity/cape/account.png");
	private static final ClientAsset.Texture OWN_ELYTRA = tex("textures/entity/elytra/account.png");

	private static ClientAsset.Texture tex(String path) {
		Identifier id = Identifier.withDefaultNamespace(path);
		return new ClientAsset.ResourceTexture(id, id);
	}

	private static PlayerSkin skin(ClientAsset.Texture cape, ClientAsset.Texture elytra) {
		return new PlayerSkin(BODY, cape, elytra, PlayerModelType.WIDE, true);
	}

	@Test
	void noElytraAndNoCapeFallBackToTheDefaultWings() {
		assertEquals(DEFAULT_WINGS, Cape.sourceWings(skin(null, null)).texturePath());
	}

	@Test
	void noElytraFallsBackToTheCape() {
		assertEquals(CAPE.texturePath(), Cape.sourceWings(skin(CAPE, null)).texturePath());
	}

	@Test
	void ownElytraBeatsTheCape() {
		assertEquals(OWN_ELYTRA.texturePath(), Cape.sourceWings(skin(CAPE, OWN_ELYTRA)).texturePath());
	}
}
