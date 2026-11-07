package fft_battleground.dump.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.reports.model.LeaderboardBalanceData;
import fft_battleground.reports.model.LeaderboardBalanceHistoryEntry;
import fft_battleground.repo.repository.PlayerRecordRepo;

/**
 * Regression for the count>168 collapse (2026-10-24): integer division made
 * hoursPerSlice zero, every date slice collapsed to "now", and the method
 * produced no usable points for counts above the 168-hour window. The clamp
 * must yield real distinct hourly points instead.
 */
public class BalanceHistoryServiceImplTest {

	@Test
	public void countsAboveTheWeekWindowAreClampedToDistinctHourlyPoints() {
		BalanceHistoryServiceImpl service = new BalanceHistoryServiceImpl();
		PlayerRecordRepo repo = mock(PlayerRecordRepo.class);
		when(repo.findById(anyString())).thenReturn(Optional.empty());
		ReflectionTestUtils.setField(service, "playerRecordRepo", repo);

		List<LeaderboardBalanceHistoryEntry> entries = Arrays.asList(
				new LeaderboardBalanceHistoryEntry("someplayer", List.of()));

		LeaderboardBalanceData data = service.getLabelsAndSetRelevantBalanceHistories(entries, 200);

		int points = data.getLeaderboardBalanceHistories().get(0).getBalanceHistory().size();
		//the broken build returned 0 usable points (all slices collapsed to "now");
		//the clamp yields the full distinct-hour window
		assertTrue(points >= 100, "expected ~168 hourly points, got " + points);
		assertTrue(points <= 168, "count must clamp at the 168-hour window, got " + points);
	}

}
