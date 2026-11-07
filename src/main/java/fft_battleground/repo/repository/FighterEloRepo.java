package fft_battleground.repo.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.FighterElo;

@Repository
public interface FighterEloRepo extends JpaRepository<FighterElo, String> {

	public List<FighterElo> findByPlayerIn(Collection<String> players);

	//provisional is derived (wins+losses < 20), so the ranked window is expressed on the columns
	@Query("SELECT f FROM FighterElo f WHERE (f.fightWins + f.fightLosses) >= :minMatches ORDER BY f.elo DESC")
	public List<FighterElo> findRanked(@Param("minMatches") int minMatches, Pageable pageable);

}
