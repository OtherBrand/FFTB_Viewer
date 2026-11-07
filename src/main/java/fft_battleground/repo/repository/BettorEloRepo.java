package fft_battleground.repo.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.BettorElo;

@Repository
public interface BettorEloRepo extends JpaRepository<BettorElo, String> {

	//players are stored lowercased; callers pass lowercased names
	public List<BettorElo> findByPlayerIn(Collection<String> players);

	//provisional is derived (wins+losses < 20), so the ranked window is expressed on the columns
	@Query("SELECT b FROM BettorElo b WHERE (b.pickWins + b.pickLosses) >= :minMatches ORDER BY b.elo DESC")
	public List<BettorElo> findRanked(@Param("minMatches") int minMatches, Pageable pageable);

}
