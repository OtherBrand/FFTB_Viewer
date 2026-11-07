package fft_battleground.repo.repository;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fft_battleground.repo.model.BotBetData;

@Transactional
@Repository
public interface BotBetDataRepo extends JpaRepository<BotBetData, Long> {

	@Query("SELECT botBetData FROM BotBetData botBetData ORDER BY botBetData.createDateTime ASC")
	public List<BotBetData> getFirstBotBetDataEntry(Pageable page);
	
	@Query("SELECT botBetData FROM BotBetData botBetData WHERE botBetData.createDateTime >= :from AND botBetData.createDateTime < :to ORDER BY botBetData.createDateTime ASC")
	public List<BotBetData> getBotBetDataBetween(@Param("from") java.util.Date from, @Param("to") java.util.Date to);

	@Query("SELECT botBetData FROM BotBetData botBetData WHERE botBetData.tournamentId IN :tournamentIds")
	public List<BotBetData> getBotBetDataForTournaments(@Param("tournamentIds") List<Long> tournamentIds);
	
	public default BotBetData getFirstTournament() {
		List<BotBetData> optionalFirstTournament = this.getFirstBotBetDataEntry(PageRequest.of(0, 1));
		return optionalFirstTournament.size() > 0 ? optionalFirstTournament.get(0) : null;
	}
}
