package fft_battleground.repo.repository;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;import com.fasterxml.jackson.databind.introspect.VisibilityChecker;

import fft_battleground.repo.BattleGroundCacheEntryKey;
import fft_battleground.repo.model.BattleGroundCacheEntry;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

public interface BattleGroundCacheEntryRepo extends JpaRepository<BattleGroundCacheEntry, String> {
	
	@Query("SELECT battleGroundCacheEntry FROM BattleGroundCacheEntry battleGroundCacheEntry WHERE battleGroundCacheEntry.cacheEntryId = :id AND battleGroundCacheEntry.lastUpdateDate <= :timestamp ")
	public BattleGroundCacheEntry getCacheEntryByIdAndAgeLimit(@Param("id") String id, @Param("timestamp") Date timestamp);
	
	@Transactional
	public default <T> T readCacheEntry(BattleGroundCacheEntryKey key, Function<String, T> deserializer) throws Exception {
		T result = null;
		
		Calendar ageLimitDate = Calendar.getInstance();
		ageLimitDate.add(key.getCalendarUnit(), key.getTimeValue());
		
		BattleGroundCacheEntry entry = null;
		try {
			entry = this.getCacheEntryByIdAndAgeLimit(key.getKey(), ageLimitDate.getTime());
		} catch(Exception e) {
			throw e;
		}
		
		if(entry != null) {
			
			result = deserializer.apply(entry.getCacheData());
		}
		
		return result;
	}
	
	@SneakyThrows
	public default <T> void writeCacheEntry(T data, String id) {
		BattleGroundCacheEntry entry = new BattleGroundCacheEntry();
		
		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(data);
		Date currentDate = new Date();
		
		entry.setCacheEntryId(id);
		entry.setCacheData(json);
		entry.setLastUpdateDate(currentDate);
		
		this.saveAndFlush(entry);
		return;
	}

}
