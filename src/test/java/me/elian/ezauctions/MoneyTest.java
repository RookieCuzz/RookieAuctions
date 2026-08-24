package me.elian.ezauctions;

import me.elian.ezauctions.model.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {
	@Test
	void roundsAtTheVaultBoundaryAndFormatsDeterministically() {
		assertEquals(1_235L, Money.fromMajor(12.345D));
		assertEquals("12.35", Money.format(1_235L));
		assertEquals(250L, Money.percentage(10_000L, BigDecimal.valueOf(2.5D)));
	}

	@Test
	void rejectsNonFiniteNegativeAndOutOfRangeInputs() {
		assertThrows(IllegalArgumentException.class, () -> Money.fromMajor(Double.NaN));
		assertThrows(IllegalArgumentException.class, () -> Money.fromMajor(Double.POSITIVE_INFINITY));
		assertThrows(IllegalArgumentException.class, () -> Money.parseMajor("-1", 100_000L));
		assertThrows(IllegalArgumentException.class, () -> Money.parseMajor("1000.01", 100_000L));
	}
}
