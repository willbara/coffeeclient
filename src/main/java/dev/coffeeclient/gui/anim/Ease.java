package dev.coffeeclient.gui.anim;

/** Easing curves on 0..1 (input is clamped). */
public enum Ease {
	LINEAR {
		@Override
		protected float curve(float t) {
			return t;
		}
	},
	OUT_CUBIC {
		@Override
		protected float curve(float t) {
			float u = 1 - t;
			return 1 - u * u * u;
		}
	},
	IN_CUBIC {
		@Override
		protected float curve(float t) {
			return t * t * t;
		}
	},
	IN_OUT_CUBIC {
		@Override
		protected float curve(float t) {
			return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
		}
	},
	OUT_QUINT {
		@Override
		protected float curve(float t) {
			float u = 1 - t;
			return 1 - u * u * u * u * u;
		}
	},
	OUT_EXPO {
		@Override
		protected float curve(float t) {
			return t >= 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
		}
	},
	/** Overshoots slightly, then settles. */
	OUT_BACK {
		@Override
		protected float curve(float t) {
			float c1 = 1.70158f, c3 = c1 + 1, u = t - 1;
			return 1 + c3 * u * u * u + c1 * u * u;
		}
	},
	OUT_ELASTIC {
		@Override
		protected float curve(float t) {
			if (t <= 0 || t >= 1) return t;
			return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
		}
	},
	OUT_BOUNCE {
		@Override
		protected float curve(float t) {
			float n = 7.5625f, d = 2.75f;
			if (t < 1 / d) return n * t * t;
			if (t < 2 / d) return n * (t -= 1.5f / d) * t + 0.75f;
			if (t < 2.5 / d) return n * (t -= 2.25f / d) * t + 0.9375f;
			return n * (t -= 2.625f / d) * t + 0.984375f;
		}
	};

	protected abstract float curve(float t);

	public float apply(float t) {
		return curve(Math.max(0f, Math.min(1f, t)));
	}
}
