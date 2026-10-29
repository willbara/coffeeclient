package dev.coffeeclient.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {
	@Test
	void startsFullAndAllowsTheBurst() {
		TokenBucket bucket = new TokenBucket(10, 3);
		assertTrue(bucket.tryTake(0));
		assertTrue(bucket.tryTake(0));
		assertTrue(bucket.tryTake(0));
		assertFalse(bucket.tryTake(0));
	}

	@Test
	void refillsAtTheRate() {
		TokenBucket bucket = new TokenBucket(10, 1);
		assertTrue(bucket.tryTake(0));
		assertFalse(bucket.tryTake(50));
		assertTrue(bucket.tryTake(100));
	}

	@Test
	void neverRefillsPastTheBurst() {
		TokenBucket bucket = new TokenBucket(100, 2);
		bucket.refill(0);
		assertEquals(2, bucket.available(60_000), 1e-9);
	}

	@Test
	void lowerBurstTrimsTokens() {
		TokenBucket bucket = new TokenBucket(10, 5);
		bucket.refill(0);
		bucket.configure(10, 2);
		assertEquals(2, bucket.available(0), 1e-9);
	}
}
