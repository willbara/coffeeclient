package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyEstimatorTest {
	@Test
	void firstSampleSeedsMeanAndHalfDeviation() {
		Latency.Estimator estimator = new Latency.Estimator();
		assertFalse(estimator.fresh(0));
		estimator.sample(100, 0);
		assertEquals(100, estimator.mean(), 1e-9);
		assertEquals(50, estimator.deviation(), 1e-9);
		assertTrue(estimator.fresh(5_000));
		assertFalse(estimator.fresh(Latency.Estimator.SAMPLE_LIFETIME));
	}

	@Test
	void laterSamplesSmooth() {
		Latency.Estimator estimator = new Latency.Estimator();
		estimator.sample(100, 0);
		estimator.sample(200, 0);
		assertEquals(112.5, estimator.mean(), 1e-9);
		assertEquals(0.75 * 50 + 0.25 * 100, estimator.deviation(), 1e-9);
	}

	@Test
	void samplesAreClamped() {
		Latency.Estimator estimator = new Latency.Estimator();
		estimator.sample(-5, 0);
		assertEquals(0, estimator.mean(), 1e-9);
		estimator.reset();
		estimator.sample(60_000, 0);
		assertEquals(10_000, estimator.mean(), 1e-9);
	}
}
