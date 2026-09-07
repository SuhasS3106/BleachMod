package com.bleach.mod.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Draws nothing. The reishi arrow ({@link com.bleach.mod.entity.ReishiArrow}) is visible only as its
 * particle trail, but Minecraft still needs a renderer registered for the entity type or it logs an
 * error every time one spawns. Registered client-side only from {@code BleachModClient}.
 */
public class InvisibleEntityRenderer<T extends Entity> extends EntityRenderer<T> {
	private static final ResourceLocation EMPTY =
			ResourceLocation.withDefaultNamespace("textures/misc/white.png");

	public InvisibleEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public boolean shouldRender(T entity, Frustum frustum, double x, double y, double z) {
		return false;
	}

	@Override
	public ResourceLocation getTextureLocation(T entity) {
		return EMPTY;
	}
}
