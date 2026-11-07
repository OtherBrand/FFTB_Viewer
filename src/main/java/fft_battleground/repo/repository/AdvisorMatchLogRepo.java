package fft_battleground.repo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import fft_battleground.repo.model.AdvisorMatchLog;

@Repository
public interface AdvisorMatchLogRepo extends JpaRepository<AdvisorMatchLog, Long> {

	public java.util.List<AdvisorMatchLog> findByTournamentIdOrderByAdvisorMatchLogIdDesc(Long tournamentId);

}
