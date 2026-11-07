package fft_battleground.repo.repository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.MutableTriple;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.Bots;
import fft_battleground.util.GambleUtil;
import lombok.SneakyThrows;

@Repository
public interface BotsRepo extends JpaRepository<Bots, Long> {

	//case-insensitive: bot rows are created with the roster's configured case, but some callers
	//(reportBotWin/Loss) look up with lowercased names — exact '=' silently skipped those bots
	@Query("SELECT Bot FROM Bots Bot WHERE Bot.dateString = :dateString AND lower(Bot.player) = lower(:name)")
	public Bots getBotByDateStringAndName(@Param("dateString") String dateString, @Param("name") String name);
	
	@Query("SELECT Bot FROM Bots Bot WHERE Bot.dateString = :dateString")
	public List<Bots> getBotByDateString(@Param("dateString") String dateString);
	
	@Query(value=" SELECT Bot.bot_entry_id AS bot_entry_id, Bot.balance AS balance, Bot.create_date_time AS date_time, "
			+ "	Bot.date_string AS date_string, Bot.highest_known_value AS highest_known_value, Bot.losses as losses, "
			+ "	Bot.player AS player, Bot.update_date_time AS update_date_time, Bot.wins AS wins, "
			+ " Bot.create_date_time AS create_date_time "
			+ " FROM bot_record Bot JOIN ( "
			+ "	SELECT player AS player, MAX(highest_known_value) AS highest_known_value "
			+ "	FROM bot_record "
			+ "	GROUP BY player) maxBot "
			+ " ON Bot.player = maxBot.player and Bot.highest_known_value = maxBot.highest_known_value "
			+ " ORDER BY update_date_time DESC ", nativeQuery=true)
	public List<Bots> highestKnownValueHistorical();
	
	@Query(value=" SELECT Bot.bot_entry_id AS bot_entry_id, Bot.balance AS balance, Bot.create_date_time AS date_time, "
			+ "	Bot.date_string AS date_string, Bot.highest_known_value AS highest_known_value, Bot.losses as losses, "
			+ "	Bot.player AS player, Bot.update_date_time AS update_date_time, Bot.wins AS wins, "
			+ " Bot.create_date_time AS create_date_time "
			+ " FROM bot_record Bot JOIN ( "
			+ "	SELECT date_string AS date_string, MAX(balance) AS balance "
			+ "	FROM bot_record "
			+ "	GROUP BY date_string) maxBot\r\n"
			+ " ON Bot.date_string = maxBot.date_string and Bot.balance = maxBot.balance "
			+ " ORDER BY update_date_time DESC ", nativeQuery=true)
	public List<Bots> highestBalancePerDay();
	
	@Query(value=" SELECT Bot.bot_entry_id AS bot_entry_id, Bot.balance AS balance, Bot.create_date_time AS date_time, "
			+ "	Bot.date_string AS date_string, Bot.highest_known_value AS highest_known_value, Bot.losses as losses, "
			+ "	Bot.player AS player, Bot.update_date_time AS update_date_time, Bot.wins AS wins, "
			+ " Bot.create_date_time AS create_date_time "
			+ " FROM bot_record Bot JOIN ( "
			+ "	SELECT player, MIN(update_date_time) AS update_date_time "
			+ "	FROM bot_record "
			+ "	GROUP BY player) maxBot "
			+ " ON Bot.player = maxBot.player and Bot.update_date_time = maxBot.update_date_time "
			+ " ORDER BY Bot.player DESC ", nativeQuery=true)
	public List<Bots> getOldestEntries();
	
	@Query("Select new fft_battleground.repo.repository.StringLongPairWrapper(Bot.player, SUM(Bot.wins)) FROM Bots Bot GROUP BY Bot.player")
	public List<Pair<String, Long>> getWinsPerBot();
	
	@Query("Select new fft_battleground.repo.repository.StringDoublePairWrapper(Bot.player, AVG(Bot.wins)) FROM Bots Bot GROUP BY Bot.player")
	public List<Pair<String, Double>> getAverageWinsPerDayPerBot();
	
	@Query("Select new fft_battleground.repo.repository.StringLongPairWrapper(Bot.player, SUM(Bot.losses)) FROM Bots Bot GROUP BY Bot.player")
	public List<Pair<String, Long>> getLossesPerBot();
	
	@Query("Select new fft_battleground.repo.repository.StringDoublePairWrapper(Bot.player, AVG(Bot.losses)) FROM Bots Bot GROUP BY Bot.player")
	public List<Pair<String, Double>> getAverageLossesPerDayPerBot();
	
	@Query("Select new fft_battleground.repo.repository.StringDoublePairWrapper(Bot.player, AVG(Bot.balance)) FROM Bots Bot GROUP BY Bot.player")
	public List<Pair<String, Double>> getAverageEndDayBalancePerBot();
	
	@Query("Select new fft_battleground.repo.repository.StringDoublePairWrapper(Bot.player, AVG(Bot.highestKnownValue)) FROM Bots Bot GROUP BY Bot.player")
	public List<Pair<String, Double>> getAveragePeakBalancePerBot();
	
	@Query("Select new fft_battleground.repo.repository.StringDateDoubleTriple(Bot.player, Bot.updateDateTime,  (Bot.wins + 1)/(Bot.wins + Bot.losses + 1)) FROM Bots Bot")
	public List<Triple<String, Date, Integer>> getDailyWinRatePerBot();
	
	@Query("Select new fft_battleground.repo.repository.StringLongPairWrapper(Bot.player, COUNT(Bot.player)) FROM Bots Bot GROUP BY Bot.player")
	public List<Pair<String, Long>> getDaysParticipatingPerBot();
	
	public default List<Bots> getBotsForToday() {
		String currentDateString = this.currentDateString();
		List<Bots> botData = this.getBotByDateString(currentDateString);
		
		return botData;
	}
	
	@SneakyThrows
	public default String currentDateString() {
		Date currentDate = new Date();
		SimpleDateFormat sdf = Bots.createDateFormatter();
		String currentDateString = sdf.format(currentDate);
		return currentDateString;
	}
	
	public default Bots addNewBotForToday(String name, boolean isBotAccountSubscriber) {
		String currentDateString = this.currentDateString();
		Bots newBot = new Bots(name, currentDateString, GambleUtil.getMinimumBetForBettor(isBotAccountSubscriber));
		this.saveAndFlush(newBot);
		
		return newBot;
	}
}

class StringLongPairWrapper extends MutablePair<String, Long> {

	/**
	 * 
	 */
	private static final long serialVersionUID = -2999516029914886724L;
	
	public StringLongPairWrapper() {
		super();
	}
	
	public StringLongPairWrapper(String left, Long right) {
		super(left, right);
	}
	
}

class StringDoublePairWrapper extends MutablePair<String, Double> {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = -6044857181796248713L;

	public StringDoublePairWrapper() {
		super();
	}
	
	public StringDoublePairWrapper(String left, Double right) {
		super(left, right);
	}
}

class StringDateDoubleTriple extends MutableTriple<String, Date, Integer> {

	/**
	 * 
	 */
	private static final long serialVersionUID = -4861697397280494630L;
	
	public StringDateDoubleTriple() {
		super();
	}
	
	public StringDateDoubleTriple(String left, Date center, Integer right) {
		super(left, center, right);
	}
}
