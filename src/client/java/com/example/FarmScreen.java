package com.example;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public abstract class FarmScreen extends Screen {

	protected static int FRAME_DARK;
	protected static int PLANK;
	protected static int PLANK_LIGHT;
	protected static int PLANK_DARK;
	protected static int BORDER;
	protected static int GREEN;
	protected static int GREEN_DARK;
	protected static final int RED = 0xFFC85040;
	protected static final int CREAM = 0xFFF5E6C8;

	/** Bingo ball body colours (B/I/N/G/O), reused every frame by the Bingo backdrop. */
	private static final int[] BINGO_BALLS = {0xE0E23B3B, 0xE02E6FD8, 0xE0F2C14E, 0xE03FAE63, 0xE0F0EDE2};

	static {
		applyTheme();
	}

	public static void applyTheme() {
		ForgeCalcTheme t = ForgeCalcTheme.current();
		FRAME_DARK = t.frameDark;
		PLANK = t.plank;
		PLANK_LIGHT = t.plankLight;
		PLANK_DARK = t.plankDark;
		BORDER = t.border;
		GREEN = t.accent;
		GREEN_DARK = t.accentDark;
	}

	private static class Particle {
		float x, y, vx, vy, size, phase;
	}

	private long screenOpenedAt = System.currentTimeMillis();
	private final java.util.Map<String, Float> hoverAnim = new java.util.HashMap<>();

	/** 0..1 ease-out over the first 220ms a screen is visible. */
	protected float openProgress() {
		float t = Math.min(1f, (System.currentTimeMillis() - screenOpenedAt) / 220f);
		return 1f - (1f - t) * (1f - t);
	}

	/** Smoothly eases a per-button hover value so highlights fade instead of snapping. */
	protected float hoverAmount(String key, boolean hovered) {
		float current = hoverAnim.getOrDefault(key, 0f);
		float target = hovered ? 1f : 0f;
		float speed = 0.22f;
		current += (target - current) * speed;
		if (Math.abs(target - current) < 0.01f) current = target;
		hoverAnim.put(key, current);
		return current;
	}

	private final List<Particle> particles = new ArrayList<>();
	private long lastFrame = System.nanoTime();
	private final Random random = new Random();

	public static boolean cursedActive() {
		return ForgeCalcTheme.current() == ForgeCalcTheme.CURSED;
	}

	/** Returns a small random screen shake offset when the Cursed theme is on. */
	protected int[] cursedShake() {
		if (!cursedActive()) return new int[]{0, 0};
		long t = System.currentTimeMillis();
		if ((t / 90) % 3 == 0) {
			return new int[]{(int) (Math.sin(t) * 4), (int) (Math.cos(t * 1.3) * 4)};
		}
		return new int[]{0, 0};
	}

	protected void drawThemeBackdrop(DrawContext ctx) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		ForgeCalcTheme theme = ForgeCalcTheme.current();

		ThemeBackgrounds.render(ctx, this.width, this.height);

		if (theme == ForgeCalcTheme.CURSED) {
			long t = System.currentTimeMillis();
			// random full-screen blink between blood-red wash and pitch black
			long phase = (t / 120) % 7;
			if (phase == 0) {
				ctx.fill(0, 0, this.width, this.height, 0x60FF0000);
			} else if (phase == 3) {
				ctx.fill(0, 0, this.width, this.height, 0xC0000000);
			} else if (phase == 5) {
				ctx.fill(0, 0, this.width, this.height, 0x4000FF00);
			}
			// creeping scanlines that jump around
			int scan = (int) ((t / 40) % this.height);
			ctx.fill(0, scan, this.width, scan + 2, 0x40FFFFFF);
			// flickering "get out" style text at random spots
			if ((t / 400) % 4 == 0) {
				String[] words = {"§4§lLEAVE", "§c§lRUN", "§2§lIT SEES YOU", "§4§lNO ESCAPE"};
				String word = words[(int) ((t / 400) % words.length)];
				int wx = (int) ((Math.sin(t * 0.01) * 0.5 + 0.5) * (this.width - 60));
				int wy = (int) ((Math.cos(t * 0.013) * 0.5 + 0.5) * (this.height - 20));
				ctx.drawText(this.textRenderer, net.minecraft.text.Text.literal(word), wx, wy, 0xFFFFFFFF, true);
			}
		}

		if (particles.size() < 45 && this.width > 0) {
			for (int i = particles.size(); i < 45; i++) {
				particles.add(spawn(true));
			}
		}

		float time = (now % 100_000_000_000L) / 1_000_000_000f;

		if (theme == ForgeCalcTheme.MOON || theme == ForgeCalcTheme.ALPHA_WOLF) {
			drawMoon(ctx, this.width - 70, 46, 26, theme == ForgeCalcTheme.MOON ? 0xFFE8EFFF : 0xFFDADAE6);
		}

		for (Particle particle : particles) {
			if (theme == ForgeCalcTheme.ANGRY_BIRDS) {
				particle.vy += 120 * dt;
			}
			particle.x += particle.vx * dt;
			particle.y += particle.vy * dt;
			if (particle.y > this.height + 10 || particle.y < -10 || particle.x < -10 || particle.x > this.width + 10) {
				reset(particle);
			}

			int x = (int) particle.x;
			int y = (int) particle.y;
			int sz = (int) particle.size;
			float tw = (float) Math.sin(time * 2 + particle.phase);

			switch (theme) {
				case CHERRY -> {
					int pink = tw > 0 ? 0xE0FF9BC7 : 0xE0FFB7D9;
					ctx.fill(x, y, x + sz, y + sz, pink);
					ctx.fill(x + (tw > 0 ? sz : -1), y + 1, x + (tw > 0 ? sz + 1 : 0), y + sz, 0xA0FFD4E6);
				}
				case SPACE -> {
					int alpha = (int) (120 + 120 * tw) << 24;
					ctx.fill(x, y, x + Math.max(1, sz - 1), y + Math.max(1, sz - 1), alpha | 0xFFFFFF);
				}
				case HACKER -> {
					ctx.fill(x, y, x + 1, y + sz * 3, 0x9000FF66);
					ctx.fill(x, y + sz * 3, x + 1, y + sz * 3 + 2, 0xF066FFB2);
				}
				case NETHER -> {
					int ember = tw > 0 ? 0xD0FF5533 : 0xD0FFB347;
					ctx.fill(x, y, x + sz, y + sz, ember);
				}
				case FARMER -> {
					int leaf = tw > 0 ? 0xC06FBF4A : 0xC0A0D060;
					ctx.fill(x, y, x + sz, y + Math.max(1, sz - 1), leaf);
				}
				case BLOOD -> {
					ctx.fill(x, y, x + 2, y + sz * 3, 0xC0CC1111);
					ctx.fill(x, y + sz * 3, x + 2, y + sz * 3 + 2, 0xF0FF3333);
				}
				case MOON -> {
					int alpha = (int) (110 + 110 * tw) << 24;
					ctx.fill(x, y, x + Math.max(1, sz - 1), y + Math.max(1, sz - 1), alpha | 0xC9D6F0);
				}
				case ALPHA_WOLF -> {
					int gray = tw > 0 ? 0x90E8E8F0 : 0x70A0A0AC;
					ctx.fill(x, y, x + Math.max(1, sz - 1), y + Math.max(1, sz - 1), gray);
				}
				case KITAGAWA -> {
					int pink = tw > 0 ? 0xD0FFB7D9 : 0xD0FFE3F0;
					ctx.fill(x, y, x + sz, y + sz, pink);
				}
				case SLIME -> {
					int green = tw > 0 ? 0xB07CFC5A : 0x904CBF3A;
					ctx.fill(x, y, x + sz + 1, y + sz + 1, green);
					ctx.fill(x + 1, y + 1, x + 2, y + 2, 0x80FFFFFF);
				}
				case POOP -> {
					int brown = tw > 0 ? 0xC07A4E28 : 0xC05C3A1E;
					ctx.fill(x, y, x + sz, y + sz, brown);
					ctx.fill(x + 1, y - 1, x + sz - 1, y, brown);
				}
				case DARK -> {
					int gray = (int) (40 + 40 * tw) << 24;
					ctx.fill(x, y, x + Math.max(1, sz - 1), y + Math.max(1, sz - 1), gray | 0xFFFFFF);
				}
				case LIGHT -> {
					int blue = (int) (50 + 40 * tw) << 24;
					ctx.fill(x, y, x + sz, y + sz, blue | 0x4FA3FF);
				}
				case BEANS -> {
					int bean = tw > 0 ? 0xC08A5A36 : 0xC06B4226;
					ctx.fill(x, y, x + sz + 1, y + sz, bean);
					ctx.fill(x + 1, y - 1, x + sz, y, bean);
					ctx.fill(x + 1, y + 1, x + 2, y + 2, 0x60FFE8C4);
				}
				case CAR -> {
					ctx.fill(x, y, x + sz * 3, y + 1, tw > 0 ? 0xC0FFAA00 : 0xC0FF3333);
				}
				case EARTH -> {
					if (particle.phase > 0.5f) {
						// drifting cloud puff
						ctx.fill(x, y, x + sz * 3, y + sz, 0x50FFFFFF);
						ctx.fill(x + sz, y - sz + 1, x + sz * 2 + 1, y, 0x40FFFFFF);
						ctx.fill(x - 1, y + 1, x + sz * 3 + 1, y + sz - 1, 0x28FFFFFF);
					} else {
						// leaf speck
						ctx.fill(x, y, x + 2, y + 2, 0xA048C878);
					}
				}
				case ANGRY_BIRDS -> {
					// slingshot arcs: red birds with a beak
					int body = particle.phase > 0.5f ? 0xE0E03020 : 0xE0FFD427;
					ctx.fill(x, y, x + sz + 2, y + sz + 2, body);
					ctx.fill(x + sz + 2, y + 1, x + sz + 4, y + 3, 0xE0FF9010);
					ctx.fill(x + 1, y + 1, x + 3, y + 3, 0xFFFFFFFF);
				}
				case MINECRAFT -> {
					// grass-block motes
					ctx.fill(x, y, x + sz + 1, y + sz + 1, 0xC08B6239);
					ctx.fill(x, y, x + sz + 1, y + 2, 0xC05FD22F);
				}
				case CLAUDE -> {
					// soft warm sparks drifting up
					int glow = (int) (0x50 + 0x50 * Math.abs(tw)) << 24;
					ctx.fill(x, y, x + sz, y + sz, glow | 0xD97757);
				}
				case MINING -> {
					// falling pickaxe sparks + dust
					if (particle.phase > 0.6f) {
						ctx.fill(x, y, x + 1, y + sz + 2, 0xB0FFC845);
					} else {
						ctx.fill(x, y, x + sz, y + sz, 0x90776655);
					}
				}
				case CODER -> {
					// falling code: green 1s and 0s (matrix rain)
					String glyph = (particle.phase > 0.5f) ? "1" : "0";
					int green = tw > 0 ? 0xE03DDC84 : 0x903DDC84;
					ctx.drawText(this.textRenderer, net.minecraft.text.Text.literal(glyph), x, y, green, false);
				}
				case NOLIFE -> {
					// slow purple starfield with the occasional pink twinkle
					int col = tw > 0.6f ? 0xF0EC4899 : 0x90000000 | 0x8B5CF6;
					int s2 = tw > 0.6f ? sz + 1 : sz;
					ctx.fill(x, y, x + s2, y + s2, col);
				}
				case NEJI -> {
					// bats flapping across a dark sky
					int wing = tw > 0 ? 1 : 0;
					int col = 0xD0301038;
					ctx.fill(x, y + wing, x + 2, y + 2 + wing, col);
					ctx.fill(x - 3, y - wing, x, y + 2 - wing, col);
					ctx.fill(x + 2, y - wing, x + 5, y + 2 - wing, col);
				}
				case JOSH_SAFARI -> {
					// dust motes + acacia-gold specks drifting on the savanna wind
					if (particle.phase > 0.5f) {
						ctx.fill(x, y, x + sz + 1, y + sz, 0xB0E0A020);
					} else {
						ctx.fill(x, y, x + 2, y + 2, 0x90C89848);
					}
				}
				case CURSED -> {
					// jittering blood-red / sickly-green glitch blocks
					int jx = x + (int) (Math.sin(time * 40 + particle.phase * 9) * 6);
					int jy = y + (int) (Math.cos(time * 37 + particle.phase * 7) * 6);
					int col = ((int) (time * 13 + particle.phase * 100) % 2 == 0) ? 0xE0FF0000 : 0xE000FF00;
					ctx.fill(jx, jy, jx + sz + 2, jy + sz + 2, col);
				}
				case AFKABLE -> {
					// slow emerald motes drifting up a near-black page, with the odd bright spark
					int halo = (int) (0x10 + 0x10 * Math.abs(tw)) << 24;
					ctx.fill(x - 1, y - 1, x + sz + 1, y + sz + 1, halo | 0x4ADE80);
					if (particle.phase > 5.6f) {
						int arm = sz / 2;
						ctx.fill(x - 2, y + arm, x + sz + 2, y + arm + 1, 0x704ADE80);
						ctx.fill(x + arm, y - 2, x + arm + 1, y + sz + 2, 0x704ADE80);
						ctx.fill(x, y, x + sz, y + sz, 0xF0C8FFDE);
					} else {
						int core = (int) (0x60 + 0x50 * tw) << 24;
						ctx.fill(x, y, x + sz, y + sz, core | 0x4ADE80);
					}
				}
				case BLACKHOLE -> {
					// orbital streak pulled toward the center
					float cx = this.width / 2f;
					float cy = this.height / 2f;
					float dx = cx - particle.x;
					float dy = cy - particle.y;
					float dist = (float) Math.sqrt(dx * dx + dy * dy);
					if (dist < 24) {
						particle.x = random.nextBoolean() ? -6 : this.width + 6;
						particle.y = random.nextFloat() * this.height;
					} else {
						float nx = dx / dist;
						float ny = dy / dist;
						float speed = 30 + 2600 / dist;
						particle.vx = (nx * 0.55f - ny * 0.85f) * speed;
						particle.vy = (ny * 0.55f + nx * 0.85f) * speed;
					}
					int glow = dist < 90 ? 0xE0FF8C28 : (tw > 0 ? 0xA0B44AFF : 0x707038B0);
					ctx.fill(x, y, x + sz, y + 1, glow);
					ctx.fill(x, y, x + 1, y + sz, glow);
				}
				case BINGO -> {
					// drifting bingo balls: B/I/N/G/O ball colours, blob body + pale number patch
					int col = BINGO_BALLS[((int) (particle.phase * 4f)) % BINGO_BALLS.length];
					int d = sz + 3;
					ctx.fill(x + 1, y, x + d - 1, y + d, col);
					ctx.fill(x, y + 1, x + d, y + d - 1, col);
					ctx.fill(x + 2, y + 2, x + d - 2, y + d - 2, tw > 0 ? 0xFFF7F0DC : 0xFFE6DCC0);
					ctx.fill(x + 1, y + 1, x + 2, y + 2, 0x80FFFFFF);
				}
				case COLOR -> {
					// rainbow confetti: hue fanned out per particle, width flutters as it spins
					int hue = ForgeCalcTheme.rainbow(0.35f, particle.phase * 0.16f);
					int jx = x + (int) (Math.sin(time * 3 + particle.phase) * 3);
					int cw = tw > 0 ? sz + 2 : Math.max(1, sz - 1);
					ctx.fill(jx, y, jx + cw, y + 2, 0xE0000000 | (hue & 0xFFFFFF));
					ctx.fill(jx, y + 2, jx + cw, y + 3, 0x60FFFFFF);
				}
				default -> {
					int alpha = (int) (50 + 40 * tw) << 24;
					ctx.fill(x, y, x + sz, y + sz, alpha | 0x4FA3FF);
				}
			}
		}
	}

	private void drawMoon(DrawContext ctx, int cx, int cy, int r, int color) {
		for (int ring = 3; ring >= 1; ring--) {
			fillCircle(ctx, cx, cy, r + ring * 3, ((14 * (4 - ring)) << 24) | (color & 0xFFFFFF), 0, 0, 0);
		}
		fillCircle(ctx, cx, cy, r, color, cx + r / 2, cy - r / 4, (int) (r * 0.92f));
	}

	private void fillCircle(DrawContext ctx, int cx, int cy, int r, int color, int cx2, int cy2, int r2) {
		for (int dy = -r; dy <= r; dy++) {
			int half = (int) Math.sqrt(r * r - dy * dy);
			int y = cy + dy;
			int left = cx - half;
			int right = cx + half + 1;

			if (r2 > 0 && Math.abs(y - cy2) <= r2) {
				int dy2 = y - cy2;
				int half2 = (int) Math.sqrt(r2 * r2 - dy2 * dy2);
				int cutLeft = cx2 - half2;
				int cutRight = cx2 + half2 + 1;
				if (cutLeft > left) {
					ctx.fill(left, y, Math.min(right, cutLeft), y + 1, color);
				}
				if (cutRight < right) {
					ctx.fill(Math.max(left, cutRight), y, right, y + 1, color);
				}
			} else {
				ctx.fill(left, y, right, y + 1, color);
			}
		}
	}

	private Particle spawn(boolean anywhere) {
		Particle particle = new Particle();
		reset(particle);
		if (anywhere) {
			particle.y = random.nextFloat() * Math.max(1, this.height);
		}
		return particle;
	}

	private void reset(Particle particle) {
		ForgeCalcTheme theme = ForgeCalcTheme.current();
		particle.x = random.nextFloat() * Math.max(1, this.width);
		particle.phase = random.nextFloat() * 6.28f;
		particle.size = 2 + random.nextInt(3);
		switch (theme) {
			case CHERRY -> {
				particle.y = -8;
				particle.vx = 8 + random.nextFloat() * 14;
				particle.vy = 18 + random.nextFloat() * 22;
			}
			case SPACE -> {
				particle.y = random.nextFloat() * Math.max(1, this.height);
				particle.vx = -3 - random.nextFloat() * 5;
				particle.vy = 0;
				particle.size = 1 + random.nextInt(2);
			}
			case HACKER -> {
				particle.y = -12;
				particle.vx = 0;
				particle.vy = 60 + random.nextFloat() * 90;
			}
			case NETHER -> {
				particle.y = this.height + 8;
				particle.vx = (random.nextFloat() - 0.5f) * 10;
				particle.vy = -25 - random.nextFloat() * 30;
			}
			case FARMER -> {
				particle.y = -8;
				particle.vx = (random.nextFloat() - 0.3f) * 12;
				particle.vy = 12 + random.nextFloat() * 14;
			}
			case BLOOD -> {
				particle.y = -12;
				particle.vx = 0;
				particle.vy = 40 + random.nextFloat() * 70;
			}
			case MOON -> {
				particle.y = random.nextFloat() * Math.max(1, this.height);
				particle.vx = -2 - random.nextFloat() * 3;
				particle.vy = 0;
				particle.size = 1 + random.nextInt(2);
			}
			case ALPHA_WOLF -> {
				particle.y = -8;
				particle.vx = (random.nextFloat() - 0.5f) * 16;
				particle.vy = 14 + random.nextFloat() * 18;
			}
			case KITAGAWA -> {
				particle.y = -8;
				particle.vx = 6 + random.nextFloat() * 12;
				particle.vy = 16 + random.nextFloat() * 20;
			}
			case SLIME -> {
				particle.y = this.height + 8;
				particle.vx = (random.nextFloat() - 0.5f) * 8;
				particle.vy = -14 - random.nextFloat() * 16;
			}
			case POOP -> {
				particle.y = -8;
				particle.vx = (random.nextFloat() - 0.5f) * 6;
				particle.vy = 22 + random.nextFloat() * 24;
			}
			case DARK -> {
				particle.y = random.nextFloat() * Math.max(1, this.height);
				particle.vx = -3 - random.nextFloat() * 4;
				particle.vy = 0;
				particle.size = 1 + random.nextInt(2);
			}
			case LIGHT -> {
				particle.y = -8;
				particle.vx = (random.nextFloat() - 0.5f) * 8;
				particle.vy = 10 + random.nextFloat() * 12;
			}
			case BEANS -> {
				particle.y = -8;
				particle.vx = (random.nextFloat() - 0.5f) * 10;
				particle.vy = 18 + random.nextFloat() * 18;
			}
			case CAR -> {
				particle.y = random.nextFloat() * Math.max(1, this.height);
				particle.vx = -90 - random.nextFloat() * 120;
				particle.vy = 0;
			}
			case EARTH -> {
				particle.y = random.nextFloat() * Math.max(1, this.height);
				particle.vx = particle.phase > 0.5f ? 8 + random.nextFloat() * 10 : (random.nextFloat() - 0.5f) * 6;
				particle.vy = particle.phase > 0.5f ? 0 : 6 + random.nextFloat() * 8;
			}
			case ANGRY_BIRDS -> {
				particle.x = -8;
				particle.y = this.height * (0.35f + random.nextFloat() * 0.5f);
				particle.vx = 60 + random.nextFloat() * 90;
				particle.vy = -30 - random.nextFloat() * 40;
			}
			case MINECRAFT -> {
				particle.y = -8;
				particle.vx = (random.nextFloat() - 0.5f) * 6;
				particle.vy = 14 + random.nextFloat() * 16;
			}
			case CLAUDE -> {
				particle.y = this.height + 8;
				particle.vx = (random.nextFloat() - 0.5f) * 10;
				particle.vy = -10 - random.nextFloat() * 14;
			}
			case MINING -> {
				particle.y = -8;
				particle.vx = (random.nextFloat() - 0.5f) * 8;
				particle.vy = 26 + random.nextFloat() * 30;
			}
			case CODER -> {
				particle.x = (float) (Math.floor(random.nextFloat() * this.width / 6) * 6);
				particle.y = -8;
				particle.vx = 0;
				particle.vy = 30 + random.nextFloat() * 40;
			}
			case NOLIFE -> {
				particle.x = random.nextFloat() * this.width;
				particle.y = random.nextFloat() * this.height;
				particle.vx = (random.nextFloat() - 0.5f) * 4;
				particle.vy = 3 + random.nextFloat() * 6;
			}
			case NEJI -> {
				particle.x = -8;
				particle.y = random.nextFloat() * this.height * 0.7f;
				particle.vx = 30 + random.nextFloat() * 50;
				particle.vy = (random.nextFloat() - 0.5f) * 20;
			}
			case JOSH_SAFARI -> {
				particle.y = random.nextFloat() * Math.max(1, this.height);
				particle.vx = 12 + random.nextFloat() * 24;
				particle.vy = (random.nextFloat() - 0.5f) * 6;
			}
			case CURSED -> {
				particle.x = random.nextFloat() * this.width;
				particle.y = random.nextFloat() * this.height;
				particle.vx = (random.nextFloat() - 0.5f) * 200;
				particle.vy = (random.nextFloat() - 0.5f) * 200;
			}
			case AFKABLE -> {
				particle.y = this.height + 8;
				particle.vx = (random.nextFloat() - 0.5f) * 7;
				particle.vy = -6 - random.nextFloat() * 11;
				particle.size = 1 + random.nextInt(3);
			}
			case BLACKHOLE -> {
				particle.x = random.nextBoolean() ? -6 : this.width + 6;
				particle.y = random.nextFloat() * Math.max(1, this.height);
				particle.vx = 0;
				particle.vy = 0;
			}
			case BINGO -> {
				// balls tumbling down the hall, slow and heavy
				particle.y = -12;
				particle.vx = (random.nextFloat() - 0.5f) * 14;
				particle.vy = 14 + random.nextFloat() * 18;
				particle.size = 2 + random.nextInt(3);
			}
			case COLOR -> {
				// confetti drop: quick fall, wide sideways spread
				particle.y = -10;
				particle.vx = (random.nextFloat() - 0.5f) * 26;
				particle.vy = 26 + random.nextFloat() * 34;
				particle.size = 2 + random.nextInt(3);
			}
			default -> {
				particle.y = this.height + 8;
				particle.vx = (random.nextFloat() - 0.5f) * 6;
				particle.vy = -10 - random.nextFloat() * 12;
			}
		}
	}

	private boolean mouseWasDown = true;
	protected boolean mouseClickedThisFrame = false;
	private int graceFrames = 4;
	private double scrollAccum = 0;

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scrollAccum += verticalAmount;
		return true;
	}

	protected int pollScroll() {
		int whole = (int) scrollAccum;
		scrollAccum -= whole;
		return whole;
	}

	protected FarmScreen(Text title) {
		super(title);
	}

	protected void updateMouse() {
		boolean down = this.client != null
				&& GLFW.glfwGetMouseButton(this.client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		if (graceFrames > 0) {
			graceFrames--;
			mouseClickedThisFrame = false;
			mouseWasDown = down;
			return;
		}
		mouseClickedThisFrame = down && !mouseWasDown;
		mouseWasDown = down;
	}

	/**
	 * Shift state read straight from GLFW rather than Screen.hasShiftDown().
	 *
	 * <p>Those static helpers exist on Screen in 1.21.8 but were removed by 1.21.11, so a jar
	 * built against one and run on the other dies with NoSuchMethodError the moment the screen
	 * renders. GLFW is not remapped and does not move between versions.
	 */
	public static boolean shiftDown() {
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc == null || mc.getWindow() == null) return false;
		long handle = mc.getWindow().getHandle();
		return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
				|| GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
	}

	public static boolean ctrlDown() {
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (mc == null || mc.getWindow() == null) return false;
		long handle = mc.getWindow().getHandle();
		return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
				|| GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
	}

	public static int blend(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return 0xFF000000
				| ((int) (ar + (br - ar) * t) << 16)
				| ((int) (ag + (bg - ag) * t) << 8)
				| (int) (ab + (bb - ab) * t);
	}

	public static void playClick(net.minecraft.client.MinecraftClient client) {
		try {
			client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
					net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0F));
		} catch (Throwable ignored) {
		}
	}

	protected boolean hover(int mouseX, int mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
	}

	protected void drawWoodPanel(DrawContext ctx, int x, int y, int w, int h) {
		float progress = openProgress();
		if (progress < 1f) {
			// slide+fade the panel in from slightly below
			int offset = (int) ((1f - progress) * 14);
			ctx.fill(x - 4, y - 4 + offset, x + w + 4, y + h + 4 + offset, (int) (0x40 * progress) << 24);
		}
		switch (ForgeCalcTheme.current().panelStyle()) {
			case 1 -> {
				// flat modern card: thin accent border, dark gradient body
				ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, FRAME_DARK);
				ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, GREEN_DARK);
				ctx.fillGradient(x, y, x + w, y + h, PLANK, blend(PLANK, 0xFF000000, 0.45f));
				ctx.fill(x, y, x + w, y + 1, GREEN);
			}
			case 2 -> {
				// soft card with cut corners
				ctx.fill(x + 3, y - 2, x + w - 3, y + h + 2, BORDER);
				ctx.fill(x - 2, y + 3, x + w + 2, y + h - 3, BORDER);
				ctx.fill(x + 2, y - 1, x + w - 2, y + h + 1, PLANK_DARK);
				ctx.fill(x - 1, y + 2, x + w + 1, y + h - 2, PLANK_DARK);
				ctx.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, PLANK_LIGHT, PLANK);
				ctx.fill(x + 3, y + 1, x + w - 3, y + 2, blend(PLANK_LIGHT, 0xFFFFFFFF, 0.3f));
			}
			case 3 -> {
				// double frame with corner ticks
				ctx.fill(x - 3, y - 3, x + w + 3, y + h + 3, FRAME_DARK);
				ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, PLANK_DARK);
				ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
				ctx.fillGradient(x, y, x + w, y + h, PLANK, PLANK_DARK);
				int tick = 7;
				for (int[] corner : new int[][]{{x - 3, y - 3, 1, 1}, {x + w + 2, y - 3, -1, 1}, {x - 3, y + h + 2, 1, -1}, {x + w + 2, y + h + 2, -1, -1}}) {
					ctx.fill(corner[0], corner[1], corner[0] + corner[2] * tick, corner[1] + corner[3], GREEN);
					ctx.fill(corner[0], corner[1], corner[0] + corner[2], corner[1] + corner[3] * tick, GREEN);
				}
			}
			default -> {
				ctx.fill(x - 3, y - 3, x + w + 3, y + h + 3, FRAME_DARK);
				ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
				ctx.fill(x, y, x + w, y + h, PLANK);
				for (int py = y + 12; py < y + h; py += 12) {
					ctx.fill(x, py, x + w, py + 1, PLANK_DARK);
				}
				ctx.fill(x, y, x + w, y + 2, PLANK_LIGHT);
			}
		}
	}

	protected boolean drawBigButton(DrawContext ctx, int mouseX, int mouseY, int x, int y, int w, int h, String label, String sub) {
		return drawBigButton(ctx, mouseX, mouseY, x, y, w, h, label, sub, null);
	}

	protected boolean drawBigButton(DrawContext ctx, int mouseX, int mouseY, int x, int y, int w, int h, String label, String sub, ItemStack icon) {
		boolean hovered = hover(mouseX, mouseY, x, y, w, h);
		float anim = hoverAmount(x + ":" + y + ":" + label, hovered);
		int top = blend(PLANK_LIGHT, GREEN, 0.35f * anim);
		int bottom = blend(PLANK_DARK, PLANK, anim);
		int grow = Math.round(anim);

		ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, FRAME_DARK);
		ctx.fill(x - 1 - grow, y - 1 - grow, x + w + 1 + grow, y + h + 1 + grow, blend(BORDER, GREEN_DARK, anim));
		ctx.fillGradient(x, y, x + w, y + h, top, bottom);
		ctx.fill(x, y, x + w, y + 2, blend(blend(PLANK_LIGHT, 0xFFFFFFFF, 0.25f), GREEN, anim));
		ctx.fill(x, y + h - 2, x + w, y + h, blend(PLANK_DARK, 0xFF000000, 0.3f));
		if (anim > 0.05f) {
			// sheen sweeping across the hovered button
			int sheenW = Math.max(2, (int) (w * 0.18f));
			int sheenX = x + (int) ((w + sheenW) * ((System.currentTimeMillis() % 1400) / 1400f)) - sheenW;
			int left = Math.max(x, sheenX);
			int right = Math.min(x + w, sheenX + sheenW);
			if (right > left) {
				ctx.fill(left, y + 2, right, y + h - 2, ((int) (0x18 * anim) << 24) | 0xFFFFFF);
			}
		}

		int textX = x + w / 2;
		if (icon != null && h >= 20) {
			ctx.drawItem(icon, x + 6, y + h / 2 - 8);
			textX += 8;
		}
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), textX, y + (sub == null ? (h - 8) / 2 : h / 2 - 10), CREAM);
		if (sub != null) {
			ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(sub), textX, y + h / 2 + 2, 0xFFCBB088);
		}

		boolean clicked = hovered && mouseClickedThisFrame;
		if (clicked) {
			mouseClickedThisFrame = false;
			if (this.client != null) {
				playClick(this.client);
			}
		}
		return clicked;
	}

	protected void drawInfoTooltip(DrawContext ctx, int mouseX, int mouseY, List<String> lines) {
		int w = 0;
		for (String line : lines) {
			w = Math.max(w, this.textRenderer.getWidth(line.replaceAll("§[0-9a-fk-or]", "")));
		}
		w += 10;
		int h = lines.size() * 10 + 6;
		int x = Math.min(mouseX + 10, this.width - w - 4);
		int y = Math.min(mouseY - h - 4 < 4 ? mouseY + 12 : mouseY - h - 4, this.height - h - 4);

		ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, FRAME_DARK);
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
		ctx.fill(x, y, x + w, y + h, 0xF0241A10);
		int ty = y + 4;
		for (String line : lines) {
			ctx.drawText(this.textRenderer, Text.literal(line), x + 5, ty, 0xFFFFFFFF, true);
			ty += 10;
		}
	}

	protected boolean drawToggleRow(DrawContext ctx, int mouseX, int mouseY, int x, int y, int w, String label, boolean value) {
		boolean hovered = hover(mouseX, mouseY, x, y, w, 16);
		ctx.fill(x, y, x + w, y + 16, hovered ? 0x40FFFFFF : 0x20000000);
		ctx.drawText(this.textRenderer, Text.literal("§f" + label), x + 4, y + 4, 0xFFFFFFFF, true);

		int pillW = 30;
		int pillX = x + w - pillW - 3;
		ctx.fill(pillX - 1, y + 2, pillX + pillW + 1, y + 14, FRAME_DARK);
		ctx.fill(pillX, y + 3, pillX + pillW, y + 13, value ? GREEN_DARK : 0xFF6A3028);
		ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(value ? "§aON" : "§cOFF"), pillX + pillW / 2, y + 4, 0xFFFFFFFF);

		boolean clicked = hovered && mouseClickedThisFrame;
		if (clicked) {
			mouseClickedThisFrame = false;
			if (this.client != null) {
				playClick(this.client);
			}
		}
		return clicked;
	}
}
