package fft_battleground.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import fft_battleground.repo.model.EloHistory;
import fft_battleground.repo.repository.EloHistoryRepo;
import fft_battleground.util.GenericResponse;
import fft_battleground.controller.response.model.EloHistoryPoint;

/**
 * The /api/players/eloHistory contract (R4): ascending rating-after points,
 * trailing window capped at 200, epoch-ms timestamps, lowercased player lookup.
 */
public class EloHistoryEndpointTest {

	@Test
	public void servesAscendingPointsCappedToTheTrailingWindow() {
		PlayerRecordController controller = new PlayerRecordController();
		EloHistoryRepo repo = mock(EloHistoryRepo.class);
		List<EloHistory> stored = new ArrayList<>();
		for(int i = 0; i < 250; i++) {
			stored.add(new EloHistory("someplayer", (long) i, 1.0, 1500.0 + i, 1501.0 + i));
			stored.get(i).setCreateDateTime(new Date(1_000_000L * i));
		}
		when(repo.findByPlayerOrderByCreateDateTimeAsc(anyString())).thenReturn(stored);
		ReflectionTestUtils.setField(controller, "eloHistoryRepo", repo);

		ResponseEntity<GenericResponse<List<EloHistoryPoint>>> response = controller.getEloHistory("SomePlayer");

		List<EloHistoryPoint> points = response.getBody().getData();
		assertEquals(200, points.size(), "trailing window cap");
		//first point is #50 of 250: rating_after 1551, timestamp 50s
		assertEquals(1551.0, points.get(0).getElo(), 1e-9);
		assertEquals(50_000_000L, points.get(0).getTimestamp());
		//ascending timestamps throughout
		List<Long> timestamps = points.stream().map(EloHistoryPoint::getTimestamp).collect(Collectors.toList());
		for(int i = 1; i < timestamps.size(); i++) {
			assertTrue(timestamps.get(i) > timestamps.get(i - 1), "ascending order");
		}
	}

	@Test
	public void unknownPlayerGetsEmptyData() {
		PlayerRecordController controller = new PlayerRecordController();
		EloHistoryRepo repo = mock(EloHistoryRepo.class);
		when(repo.findByPlayerOrderByCreateDateTimeAsc(anyString())).thenReturn(List.of());
		ReflectionTestUtils.setField(controller, "eloHistoryRepo", repo);

		ResponseEntity<GenericResponse<List<EloHistoryPoint>>> response = controller.getEloHistory("nobody");

		assertTrue(response.getBody().getData().isEmpty());
		Mockito.verify(repo).findByPlayerOrderByCreateDateTimeAsc("nobody");
	}

}
