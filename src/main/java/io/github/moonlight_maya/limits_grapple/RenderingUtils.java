package io.github.moonlight_maya.limits_grapple;

import io.github.moonlight_maya.limits_grapple.item.GrappleItem;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2FloatArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class RenderingUtils {

	public static Vec3f getTransformedAnchorThirdPerson(AbstractClientPlayerEntity playerEntity, Vec3d anchor, boolean left) {
		float tickDelta = MinecraftClient.getInstance().getTickDelta();
		anchor = anchor.subtract(playerEntity.getLerpedPos(tickDelta));
		Vec3f transformedAnchor = new Vec3f(anchor);
		float entityYaw = getTheH(playerEntity);
		transformedAnchor.transform(new Matrix3f(Vec3f.POSITIVE_Y.getDegreesQuaternion(entityYaw)));
		float leaningPitch = playerEntity.getLeaningPitch(tickDelta);
		if (leaningPitch > 0) {
			float pitchMod = playerEntity.isTouchingWater() ? playerEntity.getPitch(tickDelta) : 0;
			Quaternion quat = Vec3f.NEGATIVE_X.getDegreesQuaternion(leaningPitch * (90 + pitchMod));
			transformedAnchor.transform(new Matrix3f(quat));
			if (playerEntity.isInSwimmingPose())
				transformedAnchor.add(0, 0.875f, -0.3f*0.875f);
		}
		//transformedAnchor is now in player space.
		Vec3f playerSpacePivot = new Vec3f((left ? 1 : -1) * 5*0.875f/16f, (playerEntity.isInSneakingPose() ? 18.8f : 22) * 0.875f / 16, 0);
		transformedAnchor.subtract(playerSpacePivot); //TransformedAnchor is now relative to the player space pivot.
		return transformedAnchor;
	}

	//get that H!
	//local variable from LivingEntityRenderer$render()
	private static float getTheH(LivingEntity livingEntity) {
		float g = MinecraftClient.getInstance().getTickDelta();
		float h = MathHelper.lerpAngleDegrees(g, livingEntity.prevBodyYaw, livingEntity.bodyYaw);
		float j = MathHelper.lerpAngleDegrees(g, livingEntity.prevHeadYaw, livingEntity.headYaw);
		float k;
		float l;
		if (livingEntity.hasVehicle() && livingEntity.getVehicle() instanceof LivingEntity livingEntity2) {
			h = MathHelper.lerpAngleDegrees(g, livingEntity2.prevBodyYaw, livingEntity2.bodyYaw);
			k = j - h;
			l = MathHelper.wrapDegrees(k);
			if (l < -85.0F) {
				l = -85.0F;
			}
			if (l >= 85.0F) {
				l = 85.0F;
			}
			h = j - l;
			if (l * l > 2500.0F) {
				h += l * 0.2F;
			}
		}
		return h;
	}

	public static Vec3f transformWorldToView(Vec3d worldPos) {
		MinecraftClient client = MinecraftClient.getInstance();
		Camera camera = client.gameRenderer.getCamera();
		Matrix3f cameraMat = new Matrix3f(camera.getRotation());
		cameraMat.invert();
		Vec3f result = new Vec3f(worldPos.subtract(camera.getPos()));
		result.transform(cameraMat);
		return result;
	}

	public static void renderChainsBasic(ItemStack stack, double distance, MatrixStack matrices, VertexConsumerProvider vcp, int light, int overlay) {
		matrices.translate(0, -distance-1, 0);

		for (int i=0;i<distance-1; i++) {
			if (distance - 1 - i < 0.3) {
				break;
			}
			matrices.translate(0, 1, 0);
			MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(Blocks.CHAIN.getDefaultState(), matrices, vcp, light, overlay);
		}
	}

	/**
	 * Because I felt like it :)
	 *
	 * Fancy Formula. As t slides from 0 to 1, the grapple moves from hand to wall.
	 * x = (t^2 - t^16) * Distance / 35 * sin(40 * t)
	 * y = t * distance
	 * z = (t^2 - t^16) * Distance / 25 * cos(45 * t)
	 */
	public static void renderChainsFancy(ItemStack stack, double distance, MatrixStack matrices, VertexConsumerProvider vcp, int light, int overlay) {
		matrices.translate(0, 1, 0);
		AbstractClientPlayerEntity cpe = GrappleModClient.currentRenderedPlayerEntity;
		float tickDelta = MinecraftClient.getInstance().getTickDelta();

		double fireSpeed = GrappleItem.FIRE_SPEED_BASE + GrappleItem.FIRE_SPEED_PER_LEVEL * EnchantmentHelper.getLevel(GrappleMod.RANGE_ENCHANTMENT, stack);

		double rand = ((cpe.world.getTime() - cpe.getItemUseTime()) * 68239	% Math.PI) * 2 / Math.PI - 1; //Random yet consistent value from -1 to 1, chosen at the time of the item use

		double ticksElapsed = cpe.getItemUseTime() + tickDelta;
		double ticksToPullBack = Math.min(distance / 15, 3);
		double ticksToReach = distance / fireSpeed - ticksToPullBack;

		if (ticksElapsed > ticksToReach + ticksToPullBack) {
			renderChainsBasic(stack, distance, matrices, vcp, light, overlay);
			return;
		}

		double xRad = (3 + rand) * distance / 35;
		double zRad = (3 + rand*rand) * distance / 25;
		double xFreq = 40 + rand * 4;
		double zFreq = 45 + rand * 3;
		double dt = 1.0 / calcFancyChainCount(distance);
		double maxT = Math.min(ticksElapsed / ticksToReach, 1);

		if (ticksElapsed > ticksToReach) {
			double f = 1 - (ticksElapsed - ticksToReach) / ticksToPullBack;
			xRad *= f;
			zRad *= f;
			xFreq += (1 - f);
			zFreq += (1 - f);
		}

		Vec3f cur = new Vec3f(), prev = new Vec3f();

		for (double t = dt; t < maxT; t += dt) {
			//Calculate next x, z
			double t2 = t * t;
			t2 = t2 * t2 - t2; //t^2 - t^4

			double x = t2 * xRad * Math.sin(xFreq * t);
			double y = t * distance;
			double z = t2 * zRad * Math.cos(zFreq * t);
			cur.set((float) x, (float) y, (float) z);

			//Calculate difference, update prev
			Vec3f diff = cur.copy();
			diff.subtract(prev);
			prev.set(cur);

			if (y < 0.5)
				continue;

			//Transform and render chain according to diff
			float len = MathHelper.fastInverseSqrt(diff.dot(diff));
			diff.scale(len);
			len = 1 / len;

			float pitch = (float) Math.asin(diff.getZ());
			float yaw = (float) Math.atan2(diff.getY(), diff.getX())-MathHelper.PI/2;

			matrices.push();
			matrices.translate(x, (t - dt) * -distance, z);
			matrices.multiply(Vec3f.POSITIVE_X.getRadialQuaternion(pitch));
			matrices.multiply(Vec3f.POSITIVE_Z.getRadialQuaternion(yaw));
			matrices.scale(1, len, 1);
			matrices.translate(0, -1, 0);
			MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(Blocks.CHAIN.getDefaultState(), matrices, vcp, light, overlay);
			matrices.pop();
		}


//		matrices.scale(1, (float) distance, 1);
//		MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(Blocks.CHAIN.getDefaultState(), matrices, vcp, light, overlay);

	}

	private static int calcFancyChainCount(double distance) {
		return (int) (2 * distance * Math.pow(1.5, 1 + distance / 25));
	}

}
