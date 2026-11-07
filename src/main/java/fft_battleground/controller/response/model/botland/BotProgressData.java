package fft_battleground.controller.response.model.botland;

import java.util.List;

import fft_battleground.repo.repository.projection.DailyBalanceBucket;
import fft_battleground.repo.repository.projection.HourlyBalanceBucket;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BotProgressData {

	private List<DailyBalanceBucket> daily;

	private List<HourlyBalanceBucket> hourly;

}
