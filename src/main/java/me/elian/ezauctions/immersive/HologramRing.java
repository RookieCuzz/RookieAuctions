package me.elian.ezauctions.immersive;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Five translucent TextDisplay strokes forming a small holographic control ring. */
final class HologramRing {
	private static final int SEGMENTS = 5;
	private static final float RADIUS = 0.125F;
	private static final float STROKE = 0.025F;
	private static final float SEGMENT_LENGTH =
			(float) (2D * Math.tan(Math.PI / SEGMENTS) * RADIUS);
	// A TextDisplay containing one space renders a background quad. This maps that
	// native quad to a unit square before each segment is positioned and scaled.
	private static final Matrix4f TEXT_QUAD = new Matrix4f()
			.translate(0.4F, 0F, 0F)
			.scale(8F, 4F, 1F);
	private static final Color DISABLED = Color.fromARGB(160, 1, 21, 24);
	private static final Color IDLE = Color.fromARGB(235, 11, 98, 191);
	private static final Color HOVERED = Color.fromARGB(255, 0, 186, 219);
	private static final Color SELECTED = Color.fromARGB(255, 3, 216, 242);

	private final List<TextDisplay> strokes;
	private boolean removed;

	private HologramRing(List<TextDisplay> strokes) {
		this.strokes = strokes;
	}

	static HologramRing spawn(Plugin plugin, Player viewer, Location center) {
		Objects.requireNonNull(plugin, "plugin");
		Objects.requireNonNull(viewer, "viewer");
		Objects.requireNonNull(center, "center");
		World world = Objects.requireNonNull(center.getWorld(), "ring world");
		NamespacedKey markerKey = new NamespacedKey(plugin, "seated-bid-menu");
		List<TextDisplay> strokes = new ArrayList<>(SEGMENTS);
		try {
			for (int index = 0; index < SEGMENTS; index++) {
				final int segment = index;
				TextDisplay display = world.spawn(center, TextDisplay.class, entity -> {
					entity.setPersistent(false);
					entity.setVisibleByDefault(false);
					entity.setGravity(false);
					entity.setInvulnerable(true);
					entity.setSilent(true);
					entity.getPersistentDataContainer().set(markerKey,
							PersistentDataType.STRING, viewer.getUniqueId() + ":ring:" + segment);
					entity.setBillboard(Display.Billboard.CENTER);
					entity.setDefaultBackground(false);
					entity.setShadowed(false);
					entity.setSeeThrough(true);
					entity.setBrightness(new Display.Brightness(15, 15));
					entity.setViewRange(2F);
					entity.setTeleportDuration(1);
					entity.setInterpolationDuration(5);
					entity.text(Component.text(" "));
					entity.setBackgroundColor(IDLE);
					entity.setTransformationMatrix(segmentMatrix(segment));
				});
				strokes.add(display);
				viewer.showEntity(plugin, display);
			}
			return new HologramRing(strokes);
		} catch (RuntimeException error) {
			strokes.forEach(TextDisplay::remove);
			throw error;
		}
	}

	void move(Location center) {
		Objects.requireNonNull(center, "center");
		if (removed) {
			return;
		}
		for (TextDisplay stroke : strokes) {
			if (stroke.isValid()) {
				stroke.teleport(center);
			}
		}
	}

	void style(boolean enabled, boolean hovered, boolean selected) {
		if (removed) {
			return;
		}
		Color target = !enabled ? DISABLED : selected ? SELECTED : hovered ? HOVERED : IDLE;
		for (TextDisplay stroke : strokes) {
			if (stroke.isValid()) {
				Color current = stroke.getBackgroundColor();
				Color next = current == null ? target : blend(current, target);
				if (!next.equals(current)) {
					stroke.setBackgroundColor(next);
				}
			}
		}
	}

	boolean valid() {
		if (removed || strokes.size() != SEGMENTS) {
			return false;
		}
		for (TextDisplay stroke : strokes) {
			if (!stroke.isValid()) {
				return false;
			}
		}
		return true;
	}

	void remove() {
		if (removed) {
			return;
		}
		removed = true;
		strokes.forEach(TextDisplay::remove);
	}

	private static Matrix4f segmentMatrix(int index) {
		float angle = (float) (index * 2D * Math.PI / SEGMENTS);
		return new Matrix4f()
				.rotateZ(angle)
				.translate(RADIUS - STROKE, -SEGMENT_LENGTH / 2F, 0F)
				.scale(STROKE, SEGMENT_LENGTH, 0.001F)
				.mul(TEXT_QUAD);
	}

	private static Color blend(Color current, Color target) {
		// The menu refreshes every five ticks. A 55% step gives a short, smooth
		// hover/selection transition without scheduling work for each ring.
		return Color.fromARGB(
				step(current.getAlpha(), target.getAlpha()),
				step(current.getRed(), target.getRed()),
				step(current.getGreen(), target.getGreen()),
				step(current.getBlue(), target.getBlue()));
	}

	private static int step(int current, int target) {
		int next = Math.round(current + (target - current) * 0.55F);
		return next == current ? target : next;
	}
}
