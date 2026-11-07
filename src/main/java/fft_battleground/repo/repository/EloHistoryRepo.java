package fft_battleground.repo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.EloHistory;

@Repository
public interface EloHistoryRepo extends JpaRepository<EloHistory, Long> {

	public List<EloHistory> findByPlayerOrderByCreateDateTimeAsc(String player);

}
