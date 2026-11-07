package fft_battleground.dump.service;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fft_battleground.dump.cache.map.LastActiveCache;
import fft_battleground.dump.cache.map.LastFightActiveCache;
import fft_battleground.repo.model.BalanceHistory;
import fft_battleground.repo.model.PlayerRecord;
import fft_battleground.repo.repository.PlayerRecordRepo;
import fft_battleground.reports.model.LeaderboardBalanceData;
import fft_battleground.reports.model.LeaderboardBalanceHistoryEntry;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class BalanceHistoryServiceImpl implements BalanceHistoryService {
	
	@Autowired
	private PlayerRecordRepo playerRecordRepo;
	
	@Autowired
	private LastActiveCache lastActiveCache;
	
	@Autowired
	private LastFightActiveCache lastFightActiveCache;
	
	@Override
	public boolean isPlayerActiveInLastMonth(String player) {
		Date lastActive = this.lastActiveCache.get(player);
		Date lastFightActive = this.lastFightActiveCache.get(player);
		
		boolean isLastActive = lastActive != null ? this.isPlayerActiveInLastMonth(lastActive) : false;
		boolean isLastFightActive = lastFightActive != null ? this.isPlayerActiveInLastMonth(lastFightActive) : false;
		boolean result =  isLastActive || isLastFightActive;
		return result;
	}
	
	@Override
	public boolean isPlayerActiveInLastMonth(Date lastActiveDate) {
		if (lastActiveDate == null) {
			return false;
		}

		Calendar thirtyDaysAgo = Calendar.getInstance();
		thirtyDaysAgo.add(Calendar.DAY_OF_MONTH, -30); // 2020-01-25

		Date thirtyDaysAgoDate = thirtyDaysAgo.getTime();
		boolean isActive = lastActiveDate.after(thirtyDaysAgoDate);
		return isActive;
	}
	
	// this time let's take it hour by hour, and then use my original date slice
	// algorithm
	@Override
	@SneakyThrows
	public LeaderboardBalanceData getLabelsAndSetRelevantBalanceHistories(List<LeaderboardBalanceHistoryEntry> entries,
			Integer count) {
		SimpleDateFormat sdf = new SimpleDateFormat("dd-M-yyyy hh");

		// dynamically pick best hours to find entries for one week
		int hoursToTrack = 168;
		//168 hourly buckets is this method's ceiling (hour-granularity matching over a 1-week window);
		//a larger count made hoursPerSlice divide to zero, collapsing every slice to "now" and
		//returning the same single entry repeated for every requested point
		if(count > hoursToTrack) {
			count = hoursToTrack;
		}
		int hoursPerSlice = hoursToTrack / count;
		List<Date> dateSlices = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Calendar calendar = Calendar.getInstance();
			int hourSliceSize = i * hoursPerSlice * -1;
			calendar.add(Calendar.HOUR, hourSliceSize);
			Date dateSlice = calendar.getTime();
			sdf.parse(sdf.format(dateSlice));
			dateSlices.add(dateSlice);
		}

		Collections.sort(dateSlices);

		// simplify all dates to nearest hour
		for (LeaderboardBalanceHistoryEntry entry : entries) {
			for (BalanceHistory balanceHistory : entry.getBalanceHistory()) {
				String simplifiedDateString = sdf.format(balanceHistory.getCreate_timestamp());
				Date simplifiedDate = sdf.parse(simplifiedDateString);
				balanceHistory.setCreate_timestamp(new Timestamp(simplifiedDate.getTime()));
			}
		}

		// now lets extrapolate each player's balance history by hour
		List<LeaderboardBalanceHistoryEntry> extrapolatedData = new ArrayList<>();
		for (LeaderboardBalanceHistoryEntry entry : entries) {
			LeaderboardBalanceHistoryEntry extrapolatedEntry = new LeaderboardBalanceHistoryEntry(entry.getPlayerName(),
					new ArrayList<BalanceHistory>());
			Integer currentAmount = 0;
			for (int i = 0; i < hoursToTrack; i++) {
				Calendar calendar = Calendar.getInstance();
				calendar.add(Calendar.HOUR, (-1 * hoursToTrack) + i);

				// find nearest balanceHistory match
				boolean matchFound = false;
				for (int j = 0; j < entry.getBalanceHistory().size() && !matchFound; j++) {
					if (this.twoDatesMatchSameExactHour(calendar.getTime(),
							entry.getBalanceHistory().get(j).getCreate_timestamp())) {
						matchFound = true;
						currentAmount = entry.getBalanceHistory().get(j).getBalance();
					}
				}

				extrapolatedEntry.getBalanceHistory()
						.add(new BalanceHistory(entry.getPlayerName(), currentAmount, calendar.getTime()));
			}
			extrapolatedData.add(extrapolatedEntry);
		}

		// reduce balance history to appropriate values
		for (LeaderboardBalanceHistoryEntry entry : extrapolatedData) {
			List<BalanceHistory> truncatedHistory = new ArrayList<>();
			for (int i = 0; i < dateSlices.size(); i++) {
				Date currentSlice = dateSlices.get(i);
				boolean foundEntry = false;
				// search balance history for first entry with the correct hour
				Integer currentAmount = 0;
				for (int j = 0; j < entry.getBalanceHistory().size() && !foundEntry; j++) {
					Calendar currentSliceCalendar = Calendar.getInstance();
					currentSliceCalendar.setTime(currentSlice);
					Calendar currentBalanceHistoryDateCalendar = Calendar.getInstance();
					Date currentBalanceHistoryDate = entry.getBalanceHistory().get(j).getCreate_timestamp();
					currentBalanceHistoryDateCalendar.setTime(currentBalanceHistoryDate);
					if (this.twoDatesMatchSameExactHour(currentSlice, currentBalanceHistoryDate)) {
						foundEntry = true;
						truncatedHistory.add(entry.getBalanceHistory().get(j));
						currentAmount = entry.getBalanceHistory().get(j).getBalance();
					}
				}

				if (!foundEntry && this.twoDatesMatchSameExactHour(currentSlice, new Date())) {
					foundEntry = true;
					Optional<PlayerRecord> maybePlayer = this.playerRecordRepo
							.findById(StringUtils.lowerCase(entry.getPlayerName()));
					if (maybePlayer.isPresent()) {
						foundEntry = true;
						Date simplifiedDate = sdf.parse(sdf.format(new Date()));
						truncatedHistory.add(new BalanceHistory(entry.getPlayerName(),
								maybePlayer.get().getLastKnownAmount(), simplifiedDate));
					}
				}
				// if none found, create a valid blank entry
				if (!foundEntry) {
					log.warn("could not find an entry even with fully extrapolated data, something went wrong");
					truncatedHistory.add(new BalanceHistory(entry.getPlayerName(), currentAmount, currentSlice));
				}
				// reset foundEntry
				foundEntry = false;
			}
			entry.setBalanceHistory(truncatedHistory);
		}

		// smooth out zero results. 0 is not possible for balances, so must be caused by
		// missing data
		for (LeaderboardBalanceHistoryEntry entry : extrapolatedData) {
			this.smoothOutZeroes(entry);
		}

		LeaderboardBalanceData data = new LeaderboardBalanceData(dateSlices, extrapolatedData, count);

		return data;
	}

	/**
	 * Zero is not a valid result for balance, and must be caused by missing data.
	 * This function iterates over all balance history data backwards, setting any 0
	 * value to the first valid value that follows it.
	 * 
	 * @param extrapolatedData
	 */
	protected void smoothOutZeroes(LeaderboardBalanceHistoryEntry extrapolatedData) {
		// increment in reverse
		Integer previousAmount = null;
		List<BalanceHistory> balanceHistory = extrapolatedData.getBalanceHistory();
		for (int i = balanceHistory.size() - 1; i >= 0; i--) {
			BalanceHistory currentBalanceHistory = balanceHistory.get(i);
			if (previousAmount == null) {
				previousAmount = currentBalanceHistory.getBalance();
			} else if (currentBalanceHistory.getBalance().equals(0)) {
				currentBalanceHistory.setBalance(previousAmount);
			} else {
				previousAmount = currentBalanceHistory.getBalance();
			}
		}

		return;
	}

	protected boolean twoDatesMatchSameExactHour(Date currentSlice, Date currentBalanceHistoryDate) {
		Calendar currentSliceCalendar = Calendar.getInstance();
		currentSliceCalendar.setTime(currentSlice);
		Calendar currentBalanceHistoryDateCalendar = Calendar.getInstance();
		currentBalanceHistoryDateCalendar.setTime(currentBalanceHistoryDate);
		boolean result = currentSliceCalendar.get(Calendar.MONTH) == currentBalanceHistoryDateCalendar
				.get(Calendar.MONTH)
				&& currentSliceCalendar.get(Calendar.DAY_OF_MONTH) == currentBalanceHistoryDateCalendar
						.get(Calendar.DAY_OF_MONTH)
				&& currentSliceCalendar.get(Calendar.HOUR_OF_DAY) == currentBalanceHistoryDateCalendar
						.get(Calendar.HOUR_OF_DAY);

		return result;
	}
}
