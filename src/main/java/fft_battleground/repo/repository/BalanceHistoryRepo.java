package fft_battleground.repo.repository;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.BalanceHistory;
import fft_battleground.repo.repository.projection.DailyBalanceBucket;
import fft_battleground.repo.repository.projection.HourlyBalanceBucket;
import lombok.SneakyThrows;

@Repository
public interface BalanceHistoryRepo extends JpaRepository<BalanceHistory, String> {

	//hourly/daily rollups for the botland progress tab: aggregate the full ledger (all update types)
	//in SQL instead of shipping bet-level rows to the client. Buckets are UTC-date strings; ordering
	//by (create_timestamp, balance_history_id) keeps same-second updates deterministic
	@Query(value = "SELECT to_char(date_trunc('day', create_timestamp), 'YYYY-MM-DD') AS bucket, "
			+ "(array_agg(balance ORDER BY create_timestamp, balance_history_id))[1] AS startBalance, "
			+ "(array_agg(balance ORDER BY create_timestamp DESC, balance_history_id DESC))[1] AS endBalance, "
			+ "min(balance) AS minBalance, max(balance) AS maxBalance, "
			+ "coalesce(sum(balance_change), 0) AS netChange, count(*) AS updates "
			+ "FROM balance_history WHERE lower(player) = lower(:player) GROUP BY 1 ORDER BY 1", nativeQuery = true)
	public List<DailyBalanceBucket> getDailyBalanceBuckets(@Param("player") String player);

	@Query(value = "SELECT to_char(date_trunc('hour', create_timestamp), 'YYYY-MM-DD\"T\"HH24:00') AS bucket, "
			+ "(array_agg(balance ORDER BY create_timestamp, balance_history_id))[1] AS startBalance, "
			+ "(array_agg(balance ORDER BY create_timestamp DESC, balance_history_id DESC))[1] AS endBalance, "
			+ "min(balance) AS minBalance, max(balance) AS maxBalance, "
			+ "coalesce(sum(balance_change), 0) AS netChange, count(*) AS updates "
			+ "FROM balance_history WHERE lower(player) = lower(:player) "
			+ "AND create_timestamp >= now() - interval '7 days' GROUP BY 1 ORDER BY 1", nativeQuery = true)
	public List<HourlyBalanceBucket> getHourlyBalanceBuckets(@Param("player") String player);

	@Query("Select balanceHistory from BalanceHistory balanceHistory where balanceHistory.player = :player AND balanceHistory.type= 'DUMP' ORDER BY create_timestamp DESC")
	public List<BalanceHistory> getTournamentBalanceHistory(String player, Pageable pageable);
	
	@Query("Select balanceHistory from BalanceHistory balanceHistory where balanceHistory.player = :player AND balanceHistory.type= 'DUMP' AND balanceHistory.create_timestamp >= :timestamp ORDER BY create_timestamp DESC")
	public List<BalanceHistory> getTournamentBalanceHistory(@Param("player") String player, @Param("timestamp") Date timestamp);
	
	@SneakyThrows
	public default List<BalanceHistory> getTournamentBalanceHistoryFromPastWeek(String player) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.DATE, -7);
		Date timestamp = calendar.getTime();
		List<BalanceHistory> data = this.getTournamentBalanceHistory(player, timestamp);
		SimpleDateFormat sdf = new SimpleDateFormat("dd-M-yyyy hh");
		return data;
	}
}
