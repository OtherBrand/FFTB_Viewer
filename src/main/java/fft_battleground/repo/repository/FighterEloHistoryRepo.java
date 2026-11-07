package fft_battleground.repo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.FighterEloHistory;

@Repository
public interface FighterEloHistoryRepo extends JpaRepository<FighterEloHistory, Long> {

	public List<FighterEloHistory> findByPlayerOrderByCreateDateTimeAsc(String player);

}
